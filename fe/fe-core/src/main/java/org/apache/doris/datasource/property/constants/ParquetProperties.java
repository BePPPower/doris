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

package org.apache.doris.datasource.property.constants;

import org.apache.doris.thrift.TParquetVersion;

public class ParquetProperties {
    public static final String COMPRESS_TYPE = "compress_type";
    public static final String PARQUET_DISABLE_DICTIONARY = "disable_dictionary";
    public static final TParquetVersion parquetVersion = TParquetVersion.PARQUET_1_0;
    public static final String PARQUET_VERSION = "version";
    public static final String PARQUET_PROP_PREFIX = "parquet.";
}
