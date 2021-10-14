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

#include "arrow/flight/flight-sql/example/sqlite_server.h"

#include <sqlite3.h>

#include <boost/algorithm/string.hpp>
#include <boost/lexical_cast.hpp>
#include <boost/uuid/uuid.hpp>
#include <boost/uuid/uuid_io.hpp>
#include <sstream>

#include "arrow/api.h"
#include "arrow/flight/flight-sql/example/sqlite_statement.h"
#include "arrow/flight/flight-sql/example/sqlite_statement_batch_reader.h"
#include "arrow/flight/flight-sql/example/sqlite_tables_schema_batch_reader.h"
#include "arrow/flight/flight-sql/server.h"

namespace arrow {
namespace flight {
namespace sql {
namespace example {

std::shared_ptr<DataType> GetArrowType(const char* sqlite_type) {
  if (sqlite_type == NULLPTR) {
    // SQLite may not know the column type yet.
    return null();
  }

  if (boost::iequals(sqlite_type, "int") || boost::iequals(sqlite_type, "integer")) {
    return int64();
  } else if (boost::iequals(sqlite_type, "REAL")) {
    return float64();
  } else if (boost::iequals(sqlite_type, "BLOB")) {
    return binary();
  } else if (boost::iequals(sqlite_type, "TEXT") ||
             boost::istarts_with(sqlite_type, "char") ||
             boost::istarts_with(sqlite_type, "varchar")) {
    return utf8();
  } else {
    return null();
  }
}

std::string PrepareQueryForGetTables(const pb::sql::CommandGetTables& command) {
  std::stringstream table_query;

  table_query << "SELECT null as catalog_name, null as schema_name, name as "
                 "table_name, type as table_type FROM sqlite_master where 1=1";

  if (command.has_catalog()) {
    table_query << " and catalog_name='" << command.catalog() << "'";
  }

  if (command.has_schema_filter_pattern()) {
    table_query << " and schema_name LIKE '" << command.schema_filter_pattern() << "'";
  }

  if (command.has_table_name_filter_pattern()) {
    table_query << " and table_name LIKE '" << command.table_name_filter_pattern() << "'";
  }

  if (!command.table_types().empty()) {
    google::protobuf::RepeatedPtrField<std::string> types = command.table_types();

    table_query << " and table_type IN (";
    int size = types.size();
    for (int i = 0; i < size; i++) {
      table_query << "'" << types.at(i) << "'";
      if (size - 1 != i) {
        table_query << ",";
      }
    }

    table_query << ")";
  }

  table_query << " order by table_name";
  return table_query.str();
}

SQLiteFlightSqlServer::SQLiteFlightSqlServer() {
  db_ = NULLPTR;
  if (sqlite3_open(":memory:", &db_)) {
    sqlite3_close(db_);
    throw std::runtime_error(std::string("Can't open database: ") + sqlite3_errmsg(db_));
  }

  ExecuteSql(R"(
CREATE TABLE foreignTable (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  foreignName varchar(100),
  value int);

CREATE TABLE intTable (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  keyName varchar(100),
  value int,
  foreignId int references foreignTable(id));

INSERT INTO foreignTable (foreignName, value) VALUES ('keyOne', 1);
INSERT INTO foreignTable (foreignName, value) VALUES ('keyTwo', 0);
INSERT INTO foreignTable (foreignName, value) VALUES ('keyThree', -1);
INSERT INTO intTable (keyName, value, foreignId) VALUES ('one', 1, 1);
INSERT INTO intTable (keyName, value, foreignId) VALUES ('zero', 0, 1);
INSERT INTO intTable (keyName, value, foreignId) VALUES ('negative one', -1, 1);
  )");
}

SQLiteFlightSqlServer::~SQLiteFlightSqlServer() { sqlite3_close(db_); }

void SQLiteFlightSqlServer::ExecuteSql(const std::string& sql) {
  char* zErrMsg = NULLPTR;
  int rc = sqlite3_exec(db_, sql.c_str(), NULLPTR, NULLPTR, &zErrMsg);
  if (rc != SQLITE_OK) {
    fprintf(stderr, "SQL error: %s\n", zErrMsg);
    sqlite3_free(zErrMsg);
  }
}

Status DoGetSQLiteQuery(sqlite3* db, const std::string& query,
                        const std::shared_ptr<Schema>& schema,
                        std::unique_ptr<FlightDataStream>* result) {
  std::shared_ptr<SqliteStatement> statement;
  ARROW_RETURN_NOT_OK(SqliteStatement::Create(db, query, &statement));

  std::shared_ptr<SqliteStatementBatchReader> reader;
  ARROW_RETURN_NOT_OK(SqliteStatementBatchReader::Create(statement, schema, &reader));

  *result = std::unique_ptr<FlightDataStream>(new RecordBatchStream(reader));

  return Status::OK();
}

Status GetFlightInfoForCommand(const FlightDescriptor& descriptor,
                               std::unique_ptr<FlightInfo>* info,
                               const google::protobuf::Message& command,
                               const std::shared_ptr<Schema>& schema) {
  google::protobuf::Any ticketParsed;
  ticketParsed.PackFrom(command);

  std::vector<FlightEndpoint> endpoints{
      FlightEndpoint{{ticketParsed.SerializeAsString()}, {}}};
  ARROW_ASSIGN_OR_RAISE(auto result,
                        FlightInfo::Make(*schema, descriptor, endpoints, -1, -1))

  *info = std::unique_ptr<FlightInfo>(new FlightInfo(result));

  return Status::OK();
}

Status SQLiteFlightSqlServer::GetFlightInfoStatement(
    const pb::sql::CommandStatementQuery& command, const ServerCallContext& context,
    const FlightDescriptor& descriptor, std::unique_ptr<FlightInfo>* info) {
  const std::string& query = command.query();

  std::shared_ptr<SqliteStatement> statement;
  ARROW_RETURN_NOT_OK(SqliteStatement::Create(db_, query, &statement));

  std::shared_ptr<Schema> schema;
  ARROW_RETURN_NOT_OK(statement->GetSchema(&schema));

  pb::sql::TicketStatementQuery ticket_statement_query;
  ticket_statement_query.set_statement_handle(query);

  google::protobuf::Any ticket;
  ticket.PackFrom(ticket_statement_query);

  const std::string& ticket_string = ticket.SerializeAsString();
  std::vector<FlightEndpoint> endpoints{FlightEndpoint{{ticket_string}, {}}};
  ARROW_ASSIGN_OR_RAISE(auto result,
                        FlightInfo::Make(*schema, descriptor, endpoints, -1, -1))

  *info = std::unique_ptr<FlightInfo>(new FlightInfo(result));

  return Status::OK();
}

Status SQLiteFlightSqlServer::DoGetStatement(const pb::sql::TicketStatementQuery& command,
                                             const ServerCallContext& context,
                                             std::unique_ptr<FlightDataStream>* result) {
  const std::string& sql = command.statement_handle();

  std::shared_ptr<SqliteStatement> statement;
  ARROW_RETURN_NOT_OK(SqliteStatement::Create(db_, sql, &statement));

  std::shared_ptr<SqliteStatementBatchReader> reader;
  ARROW_RETURN_NOT_OK(SqliteStatementBatchReader::Create(statement, &reader));

  *result = std::unique_ptr<FlightDataStream>(new RecordBatchStream(reader));

  return Status::OK();
}

Status SQLiteFlightSqlServer::GetFlightInfoCatalogs(const ServerCallContext& context,
                                                    const FlightDescriptor& descriptor,
                                                    std::unique_ptr<FlightInfo>* info) {
  pb::sql::CommandGetCatalogs command;
  return GetFlightInfoForCommand(descriptor, info, command,
                                 SqlSchema::GetCatalogsSchema());
}

Status SQLiteFlightSqlServer::DoGetCatalogs(const ServerCallContext& context,
                                            std::unique_ptr<FlightDataStream>* result) {
  // As SQLite doesn't support catalogs, this will return an empty record batch.

  const std::shared_ptr<Schema>& schema = SqlSchema::GetCatalogsSchema();

  StringBuilder catalog_name_builder;
  ARROW_ASSIGN_OR_RAISE(auto catalog_name, catalog_name_builder.Finish());

  const std::shared_ptr<RecordBatch>& batch =
      RecordBatch::Make(schema, 0, {catalog_name});

  ARROW_ASSIGN_OR_RAISE(auto reader, RecordBatchReader::Make({batch}));
  *result = std::unique_ptr<FlightDataStream>(new RecordBatchStream(reader));
  return Status::OK();
}

Status SQLiteFlightSqlServer::GetFlightInfoSchemas(
    const pb::sql::CommandGetSchemas& command, const ServerCallContext& context,
    const FlightDescriptor& descriptor, std::unique_ptr<FlightInfo>* info) {
  return GetFlightInfoForCommand(descriptor, info, command,
                                 SqlSchema::GetSchemasSchema());
}

Status SQLiteFlightSqlServer::DoGetSchemas(const pb::sql::CommandGetSchemas& command,
                                           const ServerCallContext& context,
                                           std::unique_ptr<FlightDataStream>* result) {
  // As SQLite doesn't support schemas, this will return an empty record batch.

  const std::shared_ptr<Schema>& schema = SqlSchema::GetSchemasSchema();

  StringBuilder catalog_name_builder;
  ARROW_ASSIGN_OR_RAISE(auto catalog_name, catalog_name_builder.Finish());
  StringBuilder schema_name_builder;
  ARROW_ASSIGN_OR_RAISE(auto schema_name, schema_name_builder.Finish());

  const std::shared_ptr<RecordBatch>& batch =
      RecordBatch::Make(schema, 0, {catalog_name, schema_name});

  ARROW_ASSIGN_OR_RAISE(auto reader, RecordBatchReader::Make({batch}));
  *result = std::unique_ptr<FlightDataStream>(new RecordBatchStream(reader));
  return Status::OK();
}

Status SQLiteFlightSqlServer::GetFlightInfoTables(
    const pb::sql::CommandGetTables& command, const ServerCallContext& context,
    const FlightDescriptor& descriptor, std::unique_ptr<FlightInfo>* info) {
  google::protobuf::Any ticketParsed;

  ticketParsed.PackFrom(command);

  std::vector<FlightEndpoint> endpoints{
      FlightEndpoint{{ticketParsed.SerializeAsString()}, {}}};

  bool include_schema = command.include_schema();

  ARROW_ASSIGN_OR_RAISE(
      auto result,
      FlightInfo::Make(include_schema ? *SqlSchema::GetTablesSchemaWithIncludedSchema()
                                      : *SqlSchema::GetTablesSchema(),
                       descriptor, endpoints, -1, -1))
  *info = std::unique_ptr<FlightInfo>(new FlightInfo(result));

  return Status::OK();
}

Status SQLiteFlightSqlServer::DoGetTables(const pb::sql::CommandGetTables& command,
                                          const ServerCallContext& context,
                                          std::unique_ptr<FlightDataStream>* result) {
  std::string query = PrepareQueryForGetTables(command);

  std::shared_ptr<SqliteStatement> statement;
  ARROW_RETURN_NOT_OK(SqliteStatement::Create(db_, query, &statement));

  std::shared_ptr<SqliteStatementBatchReader> reader;
  ARROW_RETURN_NOT_OK(SqliteStatementBatchReader::Create(
      statement, SqlSchema::GetTablesSchema(), &reader));

  if (command.include_schema()) {
    std::shared_ptr<SqliteTablesWithSchemaBatchReader> table_schema_reader =
        std::make_shared<SqliteTablesWithSchemaBatchReader>(reader, query, db_);
    *result =
        std::unique_ptr<FlightDataStream>(new RecordBatchStream(table_schema_reader));
  } else {
    *result = std::unique_ptr<FlightDataStream>(new RecordBatchStream(reader));
  }

  return Status::OK();
}

Status SQLiteFlightSqlServer::DoPutCommandStatementUpdate(
    const pb::sql::CommandStatementUpdate& command, const ServerCallContext& context,
    std::unique_ptr<FlightMessageReader>& reader,
    std::unique_ptr<FlightMetadataWriter>& writer) {
  const std::string& sql = command.query();

  std::shared_ptr<SqliteStatement> statement;
  ARROW_RETURN_NOT_OK(SqliteStatement::Create(db_, sql, &statement));

  pb::sql::DoPutUpdateResult result;

  int64_t record_count;
  ARROW_RETURN_NOT_OK(statement->ExecuteUpdate(&record_count));

  result.set_record_count(record_count);

  const std::shared_ptr<Buffer>& buffer = Buffer::FromString(result.SerializeAsString());
  ARROW_RETURN_NOT_OK(writer->WriteMetadata(*buffer));

  return Status::OK();
}

Status SQLiteFlightSqlServer::CreatePreparedStatement(
    const pb::sql::ActionCreatePreparedStatementRequest& request,
    const ServerCallContext& context, std::unique_ptr<ResultStream>* result) {
  std::shared_ptr<SqliteStatement> statement;
  ARROW_RETURN_NOT_OK(SqliteStatement::Create(db_, request.query(), &statement));

  boost::uuids::uuid uuid = uuid_generator_();
  prepared_statements_[uuid] = statement;

  std::shared_ptr<Schema> dataset_schema;
  ARROW_RETURN_NOT_OK(statement->GetSchema(&dataset_schema));
  ARROW_ASSIGN_OR_RAISE(auto serialized_dataset_schema,
                        ipc::SerializeSchema(*dataset_schema));

  sqlite3_stmt* stmt = statement->GetSqlite3Stmt();
  const int parameter_count = sqlite3_bind_parameter_count(stmt);
  std::vector<std::shared_ptr<arrow::Field>> parameter_fields;
  parameter_fields.reserve(parameter_count);

  // As SQLite doesn't know the parameter types before executing the query, the
  // example server is accepting any SQLite supported type as input by using a dense
  // union.
  const std::shared_ptr<DataType>& dense_union_type = GetUnknownColumnDataType();

  for (int i = 0; i < parameter_count; i++) {
    const char* parameter_name_chars = sqlite3_bind_parameter_name(stmt, i + 1);
    std::string parameter_name;
    if (parameter_name_chars == NULLPTR) {
      parameter_name = std::string("parameter_") + std::to_string(i + 1);
    } else {
      parameter_name = parameter_name_chars;
    }
    parameter_fields.push_back(field(parameter_name, dense_union_type));
  }

  const std::shared_ptr<Schema>& parameter_schema = arrow::schema(parameter_fields);
  ARROW_ASSIGN_OR_RAISE(auto serialized_parameter_schema,
                        ipc::SerializeSchema(*parameter_schema));

  pb::sql::ActionCreatePreparedStatementResult action_result;
  action_result.set_dataset_schema(serialized_dataset_schema->ToString());
  action_result.set_parameter_schema(serialized_parameter_schema->ToString());
  action_result.set_prepared_statement_handle(boost::uuids::to_string(uuid));

  google::protobuf::Any any;
  any.PackFrom(action_result);

  auto buf = Buffer::FromString(any.SerializeAsString());
  *result = std::unique_ptr<ResultStream>(new SimpleResultStream({Result{buf}}));

  return Status::OK();
}

Status SQLiteFlightSqlServer::ClosePreparedStatement(
    const pb::sql::ActionClosePreparedStatementRequest& request,
    const ServerCallContext& context, std::unique_ptr<ResultStream>* result) {
  const std::string& prepared_statement_handle = request.prepared_statement_handle();
  const auto& uuid = boost::lexical_cast<boost::uuids::uuid>(prepared_statement_handle);

  auto search = prepared_statements_.find(uuid);
  if (search != prepared_statements_.end()) {
    prepared_statements_.erase(uuid);
  } else {
    return Status::Invalid("Prepared statement not found");
  }

  // Need to instantiate a ResultStream, otherwise clients can not wait for completion.
  *result = std::unique_ptr<ResultStream>(new SimpleResultStream({}));
  return Status::OK();
}

Status SQLiteFlightSqlServer::GetFlightInfoPreparedStatement(
    const pb::sql::CommandPreparedStatementQuery& command,
    const ServerCallContext& context, const FlightDescriptor& descriptor,
    std::unique_ptr<FlightInfo>* info) {
  const std::string& prepared_statement_handle = command.prepared_statement_handle();
  const auto& uuid = boost::lexical_cast<boost::uuids::uuid>(prepared_statement_handle);

  auto search = prepared_statements_.find(uuid);
  if (search == prepared_statements_.end()) {
    return Status::Invalid("Prepared statement not found");
  }

  std::shared_ptr<SqliteStatement> statement = search->second;

  std::shared_ptr<Schema> schema;
  ARROW_RETURN_NOT_OK(statement->GetSchema(&schema));

  return GetFlightInfoForCommand(descriptor, info, command, schema);
}

Status SQLiteFlightSqlServer::DoGetPreparedStatement(
    const pb::sql::CommandPreparedStatementQuery& command,
    const ServerCallContext& context, std::unique_ptr<FlightDataStream>* result) {
  const std::string& prepared_statement_handle = command.prepared_statement_handle();
  const auto& uuid = boost::lexical_cast<boost::uuids::uuid>(prepared_statement_handle);

  auto search = prepared_statements_.find(uuid);
  if (search == prepared_statements_.end()) {
    return Status::Invalid("Prepared statement not found");
  }

  std::shared_ptr<SqliteStatement> statement = search->second;

  std::shared_ptr<SqliteStatementBatchReader> reader;
  ARROW_RETURN_NOT_OK(SqliteStatementBatchReader::Create(statement, &reader));

  *result = std::unique_ptr<FlightDataStream>(new RecordBatchStream(reader));

  return Status::OK();
}

Status SQLiteFlightSqlServer::DoPutPreparedStatement(
    const pb::sql::CommandPreparedStatementQuery& command,
    const ServerCallContext& context, std::unique_ptr<FlightMessageReader>& reader,
    std::unique_ptr<FlightMetadataWriter>& writer) {
  const std::string& prepared_statement_handle = command.prepared_statement_handle();
  const auto& uuid = boost::lexical_cast<boost::uuids::uuid>(prepared_statement_handle);

  auto search = prepared_statements_.find(uuid);
  if (search == prepared_statements_.end()) {
    return Status::Invalid("Prepared statement not found");
  }

  std::shared_ptr<SqliteStatement> statement = search->second;

  sqlite3_stmt* stmt = statement->GetSqlite3Stmt();

  // Loading parameters received from the RecordBatches on the underlying sqlite3_stmt.
  FlightStreamChunk chunk;
  while (true) {
    RETURN_NOT_OK(reader->Next(&chunk));
    std::shared_ptr<RecordBatch>& record_batch = chunk.data;
    if (record_batch == nullptr) break;

    const int64_t num_rows = record_batch->num_rows();
    const int& num_columns = record_batch->num_columns();

    for (int i = 0; i < num_rows; ++i) {
      for (int c = 0; c < num_columns; ++c) {
        const std::shared_ptr<Array>& column = record_batch->column(c);
        ARROW_ASSIGN_OR_RAISE(std::shared_ptr<Scalar> scalar, column->GetScalar(i));

        auto& holder = reinterpret_cast<DenseUnionScalar&>(*scalar).value;

        switch (holder->type->id()) {
          case Type::INT64: {
            int64_t value = reinterpret_cast<Int64Scalar&>(*holder).value;
            sqlite3_bind_int64(stmt, c + 1, value);
            break;
          }
          case Type::FLOAT: {
            double value = reinterpret_cast<FloatScalar&>(*holder).value;
            sqlite3_bind_double(stmt, c + 1, value);
            break;
          }
          case Type::STRING: {
            std::shared_ptr<Buffer> buffer =
                reinterpret_cast<StringScalar&>(*holder).value;
            const std::string string = buffer->ToString();
            const char* value = string.c_str();
            sqlite3_bind_text(stmt, c + 1, value, static_cast<int>(strlen(value)),
                              SQLITE_TRANSIENT);
            break;
          }
          case Type::BINARY: {
            std::shared_ptr<Buffer> buffer =
                reinterpret_cast<BinaryScalar&>(*holder).value;
            sqlite3_bind_blob(stmt, c + 1, buffer->data(),
                              static_cast<int>(buffer->size()), SQLITE_TRANSIENT);
            break;
          }
          default:
            return Status::Invalid("Received unsupported data type: ",
                                   holder->type->ToString());
        }
      }
    }
  }

  return Status::OK();
}

Status SQLiteFlightSqlServer::GetFlightInfoTableTypes(const ServerCallContext& context,
                                                      const FlightDescriptor& descriptor,
                                                      std::unique_ptr<FlightInfo>* info) {
  pb::sql::CommandGetTableTypes command;
  return GetFlightInfoForCommand(descriptor, info, command,
                                 SqlSchema::GetTableTypesSchema());
}

Status SQLiteFlightSqlServer::DoGetTableTypes(const ServerCallContext& context,
                                              std::unique_ptr<FlightDataStream>* result) {
  std::string query = "SELECT DISTINCT type as table_type FROM sqlite_master";

  return DoGetSQLiteQuery(db_, query, SqlSchema::GetTableTypesSchema(), result);
}

Status SQLiteFlightSqlServer::GetFlightInfoPrimaryKeys(
    const pb::sql::CommandGetPrimaryKeys& command, const ServerCallContext& context,
    const FlightDescriptor& descriptor, std::unique_ptr<FlightInfo>* info) {
  return GetFlightInfoForCommand(descriptor, info, command,
                                 SqlSchema::GetPrimaryKeysSchema());
}

Status SQLiteFlightSqlServer::DoGetPrimaryKeys(
    const pb::sql::CommandGetPrimaryKeys& command, const ServerCallContext& context,
    std::unique_ptr<FlightDataStream>* result) {
  std::stringstream table_query;

  // The field key_name can not be recovered by the sqlite, so it is being set
  // to null following the same pattern for catalog_name and schema_name.
  table_query << "SELECT null as catalog_name, null as schema_name, table_name, "
                 "name as column_name,  pk as key_sequence, null as key_name\n"
                 "FROM pragma_table_info(table_name)\n"
                 "    JOIN (SELECT null as catalog_name, null as schema_name, name as "
                 "table_name, type as table_type\n"
                 "FROM sqlite_master) where 1=1 and pk != 0";

  if (command.has_catalog()) {
    table_query << " and catalog_name LIKE '" << command.catalog() << "'";
  }

  if (command.has_schema()) {
    table_query << " and schema_name LIKE '" << command.schema() << "'";
  }

  table_query << " and table_name LIKE '" << command.table() << "'";

  return DoGetSQLiteQuery(db_, table_query.str(), SqlSchema::GetPrimaryKeysSchema(),
                          result);
}

std::string PrepareQueryForGetImportedOrExportedKeys(const std::string& filter) {
  return R"(SELECT * FROM (SELECT NULL AS pk_catalog_name,
    NULL AS pk_schema_name,
    p."table" AS pk_table_name,
    p."to" AS pk_column_name,
    NULL AS fk_catalog_name,
    NULL AS fk_schema_name,
    m.name AS fk_table_name,
    p."from" AS fk_column_name,
    p.seq AS key_sequence,
    NULL AS pk_key_name,
    NULL AS fk_key_name,
    CASE
        WHEN p.on_update = 'CASCADE' THEN 0
        WHEN p.on_update = 'RESTRICT' THEN 1
        WHEN p.on_update = 'SET NULL' THEN 2
        WHEN p.on_update = 'NO ACTION' THEN 3
        WHEN p.on_update = 'SET DEFAULT' THEN 4
    END AS update_rule,
    CASE
        WHEN p.on_delete = 'CASCADE' THEN 0
        WHEN p.on_delete = 'RESTRICT' THEN 1
        WHEN p.on_delete = 'SET NULL' THEN 2
        WHEN p.on_delete = 'NO ACTION' THEN 3
        WHEN p.on_delete = 'SET DEFAULT' THEN 4
    END AS delete_rule
  FROM sqlite_master m
  JOIN pragma_foreign_key_list(m.name) p ON m.name != p."table"
  WHERE m.type = 'table') WHERE )" +
         filter + R"( ORDER BY
  pk_catalog_name, pk_schema_name, pk_table_name, pk_key_name, key_sequence)";
}

Status SQLiteFlightSqlServer::GetFlightInfoImportedKeys(
    const pb::sql::CommandGetImportedKeys& command, const ServerCallContext& context,
    const FlightDescriptor& descriptor, std::unique_ptr<FlightInfo>* info) {
  return GetFlightInfoForCommand(descriptor, info, command,
                                 SqlSchema::GetImportedAndExportedKeysSchema());
}

Status SQLiteFlightSqlServer::DoGetImportedKeys(
    const pb::sql::CommandGetImportedKeys& command, const ServerCallContext& context,
    std::unique_ptr<FlightDataStream>* result) {
  std::string filter = "fk_table_name = '" + command.table() + "'";
  if (command.has_catalog()) {
    filter += " AND fk_catalog_name = '" + command.catalog() + "'";
  }
  if (command.has_schema()) {
    filter += " AND fk_schema_name = '" + command.schema() + "'";
  }
  std::string query = PrepareQueryForGetImportedOrExportedKeys(filter);

  return DoGetSQLiteQuery(db_, query, SqlSchema::GetImportedAndExportedKeysSchema(),
                          result);
}

Status SQLiteFlightSqlServer::GetFlightInfoExportedKeys(
    const pb::sql::CommandGetExportedKeys& command, const ServerCallContext& context,
    const FlightDescriptor& descriptor, std::unique_ptr<FlightInfo>* info) {
  return GetFlightInfoForCommand(descriptor, info, command,
                                 SqlSchema::GetImportedAndExportedKeysSchema());
}

Status SQLiteFlightSqlServer::DoGetExportedKeys(
    const pb::sql::CommandGetExportedKeys& command, const ServerCallContext& context,
    std::unique_ptr<FlightDataStream>* result) {
  std::string filter = "pk_table_name = '" + command.table() + "'";
  if (command.has_catalog()) {
    filter += " AND pk_catalog_name = '" + command.catalog() + "'";
  }
  if (command.has_schema()) {
    filter += " AND pk_schema_name = '" + command.schema() + "'";
  }
  std::string query = PrepareQueryForGetImportedOrExportedKeys(filter);

  return DoGetSQLiteQuery(db_, query, SqlSchema::GetImportedAndExportedKeysSchema(),
                          result);
}

}  // namespace example
}  // namespace sql
}  // namespace flight
}  // namespace arrow
