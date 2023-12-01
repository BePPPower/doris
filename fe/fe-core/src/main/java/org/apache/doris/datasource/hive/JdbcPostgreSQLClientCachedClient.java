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

package org.apache.doris.datasource.hive;

import org.apache.doris.catalog.JdbcTable;
import org.apache.doris.datasource.jdbc.client.JdbcClientConfig;
import org.apache.doris.thrift.TOdbcTableType;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.apache.hadoop.hive.common.ValidTxnList;
import org.apache.hadoop.hive.metastore.IMetaStoreClient.NotificationFilter;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsObj;
import org.apache.hadoop.hive.metastore.api.CurrentNotificationEventId;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.LockRequest;
import org.apache.hadoop.hive.metastore.api.LockResponse;
import org.apache.hadoop.hive.metastore.api.NotificationEventResponse;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.PrincipalType;
import org.apache.hadoop.hive.metastore.api.SerDeInfo;
import org.apache.hadoop.hive.metastore.api.SerdeType;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.api.TableValidWriteIds;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class JdbcPostgreSQLClientCachedClient extends JdbcClientCachedClient {
    private static final Logger LOG = LogManager.getLogger(JdbcPostgreSQLClientCachedClient.class);

    public JdbcPostgreSQLClientCachedClient(PooledHiveMetaStoreClient pooledHiveMetaStoreClient,
            JdbcClientConfig jdbcClientConfig) {
        super(pooledHiveMetaStoreClient, jdbcClientConfig);
    }

    @Override
    public List<String> getAllDatabases() throws Exception {
        String nameFiled = JdbcTable.databaseProperName(TOdbcTableType.POSTGRESQL, "NAME");
        String tableName = JdbcTable.databaseProperName(TOdbcTableType.POSTGRESQL, "DBS");
        String sql = String.format("SELECT %s FROM %s;", nameFiled, tableName);
        LOG.debug("getAllDatabases exec sql: {}", sql);
        try (Connection conn = getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            Builder<String> builder = ImmutableList.builder();
            while (rs.next()) {
                String hiveDatabaseName = rs.getString("NAME");
                builder.add(hiveDatabaseName);
            }
            return builder.build();
        }
    }

    @Override
    public List<String> getAllTables(String dbName) throws Exception {
        String sql = "SELECT \"TBL_NAME\" FROM \"TBLS\" join \"DBS\" on \"TBLS\".\"DB_ID\" = \"DBS\".\"DB_ID\""
                + " WHERE \"DBS\".\"NAME\" = '" + dbName + "';";
        LOG.debug("getAllTables exec sql: {}", sql);

        try (Connection conn = getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            Builder<String> builder = ImmutableList.builder();
            while (rs.next()) {
                String hiveDatabaseName = rs.getString("TBL_NAME");
                builder.add(hiveDatabaseName);
            }
            return builder.build();
        }
    }

    @Override
    public boolean tableExists(String dbName, String tblName) throws Exception {
        List<String> allTables = getAllTables(dbName);
        return allTables.contains(tblName);
    }

    @Override
    public List<String> listPartitionNames(String dbName, String tblName, short maxListPartitionNum) throws Exception {
        String sql = String.format("SELECT \"PART_NAME\" from \"PARTITIONS\" WHERE \"TBL_ID\" = ("
                + "SELECT \"TBL_ID\" FROM \"TBLS\" join \"DBS\" on \"TBLS\".\"DB_ID\" = \"DBS\".\"DB_ID\""
                + " WHERE \"DBS\".\"NAME\" = '%s' AND \"TBLS\".\"TBL_NAME\"='%s');", dbName, tblName);
        LOG.debug("listPartitionNames exec sql: {}", sql);

        try (Connection conn = getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            Builder<String> builder = ImmutableList.builder();
            while (rs.next()) {
                String hivePartitionName = rs.getString("PART_NAME");
                builder.add(hivePartitionName);
            }
            return builder.build();
        }
    }

    // not used
    @Override
    public Partition getPartition(String dbName, String tblName, List<String> partitionValues) throws Exception {
        LOG.debug("getPartition partitionValues: {}", partitionValues);
        String partitionName = Joiner.on("/").join(partitionValues);
        ImmutableList<String> partitionNames = ImmutableList.of(partitionName);
        LOG.debug("getPartition partitionNames: {}", partitionNames);
        List<Partition> partitions = getPartitionsByNames(dbName, tblName, partitionNames);
        if (!partitions.isEmpty()) {
            return partitions.get(0);
        }
        throw new Exception("Can not get partition of partitionName = " + partitionName
                + ", from " + dbName + "." + tblName);
    }

    @Override
    public List<Partition> getPartitionsByNames(String dbName, String tblName, List<String> partitionNames)
            throws Exception {
        List<String> partitionNamesWithQuote = partitionNames.stream().map(partitionName -> "'" + partitionName + "'")
                .collect(Collectors.toList());
        String partitionNamesString = Joiner.on(", ").join(partitionNamesWithQuote);
        String sql = String.format("SELECT \"PART_ID\", \"PARTITIONS\".\"CREATE_TIME\","
                        + " \"PARTITIONS\".\"LAST_ACCESS_TIME\","
                        + " \"PART_NAME\", \"PARTITIONS\".\"SD_ID\" FROM \"PARTITIONS\""
                        + " join \"TBLS\" on \"TBLS\".\"TBL_ID\" = \"PARTITIONS\".\"TBL_ID\""
                        + " join \"DBS\" on \"TBLS\".\"DB_ID\" = \"DBS\".\"DB_ID\""
                        + " WHERE \"DBS\".\"NAME\" = '%s' AND \"TBLS\".\"TBL_NAME\"='%s'"
                        + " AND \"PART_NAME\" in (%s);",
                dbName, tblName, partitionNamesString);
        LOG.debug("getPartitionsByNames exec sql: {}", sql);

        try (Connection conn = getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            Builder<Partition> builder = ImmutableList.builder();
            while (rs.next()) {
                Partition partition = new Partition();
                partition.setDbName(dbName);
                partition.setTableName(tblName);
                partition.setCreateTime(rs.getInt("CREATE_TIME"));
                partition.setLastAccessTime(rs.getInt("LAST_ACCESS_TIME"));

                // set partition values
                partition.setValues(getPartitionValues(rs.getInt("PART_ID")));

                // set SD
                StorageDescriptor storageDescriptor = getStorageDescriptor(rs.getInt("SD_ID"));
                partition.setSd(storageDescriptor);

                builder.add(partition);
            }
            return builder.build();
        }
    }

    private List<String> getPartitionValues(int partitionId) throws Exception {
        String sql = String.format("SELECT \"PART_KEY_VAL\" FROM \"PARTITION_KEY_VALS\""
                + " WHERE \"PART_ID\" = " + partitionId);
        LOG.debug("getPartitionValues exec sql: {}", sql);
        try (Connection conn = getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            Builder<String> builder = ImmutableList.builder();
            while (rs.next()) {
                builder.add(rs.getString("PART_KEY_VAL"));
            }
            return builder.build();
        }
    }

    @Override
    public Table getTable(String dbName, String tblName) throws Exception {
        String sql = "SELECT \"TBL_ID\", \"TBL_NAME\", \"DBS\".\"NAME\", \"OWNER\", \"CREATE_TIME\","
                + " \"LAST_ACCESS_TIME\", \"LAST_ACCESS_TIME\", \"RETENTION\","
                + " \"IS_REWRITE_ENABLED\", \"VIEW_EXPANDED_TEXT\", \"VIEW_ORIGINAL_TEXT\", \"DBS\".\"OWNER_TYPE\""
                + " FROM \"TBLS\" join \"DBS\" on \"TBLS\".\"DB_ID\" = \"DBS\".\"DB_ID\" "
                + " WHERE \"DBS\".\"NAME\" = '" + dbName + "' AND \"TBLS\".\"TBL_NAME\"='" + tblName + "';";
        LOG.debug("getTable exec sql: {}", sql);

        try (Connection conn = getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            Table hiveTable = new Table();
            if (rs.next()) {
                hiveTable.setTableName(rs.getString("TBL_NAME"));
                hiveTable.setDbName(rs.getString("NAME"));
                hiveTable.setOwner(rs.getString("OWNER"));
                hiveTable.setCreateTime(rs.getInt("CREATE_TIME"));
                hiveTable.setLastAccessTime(rs.getInt("LAST_ACCESS_TIME"));
                hiveTable.setRetention(rs.getInt("RETENTION"));
                hiveTable.setOwnerType(getOwnerType(rs.getString("OWNER_TYPE")));
                hiveTable.setRewriteEnabled(rs.getBoolean("IS_REWRITE_ENABLED"));
                hiveTable.setViewExpandedText(rs.getString("VIEW_EXPANDED_TEXT"));
                hiveTable.setViewOriginalText(rs.getString("VIEW_ORIGINAL_TEXT"));

                hiveTable.setSd(getStorageDescriptor(dbName, tblName));
                hiveTable.setParameters(getTableParameters(rs.getInt("TBL_ID")));
                hiveTable.setPartitionKeys(getTablePartitionKeys(rs.getInt("TBL_ID")));
                return hiveTable;
            }
            throw new Exception("Can not get Table from PG databases. dbName = " + dbName + ", tblName = " + tblName);
        }
    }

    private StorageDescriptor getStorageDescriptor(String dbName, String tblName) throws Exception {
        String sql = "SELECT * from \"SDS\" WHERE \"SD_ID\" = ("
                + "SELECT \"SD_ID\" FROM \"TBLS\" join \"DBS\" on \"TBLS\".\"DB_ID\" = \"DBS\".\"DB_ID\" "
                + "WHERE \"DBS\".\"NAME\" = '" + dbName + "' AND \"TBLS\".\"TBL_NAME\"='" + tblName + "'"
                + ");";
        LOG.debug("getStorageDescriptorByDbAndTable exec sql: {}", sql);

        try (Connection conn = getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                StorageDescriptor sd = new StorageDescriptor();
                sd.setInputFormat(rs.getString("INPUT_FORMAT"));
                // for gauss
                sd.setCompressed(Boolean.valueOf(rs.getInt("IS_COMPRESSED") != 0));
                sd.setLocation(rs.getString("LOCATION"));
                sd.setNumBuckets(rs.getInt("NUM_BUCKETS"));
                sd.setOutputFormat(rs.getString("OUTPUT_FORMAT"));
                sd.setStoredAsSubDirectories(rs.getBoolean("IS_STOREDASSUBDIRECTORIES"));
                sd.setSerdeInfo(getSerdeInfo(rs.getInt("SERDE_ID")));
                return sd;
            }
            throw new Exception("Can not get StorageDescriptor from PG databases of " + dbName + "." + tblName);
        }
    }

    private StorageDescriptor getStorageDescriptor(int sdId) throws Exception {
        String sql = "SELECT * from \"SDS\" WHERE \"SD_ID\" = " + sdId + ";";
        LOG.debug("getStorageDescriptorBySDID exec sql: {}", sql);

        try (Connection conn = getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                StorageDescriptor sd = new StorageDescriptor();
                sd.setInputFormat(rs.getString("INPUT_FORMAT"));
                // for gauss
                sd.setCompressed(Boolean.valueOf(rs.getInt("IS_COMPRESSED") != 0));
                sd.setLocation(rs.getString("LOCATION"));
                sd.setNumBuckets(rs.getInt("NUM_BUCKETS"));
                sd.setOutputFormat(rs.getString("OUTPUT_FORMAT"));
                sd.setStoredAsSubDirectories(rs.getBoolean("IS_STOREDASSUBDIRECTORIES"));
                sd.setSerdeInfo(getSerdeInfo(rs.getInt("SERDE_ID")));
                return sd;
            }
            throw new Exception("Can not get StorageDescriptor from PG databases, SD_ID = " + sdId + ".");
        }
    }

    private SerDeInfo getSerdeInfo(int serdeId) throws Exception {
        String sql = "SELECT * FROM \"SERDES\" WHERE \"SERDE_ID\" = " + serdeId;
        LOG.debug("getSerdeInfo exec sql: {}", sql);

        SerDeInfo serDeInfo = new SerDeInfo();
        serDeInfo.setParameters(getSerdeInfoParameters(serdeId));

        try (Connection conn = getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                serDeInfo.setName(rs.getString("NAME"));
                serDeInfo.setSerializationLib(rs.getString("SLIB"));

                // for gauss
                serDeInfo.setDescription(rs.getString("DESCRIPTION"));
                serDeInfo.setSerializerClass(rs.getString("SERIALIZER_CLASS"));
                serDeInfo.setDeserializerClass(rs.getString("DESERIALIZER_CLASS"));
                serDeInfo.setSerdeType(getSerdeType(rs.getString("SERDE_TYPE")));
                return serDeInfo;
            }
            throw new Exception("Can not get SerDeInfo from PG databases, serdeId = " + serdeId + ".");
        }


    }

    private Map<String, String> getSerdeInfoParameters(int serdeId) throws Exception {
        String sql = "SELECT \"PARAM_KEY\", \"PARAM_VALUE\" from \"SERDE_PARAMS\" WHERE \"SERDE_ID\" = " + serdeId;
        LOG.debug("getSerdeInfoParameters exec sql: {}", sql);

        try (Connection conn = getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
            while (rs.next()) {
                builder.put(rs.getString("PARAM_KEY"), rs.getString("PARAM_VALUE"));
            }
            return builder.build();
        }
    }

    private List<FieldSchema> getTablePartitionKeys(int tableId) throws Exception {
        String sql = "SELECT \"PKEY_NAME\", \"PKEY_TYPE\", \"PKEY_COMMENT\" from \"PARTITION_KEYS\""
                + " WHERE \"TBL_ID\"= " + tableId;
        LOG.debug("getTablePartitionKeys exec sql: {}", sql);

        try (Connection conn = getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            Builder<FieldSchema> builder = ImmutableList.builder();
            while (rs.next()) {
                FieldSchema fieldSchema = new FieldSchema(rs.getString("PKEY_NAME"),
                        rs.getString("PKEY_TYPE"), rs.getString("PKEY_COMMENT"));
                builder.add(fieldSchema);
            }

            List<FieldSchema> fieldSchemas = builder.build();
            // must reverse fields
            List<FieldSchema> reversedFieldSchemas = Lists.newArrayList(fieldSchemas);
            Collections.reverse(reversedFieldSchemas);
            return reversedFieldSchemas;
        }
    }

    private Map<String, String> getTableParameters(int tableId) throws Exception {
        String sql = "SELECT \"PARAM_KEY\", \"PARAM_VALUE\" from \"TABLE_PARAMS\" WHERE \"TBL_ID\" = " + tableId;
        LOG.debug("getParameters exec sql: {}", sql);

        try (Connection conn = getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
            while (rs.next()) {
                builder.put(rs.getString("PARAM_KEY"), rs.getString("PARAM_VALUE"));
            }
            return builder.build();
        }
    }

    private PrincipalType getOwnerType(String ownerTypeString) throws Exception {
        switch (ownerTypeString) {
            case "USER":
                return PrincipalType.findByValue(1);
            case "ROLE":
                return PrincipalType.findByValue(2);
            case "GROUP":
                return PrincipalType.findByValue(3);
            default:
                throw new Exception("Unknown owner type of this table");
        }
    }

    private SerdeType getSerdeType(String serdeTypeString) throws Exception {
        switch (serdeTypeString) {
            case "HIVE":
                return SerdeType.findByValue(1);
            case "SCHEMA_REGISTRY":
                return SerdeType.findByValue(2);
            default:
                throw new Exception("Unknown serde type of this table");
        }
    }

    @Override
    public List<FieldSchema> getSchema(String dbName, String tblName) throws Exception {
        String sql = "SELECT \"COLUMN_NAME\", \"TYPE_NAME\", \"COMMENT\", \"TBLS\".\"TBL_ID\""
                + " FROM \"TBLS\" join \"DBS\" on \"TBLS\".\"DB_ID\" = \"DBS\".\"DB_ID\""
                + " join \"SDS\" on \"SDS\".\"SD_ID\" = \"TBLS\".\"SD_ID\""
                + " join \"COLUMNS_V2\" on \"COLUMNS_V2\".\"CD_ID\" = \"SDS\".\"CD_ID\""
                + " WHERE \"DBS\".\"NAME\" = '" + dbName + "' AND \"TBLS\".\"TBL_NAME\"='" + tblName + "';";
        LOG.debug("getSchema exec sql: {}", sql);

        Builder<FieldSchema> builder = ImmutableList.builder();
        int tableId = -1;
        try (Connection conn = getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                FieldSchema fieldSchema = new FieldSchema(rs.getString("COLUMN_NAME"),
                        rs.getString("TYPE_NAME"), rs.getString("COMMENT"));
                builder.add(fieldSchema);
                // actually, all resultSets have the same TBL_ID.
                tableId = rs.getInt("TBL_ID");
            }
        } catch (Exception e) {
            throw new Exception("Can not get schema of db = " + dbName + ", table = " + tblName);
        }

        // add partition columns
        getTablePartitionKeys(tableId).stream().forEach(field -> builder.add(field));
        return builder.build();
    }

    @Override
    public List<ColumnStatisticsObj> getTableColumnStatistics(String dbName, String tblName, List<String> columns)
            throws Exception {
        LOG.debug("getTableColumnStatistics is not supported");
        return ImmutableList.of();
    }

    // no use
    @Override
    public Map<String, List<ColumnStatisticsObj>> getPartitionColumnStatistics(String dbName, String tblName,
            List<String> partNames, List<String> columns) throws Exception {
        return null;
    }

    @Override
    public CurrentNotificationEventId getCurrentNotificationEventId() throws Exception {
        throw new Exception("Do not support in JdbcPostgreSQLClientCachedClient.");
    }

    @Override
    public NotificationEventResponse getNextNotification(long lastEventId, int maxEvents, NotificationFilter filter)
            throws Exception {
        throw new Exception("Do not support in JdbcPostgreSQLClientCachedClient.");
    }

    @Override
    public long openTxn(String user) throws Exception {
        throw new Exception("Do not support in JdbcPostgreSQLClientCachedClient.");
    }

    @Override
    public void commitTxn(long txnId) throws Exception {
        throw new Exception("Do not support in JdbcPostgreSQLClientCachedClient.");
    }

    @Override
    public ValidTxnList getValidTxns() throws Exception {
        throw new Exception("Do not support in JdbcPostgreSQLClientCachedClient.");
    }

    @Override
    public List<TableValidWriteIds> getValidWriteIds(List<String> fullTableName, String validTransactions)
            throws Exception {
        throw new Exception("Do not support in JdbcPostgreSQLClientCachedClient.");
    }

    @Override
    public LockResponse checkLock(long lockId) throws Exception {
        throw new Exception("Do not support in JdbcPostgreSQLClientCachedClient.");
    }

    @Override
    public LockResponse lock(LockRequest lockRequest) throws Exception {
        throw new Exception("Do not support in JdbcPostgreSQLClientCachedClient.");
    }
}
