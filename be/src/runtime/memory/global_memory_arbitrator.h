// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#pragma once

#include "runtime/process_profile.h"
#include "util/mem_info.h"

namespace doris {
#include "common/compile_check_begin.h"

class GlobalMemoryArbitrator {
public:
    static inline void reset_refresh_interval_memory_growth() {
        refresh_interval_memory_growth = 0;
    }

    // If need to use process memory in your execution logic, pls use it.
    // equal to real process memory(vm_rss), subtract jemalloc dirty page cache,
    // add reserved memory and growth memory since the last vm_rss update.
    static inline int64_t process_memory_usage() {
        return PerfCounters::get_vm_rss() +
               refresh_interval_memory_growth.load(std::memory_order_relaxed) +
               process_reserved_memory();
    }

    static std::string process_memory_used_str() {
        auto msg = fmt::format("process memory used {}",
                               PrettyPrinter::print(process_memory_usage(), TUnit::BYTES));
#ifdef ADDRESS_SANITIZER
        msg = "[ASAN]" + msg;
#endif
        return msg;
    }

    static std::string process_memory_used_details_str() {
        auto msg = fmt::format(
                "process memory used {}(= {}[vm/rss] + {}[reserved] + {}B[waiting_refresh])",
                PrettyPrinter::print(process_memory_usage(), TUnit::BYTES),
                PerfCounters::get_vm_rss_str(),
                PrettyPrinter::print(process_reserved_memory(), TUnit::BYTES),
                refresh_interval_memory_growth);
#ifdef ADDRESS_SANITIZER
        msg = "[ASAN]" + msg;
#endif
        return msg;
    }

    static inline int64_t sys_mem_available() {
        return MemInfo::_s_sys_mem_available.load(std::memory_order_relaxed) -
               refresh_interval_memory_growth.load(std::memory_order_relaxed) -
               process_reserved_memory();
    }

    static inline std::string sys_mem_available_str() {
        auto msg = fmt::format("sys available memory {}",
                               PrettyPrinter::print(sys_mem_available(), TUnit::BYTES));
#ifdef ADDRESS_SANITIZER
        msg = "[ASAN]" + msg;
#endif
        return msg;
    }

    static inline std::string sys_mem_available_details_str() {
        auto msg = fmt::format(
                "sys available memory {}(= {}[proc/available] - {}[reserved] - "
                "{}B[waiting_refresh])",
                PrettyPrinter::print(sys_mem_available(), TUnit::BYTES),
                PrettyPrinter::print(MemInfo::_s_sys_mem_available.load(std::memory_order_relaxed),
                                     TUnit::BYTES),
                PrettyPrinter::print(process_reserved_memory(), TUnit::BYTES),
                refresh_interval_memory_growth);
#ifdef ADDRESS_SANITIZER
        msg = "[ASAN]" + msg;
#endif
        return msg;
    }

    static bool reserve_process_memory(int64_t bytes);
    static bool try_reserve_process_memory(int64_t bytes);
    static void shrink_process_reserved(int64_t bytes);

    static inline int64_t process_reserved_memory() {
        return _process_reserved_memory.load(std::memory_order_relaxed);
    }

    // `process_memory_usage` includes all reserved memory. if a thread has `reserved_memory`,
    // and the memory allocated by thread is less than the thread `reserved_memory`,
    // even if `process_memory_usage` is greater than `process_mem_limit`, memory can still be allocated.
    // At this time, `process_memory_usage` will not increase, process physical memory will increase,
    // and `reserved_memory` will be reduced.
    static int64_t sub_thread_reserve_memory(int64_t bytes);

    static bool is_exceed_soft_mem_limit(int64_t bytes = 0) {
        if (bytes > 0 && sub_thread_reserve_memory(bytes) <= 0) {
            return false;
        }
        auto rt = process_memory_usage() + bytes >= MemInfo::soft_mem_limit() ||
                  sys_mem_available() - bytes < MemInfo::sys_mem_available_warning_water_mark();
        if (rt) {
            doris::ProcessProfile::instance()->memory_profile()->print_log_process_usage();
        }
        return rt;
    }

    static bool is_exceed_hard_mem_limit(int64_t bytes = 0) {
        if (bytes > 0 && sub_thread_reserve_memory(bytes) <= 0) {
            return false;
        }
        // Limit process memory usage using the actual physical memory of the process in `/proc/self/status`.
        // This is independent of the consumption value of the mem tracker, which counts the virtual memory
        // of the process malloc.
        // for fast, expect MemInfo::initialized() to be true.
        //
        // tcmalloc/jemalloc allocator cache does not participate in the mem check as part of the process physical memory.
        // because `new/malloc` will trigger mem hook when using tcmalloc/jemalloc allocator cache,
        // but it may not actually alloc physical memory, which is not expected in mem hook fail.
        auto rt = process_memory_usage() + bytes >= MemInfo::mem_limit() ||
                  sys_mem_available() - bytes < MemInfo::sys_mem_available_low_water_mark();
        if (rt) {
            doris::ProcessProfile::instance()->memory_profile()->print_log_process_usage();
        }
        return rt;
    }

    static std::string process_mem_log_str() {
        return fmt::format(
                "sys physical memory {}. {}, limit {}, soft limit {}. {}, low water mark {}, "
                "warning water mark {}",
                PrettyPrinter::print(MemInfo::physical_mem(), TUnit::BYTES),
                process_memory_used_details_str(), MemInfo::mem_limit_str(),
                MemInfo::soft_mem_limit_str(), sys_mem_available_details_str(),
                PrettyPrinter::print(MemInfo::sys_mem_available_low_water_mark(), TUnit::BYTES),
                PrettyPrinter::print(MemInfo::sys_mem_available_warning_water_mark(),
                                     TUnit::BYTES));
    }

    static void refresh_memory_bvar();

    // It is only used after the memory limit is exceeded. When multiple threads are waiting for the available memory of the process,
    // avoid multiple threads starting at the same time and causing OOM.
    static std::atomic<int64_t> refresh_interval_memory_growth;

    static std::mutex cache_adjust_capacity_lock;
    static std::condition_variable cache_adjust_capacity_cv;
    static std::atomic<bool> cache_adjust_capacity_notify;
    // This capacity is set by memory maintenance thread `refresh_cache_capacity`, it is running periodicity,
    // modified when process memory changes.
    static std::atomic<double> last_periodic_refreshed_cache_capacity_adjust_weighted;
    // This capacity is set by memory maintenance thread `handle_paused_queries`, in workload group mgr,
    // modified when a query enters paused state due to process memory exceed.
    static std::atomic<double> last_memory_exceeded_cache_capacity_adjust_weighted;
    // The value that take affect
    static std::atomic<double> last_affected_cache_capacity_adjust_weighted;
    static std::atomic<bool> any_workload_group_exceed_limit;

    static void notify_cache_adjust_capacity() {
        cache_adjust_capacity_notify.store(true, std::memory_order_relaxed);
        cache_adjust_capacity_cv.notify_all();
    }

    static std::mutex memtable_memory_refresh_lock;
    static std::condition_variable memtable_memory_refresh_cv;
    static std::atomic<bool> memtable_memory_refresh_notify;
    static void notify_memtable_memory_refresh() {
        memtable_memory_refresh_notify.store(true, std::memory_order_relaxed);
        memtable_memory_refresh_cv.notify_all();
    }

private:
    static std::atomic<int64_t> _process_reserved_memory;
};

#include "common/compile_check_end.h"
} // namespace doris
