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

/* ******* Do not commit this file unless you know what you are doing ******* */

// **Note**: default db will be create if not exist
defaultDb = "regression_test"

// add useLocalSessionState so that the jdbc will not send
// init cmd like: select @@session.tx_read_only
// at each time we connect.
// add allowLoadLocalInfile so that the jdbc can execute mysql load data from client.
jdbcUrl = "jdbc:mysql://127.0.0.1:9030/?useLocalSessionState=true&allowLoadLocalInfile=true&zeroDateTimeBehavior=round"
targetJdbcUrl = "jdbc:mysql://127.0.0.1:9030/?useLocalSessionState=true&allowLoadLocalInfile=true&zeroDateTimeBehavior=round"
jdbcUser = "root"
jdbcPassword = ""

feSourceThriftAddress = "127.0.0.1:9020"
feTargetThriftAddress = "127.0.0.1:9020"
syncerAddress = "127.0.0.1:9190"
feSyncerUser = "root"
feSyncerPassword = ""

feHttpAddress = "127.0.0.1:8030"
feHttpUser = "root"
feHttpPassword = ""

// set DORIS_HOME by system properties
// e.g. java -DDORIS_HOME=./
suitePath = "${DORIS_HOME}/regression-test/suites"
dataPath = "${DORIS_HOME}/regression-test/data"
pluginPath = "${DORIS_HOME}/regression-test/plugins"
realDataPath = "${DORIS_HOME}/regression-test/realdata"
sslCertificatePath = "${DORIS_HOME}/regression-test/ssl_default_certificate"

// suite configs
suites = {

    //// equals to:
    ////    suites.test_suite_1.key1 = "val1"
    ////    suites.test_suite_1.key2 = "val2"
    ////
    //test_suite_1 {
    //    key1 = "val1"
    //    key2 = "val2"
    //}

    //test_suite_2 {
    //    key3 = "val1"
    //    key4 = "val2"
    //}
}

// docker image
image = ""
dockerCoverageOutputDir = "" // if not empty, will save docker coverage output files
dockerEndDeleteFiles = false
dorisComposePath = "${DORIS_HOME}/docker/runtime/doris-compose/doris-compose.py"
// do run docker test because pipeline not support build image now
excludeDockerTest = true

// will test <group>/<suite>.groovy
// empty group will test all group
testGroups = ""
// empty suite will test all suite
testSuites = ""
// empty directories will test all directories
testDirectories = ""

// this groups will not be executed
excludeGroups = ""
// this suites will not be executed
excludeSuites = "test_broker_load"
// this directories will not be executed
excludeDirectories = "000_the_start_sentinel_do_not_touch," + // keep this line as the first line
    "segcompaction_p2," +
    "workload_manager_p1," +
    "cloud_p0/cache," +
    "zzz_the_end_sentinel_do_not_touch," +
    "dialect_compatible"// keep this line as the last line

customConf1 = "test_custom_conf_value"

// for test csv with header
enableHdfs=false // set to true if hdfs is ready
hdfsFs = "hdfs://127.0.0.1:9000"
hdfsUser = "doris-test"
hdfsPasswd = ""
brokerName = "broker_name"

// broker load test config
enableBrokerLoad=true

// for s3 releated cases, "aliyun" or "aliyun-internal" or "tencent" or "huawei" or "azure" or "gcp"
// if s3Source is set,  s3Endpoint s3BucketName s3Region s3Provider will be filled with default value if not set
s3Source="aliyun"
// s3Endpoint = ""
// s3BucketName = ""
// s3Region = ""
// s3Provider = ""
ak="***********"
sk="***********"

// jdbc connector test config
// To enable jdbc test, you need first start mysql/pg container.
// See `docker/thirdparties/run-thirdparties-docker.sh`
enableJdbcTest=false
mysql_57_port=3316
pg_14_port=5442
oracle_11_port=1521
sqlserver_2022_port=1433
clickhouse_22_port=8123
doris_port=9030
mariadb_10_port=3326
db2_11_port=50000
oceanbase_port=2881

// hive catalog test config
// To enable hive/paimon test, you need first start hive container.
// See `docker/thirdparties/run-thirdparties-docker.sh`
enableHiveTest=false
enablePaimonTest=false

