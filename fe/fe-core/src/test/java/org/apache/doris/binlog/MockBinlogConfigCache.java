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

package org.apache.doris.binlog;

import org.apache.doris.catalog.BinlogConfig;

import java.util.Map;

final class MockBinlogConfigCache extends BinlogConfigCache {
    private Map<String, BinlogConfig> mockedConfigs;

    public MockBinlogConfigCache(Map<String, BinlogConfig> mockedConfigs) {
        super();
        this.mockedConfigs = mockedConfigs;
    }

    public void addDbBinlogConfig(long dbId, boolean enableBinlog, long expiredTime) {
        BinlogConfig config = BinlogTestUtils.newTestBinlogConfig(enableBinlog, expiredTime);
        mockedConfigs.put(String.valueOf(dbId), config);
    }

    public void addTableBinlogConfig(long dbId, long tableId, boolean enableBinlog, long expiredTime) {
        BinlogConfig config = BinlogTestUtils.newTestBinlogConfig(enableBinlog, expiredTime);
        mockedConfigs.put(String.format("%d_%d", dbId, tableId), config);
    }

    @Override
    public BinlogConfig getTableBinlogConfig(long dbId, long tableId) {
        return mockedConfigs.get(String.format("%d_%d", dbId, tableId));
    }

    @Override
    public BinlogConfig getDBBinlogConfig(long dbId) {
        return mockedConfigs.get(String.valueOf(dbId));
    }

    @Override
    public boolean isEnableTable(long dbId, long tableId) {
        return mockedConfigs.containsKey(String.format("%d_%d", dbId, tableId));
    }

    @Override
    public boolean isEnableDB(long dbId) {
        BinlogConfig config = mockedConfigs.get(String.valueOf(dbId));
        if (config != null) {
            return config.isEnable();
        }
        return false;
    }
}
