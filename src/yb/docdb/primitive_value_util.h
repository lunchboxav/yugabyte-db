// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//

// Utilities for docdb operations.

#ifndef YB_DOCDB_PRIMITIVE_VALUE_UTIL_H
#define YB_DOCDB_PRIMITIVE_VALUE_UTIL_H

#include "yb/docdb/docdb.h"

namespace yb {
namespace docdb {

// Add primary key column values to the component group. Verify that they are in the same order
// as in the table schema.
CHECKED_STATUS QLKeyColumnValuesToPrimitiveValues(
    const google::protobuf::RepeatedPtrField<QLExpressionPB> &column_values,
    const Schema &schema, size_t column_idx, const size_t column_count,
    vector<PrimitiveValue> *components);

Result<vector<PrimitiveValue>> InitKeyColumnPrimitiveValues(
    const google::protobuf::RepeatedPtrField<PgsqlExpressionPB> &column_values,
    const Schema &schema,
    size_t start_idx);

}  // namespace docdb
}  // namespace yb

#endif // YB_DOCDB_PRIMITIVE_VALUE_UTIL_H