// port of hive2 docker
hive2HmsPort=9083
hive2HdfsPort=8020
hive2ServerPort=10000
hive2PgPort=5432

// port of hive3 docker
hive3HmsPort=9383
hive3HdfsPort=8320
hive3ServerPort=13000
hive3PgPort=5732

// kafka test config
// to enable kafka test, you need firstly to start kafka container
// See `docker/thirdparties/run-thirdparties-docker.sh`
enableKafkaTest=false
kafka_port=19193

// elasticsearch catalog test config
// See `docker/thirdparties/run-thirdparties-docker.sh`
enableEsTest=false
es_5_port=59200
es_6_port=19200
es_7_port=29200
es_8_port=39200


//hive  catalog test config for bigdata
enableExternalHiveTest = false
extHiveHmsHost = "***.**.**.**"
extHiveHmsPort = 7004
extHdfsPort = 4007
extHiveServerPort= 7001
extHiveHmsUser = "****"
extHiveHmsPassword= "***********"
dfsNameservices=""
dfsNameservicesPort=8020
dfsHaNamenodesHdfsCluster=""
dfsNamenodeRpcAddress1=""
dfsNamenodeRpcAddress2=""
dfsNamenodeRpcAddress3=""
hadoopSecurityAuthentication = ""
hadoopKerberosKeytabPath = ""
hadoopKerberosPrincipal = ""
hadoopSecurityAutoToLocal = ""


//paimon catalog test config for bigdata
enableExternalPaimonTest = false

//mysql jdbc connector test config for bigdata
enableExternalMysqlTest = false
extMysqlHost = "***.**.**.**"
extMysqlPort = 3306
extMysqlUser = "****"
extMysqlPassword = "***********"

//postgresql jdbc connector test config for bigdata
enableExternalPgTest = false
extPgHost = "***.**.**.*"
extPgPort = 5432
extPgUser = "****"
extPgPassword = "***********"

// minio external test config
enableExternalMinioTest = false
extMinioHost = "***.**.**.**"
extMinioPort = 9000
extMinioDomain = "myminio.com"
extMinioAk = "minioadmin"
extMinioSk = "minioadmin"
extMinioRegion = "us-east-1"
extMinioBucket = "test-bucket"

enableExternalHudiTest = false
hudiEmrCatalog = "***********"

enableObjStorageTest=false
enableMaxComputeTest=false
aliYunAk="***********"
dlfUid="***********"
aliYunSk="***********"
hwYunAk="***********"
hwYunSk="***********"
txYunAk="***********"
txYunSk="***********"

//arrow flight sql test config
extArrowFlightSqlHost = "127.0.0.1"
extArrowFlightSqlPort = 8081
extArrowFlightSqlUser = "root"
extArrowFlightSqlPassword= ""

// iceberg rest catalog config
iceberg_rest_uri_port=18181
iceberg_minio_port=19001

// If the failure suite num exceeds this config
// all following suite will be skipped to fast quit the run.
// <=0 means no limit.
max_failure_num=0

// used for exporting test
s3ExportBucketName = ""

externalEnvIp="127.0.0.1"

// trino-connector catalog test config
enableTrinoConnectorTest = false

enableKerberosTest=false
kerberosHmsPort=9883
kerberosHdfsPort=8820
enableRefactorParamsHdfsTest=true


// LakeSoul catalog test config
enableLakesoulTest = false
lakesoulPGUser="*******"
lakesoulPGPwd="*******"
lakesoulPGUrl="*******"
lakesoulMinioAK="*******"
lakesoulMinioSK="*******"
lakesoulMinioEndpoint="*******"

// cloud
metaServiceToken = "greedisgood9999"
instanceId = "default_instance_id"
multiClusterInstance = "default_instance_id"

storageProvider = "oss"
cbsS3Ak = "*******"
cbsS3Sk = "*******"
cbsS3Endpoint = "oss-cn-beijing.aliyuncs.com"
cbsS3Bucket = "test-bucket"
cbsS3Prefix = "test-cluster-prefix"

// External Env
enableExternalIcebergTest = false
// The properties string of iceberg catalog
icebergS3TablesCatalog = ""

enableExternalHudiTest = false
// The properties string of hudi catalog
hudiEmrCatalog = ""
