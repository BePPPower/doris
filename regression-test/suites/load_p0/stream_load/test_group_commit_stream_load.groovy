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

suite("test_group_commit_stream_load") {
    def tableName = "test_group_commit_stream_load"

    def getRowCount = { expectedRowCount ->
        def retry = 0
        while (retry < 30) {
            sleep(2000)
            def rowCount = sql "select count(*) from ${tableName}"
            logger.info("rowCount: " + rowCount + ", retry: " + retry)
            if (rowCount[0][0] >= expectedRowCount) {
                break
            }
            retry++
        }
    }

    def getAlterTableState = {
        waitForSchemaChangeDone {
            sql """ SHOW ALTER TABLE COLUMN WHERE tablename='${tableName}' ORDER BY createtime DESC LIMIT 1 """
            time 600
        }
        return true
    }

    def checkStreamLoadResult = { exception, result, total_rows, loaded_rows, filtered_rows, unselected_rows ->
        if (exception != null) {
            throw exception
        }
        log.info("Stream load result: ${result}".toString())
        def json = parseJson(result)
        assertEquals("success", json.Status.toLowerCase())
        assertTrue(json.GroupCommit)
        assertTrue(json.Label.startsWith("group_commit_"))
        assertEquals(total_rows, json.NumberTotalRows)
        assertEquals(loaded_rows, json.NumberLoadedRows)
        assertEquals(filtered_rows, json.NumberFilteredRows)
        assertEquals(unselected_rows, json.NumberUnselectedRows)
        if (filtered_rows > 0) {
            assertFalse(json.ErrorURL.isEmpty())
        } else {
            assertTrue(json.ErrorURL == null || json.ErrorURL.isEmpty())
        }
    }

    try {
        // create table
        sql """ drop table if exists ${tableName}; """

        sql """
        CREATE TABLE `${tableName}` (
            `id` int(11) NOT NULL,
            `name` varchar(1100) NULL,
            `score` int(11) NULL default "-1"
        ) ENGINE=OLAP
        DUPLICATE KEY(`id`, `name`)
        PARTITION BY RANGE (id) (
            PARTITION plessThan1 VALUES LESS THAN ("0"),
            PARTITION plessThan2 VALUES LESS THAN ("100")
        )
        DISTRIBUTED BY HASH(`id`) BUCKETS 1
        PROPERTIES (
            "replication_num" = "1",
            "group_commit_interval_ms" = "200"
        );
        """

        // stream load with compress file
        String[] compressionTypes = new String[]{"gz", "bz2", /*"lzo",*/ "lz4"} //, "deflate"}
        for (final def compressionType in compressionTypes) {
            def fileName = "test_compress.csv." + compressionType
            streamLoad {
                table "${tableName}"

                set 'column_separator', ','
                set 'group_commit', 'async_mode'
                set 'compress_type', "${compressionType}"
                // set 'columns', 'id, name, score'
                file "${fileName}"
                unset 'label'

                time 10000 // limit inflight 10s

                check { result, exception, startTime, endTime ->
                    checkStreamLoadResult(exception, result, 4, 4, 0, 0)
                }
            }
        }

        // stream load with 2 columns
        streamLoad {
            table "${tableName}"

            set 'column_separator', ','
            set 'group_commit', 'async_mode'
            set 'columns', 'id, name'
            file "test_stream_load1.csv"
            unset 'label'

            time 10000 // limit inflight 10s

            check { result, exception, startTime, endTime ->
                checkStreamLoadResult(exception, result, 2, 2, 0, 0)
            }
        }

        // stream load with different column order
        streamLoad {
            table "${tableName}"

            set 'column_separator', '|'
            set 'group_commit', 'async_mode'
            set 'columns', 'score, id, name'
            file "test_stream_load2.csv"
            unset 'label'

            time 10000 // limit inflight 10s

            check { result, exception, startTime, endTime ->
                checkStreamLoadResult(exception, result, 2, 2, 0, 0)
            }
        }

        // stream load with where condition
        streamLoad {
            table "${tableName}"

            set 'column_separator', ','
            set 'group_commit', 'async_mode'
            set 'columns', 'id, name'
            file "test_stream_load1.csv"
            set 'where', 'id > 5'
            unset 'label'

            time 10000 // limit inflight 10s

            check { result, exception, startTime, endTime ->
                checkStreamLoadResult(exception, result, 2, 1, 0, 1)
            }
        }

        // stream load with mapping
        streamLoad {
            table "${tableName}"

            set 'column_separator', ','
            set 'group_commit', 'async_mode'
            set 'columns', 'id, name, score = id * 10'
            file "test_stream_load1.csv"
            unset 'label'

            time 10000 // limit inflight 10s

            check { result, exception, startTime, endTime ->
                checkStreamLoadResult(exception, result, 2, 2, 0, 0)
            }
        }

        // stream load with filtered rows
        streamLoad {
            table "${tableName}"

            set 'column_separator', ','
            set 'group_commit', 'async_mode'
            file "test_stream_load3.csv"
            set 'where', "name = 'a'"
            set 'max_filter_ratio', '0.7'
            unset 'label'

            time 10000 // limit inflight 10s

            check { result, exception, startTime, endTime ->
                checkStreamLoadResult(exception, result, 6, 2, 3, 1)
            }
        }

        // stream load with label
        streamLoad {
            table "${tableName}"

            // set 'label', 'test_stream_load'
            set 'column_separator', '|'
            set 'group_commit', 'async_mode'
            // set 'label', 'l_' + System.currentTimeMillis()
            file "test_stream_load2.csv"

            time 10000 // limit inflight 10s
            check { result, exception, startTime, endTime ->
                if (exception != null) {
                    throw exception
                }
                log.info("Stream load result: ${result}".toString())
                def json = parseJson(result)
                assertEquals("fail", json.Status.toLowerCase())
            }
        }

        getRowCount(21)
        qt_sql " SELECT * FROM ${tableName} order by id, name, score asc; "
    } finally {
        // try_sql("DROP TABLE ${tableName}")
    }

    // stream load with large data and schema change
    tableName = "test_stream_load_lineorder"
    try {
        sql """ DROP TABLE IF EXISTS `${tableName}` """
        sql """
            CREATE TABLE IF NOT EXISTS `${tableName}` (
            `lo_orderkey` bigint(20) NOT NULL COMMENT "",
            `lo_linenumber` bigint(20) NOT NULL COMMENT "",
            `lo_custkey` int(11) NOT NULL COMMENT "",
            `lo_partkey` int(11) NOT NULL COMMENT "",
            `lo_suppkey` int(11) NOT NULL COMMENT "",
            `lo_orderdate` int(11) NOT NULL COMMENT "",
            `lo_orderpriority` varchar(16) NOT NULL COMMENT "",
            `lo_shippriority` int(11) NOT NULL COMMENT "",
            `lo_quantity` bigint(20) NOT NULL COMMENT "",
            `lo_extendedprice` bigint(20) NOT NULL COMMENT "",
            `lo_ordtotalprice` bigint(20) NOT NULL COMMENT "",
            `lo_discount` bigint(20) NOT NULL COMMENT "",
            `lo_revenue` bigint(20) NOT NULL COMMENT "",
            `lo_supplycost` bigint(20) NOT NULL COMMENT "",
            `lo_tax` bigint(20) NOT NULL COMMENT "",
            `lo_commitdate` bigint(20) NOT NULL COMMENT "",
            `lo_shipmode` varchar(11) NOT NULL COMMENT ""
            )
            PARTITION BY RANGE(`lo_orderdate`)
            (PARTITION p1992 VALUES [("-2147483648"), ("19930101")),
            PARTITION p1993 VALUES [("19930101"), ("19940101")),
            PARTITION p1994 VALUES [("19940101"), ("19950101")),
            PARTITION p1995 VALUES [("19950101"), ("19960101")),
            PARTITION p1996 VALUES [("19960101"), ("19970101")),
            PARTITION p1997 VALUES [("19970101"), ("19980101")),
            PARTITION p1998 VALUES [("19980101"), ("19990101")))
            DISTRIBUTED BY HASH(`lo_orderkey`) BUCKETS 4
            PROPERTIES (
                "replication_num" = "1",
                "group_commit_interval_ms" = "200"
            );
        """
        // load data
        def columns = """lo_orderkey,lo_linenumber,lo_custkey,lo_partkey,lo_suppkey,lo_orderdate,lo_orderpriority, 
            lo_shippriority,lo_quantity,lo_extendedprice,lo_ordtotalprice,lo_discount, 
            lo_revenue,lo_supplycost,lo_tax,lo_commitdate,lo_shipmode"""

        /*new Thread(() -> {
            Thread.sleep(3000)
            // do light weight schema change
            sql """ alter table ${tableName} ADD column sc_tmp varchar(100) after lo_revenue; """

            assertTrue(getAlterTableState())

            // do hard weight schema change
            def new_columns = """lo_orderkey,lo_linenumber,lo_custkey,lo_partkey,lo_suppkey,lo_orderdate,lo_orderpriority, 
            lo_shippriority,lo_quantity,lo_extendedprice,lo_ordtotalprice,lo_discount, 
            lo_revenue,lo_supplycost,lo_tax,lo_shipmode,lo_commitdate"""
            sql """ alter table ${tableName} order by (${new_columns}); """
        }).start();*/

        for (int i = 0; i < 2; i++) {

            streamLoad {
                table tableName

                set 'column_separator', '|'
                set 'compress_type', 'GZ'
                set 'columns', columns + ",lo_dummy"
                set 'group_commit', 'async_mode'
                unset 'label'

                file """${getS3Url()}/regression/ssb/sf0.1/lineorder.tbl.gz"""

                time 10000 // limit inflight 10s

                // stream load action will check result, include Success status, and NumberTotalRows == NumberLoadedRows

                // if declared a check callback, the default check condition will ignore.
                // So you must check all condition
                check { result, exception, startTime, endTime ->
                    checkStreamLoadResult(exception, result, 600572, 600572, 0, 0)
                }
            }
        }

        getRowCount(600572 * 2)
        qt_sql """ select count(*) from ${tableName} """

        // assertTrue(getAlterTableState())
    } finally {
        // try_sql("DROP TABLE ${tableName}")
    }

    // stream load with unique_key_update_mode
    tableName = "test_group_commit_stream_load_update"
    sql """ DROP TABLE IF EXISTS ${tableName} """
    sql """ CREATE TABLE ${tableName} (
            `k` int(11) NULL, 
            `v1` BIGINT NULL,
            `v2` BIGINT NULL DEFAULT "9876",
            `v3` BIGINT NOT NULL,
            `v4` BIGINT NOT NULL DEFAULT "1234",
            `v5` BIGINT NULL
            ) UNIQUE KEY(`k`) DISTRIBUTED BY HASH(`k`) BUCKETS 1
            PROPERTIES(
            "replication_num" = "1",
            "enable_unique_key_merge_on_write" = "true",
            "light_schema_change" = "true",
            "enable_unique_key_skip_bitmap_column" = "true"); """

    def show_res = sql "show create table ${tableName}"
    assertTrue(show_res.toString().contains('"enable_unique_key_skip_bitmap_column" = "true"'))
    sql """insert into ${tableName} select number, number, number, number, number, number from numbers("number" = "6"); """
    qt_sql "select k,v1,v2,v3,v4,v5,BITMAP_TO_STRING(__DORIS_SKIP_BITMAP_COL__) from ${tableName} order by k;"

    streamLoad {
        table "${tableName}"
        set 'group_commit', 'async_mode'
        set 'format', 'json'
        set 'read_json_by_line', 'true'
        set 'strict_mode', 'false'
        set 'unique_key_update_mode', 'update_FLEXIBLE_COLUMNS'
        file "test1.json"
        time 20000
        unset 'label'
    }
    qt_read_json_by_line "select k,v1,v2,v3,v4,v5,BITMAP_TO_STRING(__DORIS_SKIP_BITMAP_COL__) from ${tableName} order by k;"

}
