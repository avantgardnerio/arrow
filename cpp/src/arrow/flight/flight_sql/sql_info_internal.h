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

#include "arrow/flight/flight_sql/sql_info_types.h"

namespace arrow {
namespace flight {
namespace sql {
namespace internal {

/// \brief Auxiliary class used to populate GetSqlInfo's DenseUnionArray with different
/// data types.
class SqlInfoResultAppender {
 public:
  /// \brief Appends a string to the DenseUnionBuilder.
  /// \param[in] value Value to be appended.
  Status operator()(const std::string& value);

  /// \brief Appends a bool to the DenseUnionBuilder.
  /// \param[in] value Value to be appended.
  Status operator()(bool value);

  /// \brief Appends a int64_t to the DenseUnionBuilder.
  /// \param[in] value Value to be appended.
  Status operator()(int64_t value);

  /// \brief Appends a int64_t to the DenseUnionBuilder.
  /// \param[in] value Value to be appended.
  Status operator()(int32_t value);

  /// \brief Appends a string list to the DenseUnionBuilder.
  /// \param[in] value Value to be appended.
  Status operator()(const std::vector<std::string>& value);

  /// \brief Appends a int32 to int32 list map to the DenseUnionBuilder.
  /// \param[in] value Value to be appended.
  Status operator()(const std::unordered_map<int32_t, std::vector<int32_t>>& value);

  /// \brief Creates a Variant visitor that appends data to given
  /// DenseUnionBuilder. \param[in] value_builder  DenseUnionBuilder to append data to.
  explicit SqlInfoResultAppender(DenseUnionBuilder& value_builder);

  SqlInfoResultAppender(const SqlInfoResultAppender&) = delete;
  SqlInfoResultAppender(SqlInfoResultAppender&&) = delete;
  SqlInfoResultAppender& operator=(const SqlInfoResultAppender&) = delete;

 private:
  DenseUnionBuilder& value_builder_;

  // Builders for each child on dense union
  StringBuilder* string_value_builder_;
  BooleanBuilder* bool_value_builder_;
  Int64Builder* bigint_value_builder_;
  Int32Builder* int32_bitmask_builder_;
  ListBuilder* string_list_builder_;
  MapBuilder* int32_to_int32_list_builder_;

  enum : int8_t {
    STRING_VALUE_INDEX = 0,
    BOOL_VALUE_INDEX = 1,
    BIGINT_VALUE_INDEX = 2,
    INT32_BITMASK_INDEX = 3,
    STRING_LIST_INDEX = 4,
    INT32_TO_INT32_LIST_INDEX = 5
  };
};

}  // namespace internal
}  // namespace sql
}  // namespace flight
}  // namespace arrow
