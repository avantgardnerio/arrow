/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.arrow.driver.jdbc.client;

import org.apache.arrow.driver.jdbc.client.utils.ClientAuthenticationUtils;
import org.apache.arrow.flight.*;
import org.apache.arrow.flight.auth2.BearerCredentialWriter;
import org.apache.arrow.flight.auth2.ClientBearerHeaderHandler;
import org.apache.arrow.flight.auth2.ClientIncomingAuthHeaderMiddleware;
import org.apache.arrow.flight.client.ClientCookieMiddleware;
import org.apache.arrow.flight.grpc.CredentialCallOption;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.flight.sql.impl.FlightSql.SqlInfo;
import org.apache.arrow.flight.sql.util.TableRef;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.util.AutoCloseables;
import org.apache.arrow.util.Preconditions;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.calcite.avatica.Meta.StatementType;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.util.*;

import static org.apache.arrow.flight.LocationSchemes.GRPC_INSECURE;
import static org.apache.arrow.flight.LocationSchemes.GRPC_TLS;

/**
 * A {@link FlightSqlClient} handler.
 */
public final class ArrowFlightSqlClientHandler implements AutoCloseable {

  ConnectionManager connectionManager;
  private final FlightSqlClient sqlClient;
  private final Set<CallOption> options = new HashSet<>();

  ArrowFlightSqlClientHandler(final ConnectionManager connectionManager,
                              final Collection<CallOption> options) throws SQLException {
    this.connectionManager = Preconditions.checkNotNull(connectionManager);
    this.options.addAll(options);
    FlightSqlClient sqlClient = connectionManager.getConnection(null);
    this.sqlClient = Preconditions.checkNotNull(sqlClient);
  }

  /**
   * Creates a new {@link ArrowFlightSqlClientHandler} from the provided {@code client} and {@code options}.
   *
   * @param connectionManager the {@link ConnectionManager} to manage {@link FlightSqlClient} connections.
   * @param options the {@link CallOption}s to persist in between subsequent client calls.
   * @return a new {@link ArrowFlightSqlClientHandler}.
   */
  public static ArrowFlightSqlClientHandler createNewHandler(final ConnectionManager connectionManager,
                                                             final Collection<CallOption> options) throws SQLException {
    return new ArrowFlightSqlClientHandler(connectionManager, options);
  }

  /**
   * Gets the {@link #options} for the subsequent calls from this handler.
   *
   * @return the {@link CallOption}s.
   */
  private CallOption[] getOptions() {
    return options.toArray(new CallOption[0]);
  }

  /**
   * Makes an RPC "getStream" request based on the provided {@link FlightInfo}
   * object. Retrieves the result of the query previously prepared with "getInfo."
   *
   * @param flightInfo The {@link FlightInfo} instance from which to fetch results.
   * @return a {@code FlightStream} of results.
   */
  public List<FlightStream> getStreams(final FlightInfo flightInfo) throws RuntimeException {
    try {
      ArrayList<FlightStream> streams = new ArrayList<>();
      for (FlightEndpoint ep : flightInfo.getEndpoints()) {
        URI uri = ep.getLocations().size() > 0 ? ep.getLocations().get(0).getUri() : null;
        FlightSqlClient sqlClient = this.connectionManager.getConnection(uri);
        streams.add(sqlClient.getStream(ep.getTicket(), getOptions()));
      }
      return streams;
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  /**
   * Makes an RPC "getInfo" request based on the provided {@code query}
   * object.
   *
   * @param query The query.
   * @return a {@code FlightStream} of results.
   */
  public FlightInfo getInfo(final String query) {
    return sqlClient.execute(query, getOptions());
  }

  @Override
  public void close() throws SQLException {
    try {
      AutoCloseables.close(connectionManager);
    } catch (final Exception e) {
      throw new SQLException("Failed to clean up client resources.", e);
    }
  }

  /**
   * A prepared statement handler.
   */
  public interface PreparedStatement extends AutoCloseable {
    /**
     * Executes this {@link PreparedStatement}.
     *
     * @return the {@link FlightInfo} representing the outcome of this query execution.
     * @throws SQLException on error.
     */
    FlightInfo executeQuery() throws SQLException;

    /**
     * Executes a {@link StatementType#UPDATE} query.
     *
     * @return the number of rows affected.
     */
    long executeUpdate();

    /**
     * Gets the {@link StatementType} of this {@link PreparedStatement}.
     *
     * @return the Statement Type.
     */
    StatementType getType();

    /**
     * Gets the {@link Schema} of this {@link PreparedStatement}.
     *
     * @return {@link Schema}.
     */
    Schema getDataSetSchema();

    @Override
    void close();
  }

  /**
   * Creates a new {@link PreparedStatement} for the given {@code query}.
   *
   * @param query the SQL query.
   * @return a new prepared statement.
   */
  public PreparedStatement prepare(final String query) {
    final FlightSqlClient.PreparedStatement preparedStatement =
        sqlClient.prepare(query, getOptions());
    return new PreparedStatement() {
      @Override
      public FlightInfo executeQuery() throws SQLException {
        return preparedStatement.execute(getOptions());
      }

      @Override
      public long executeUpdate() {
        return preparedStatement.executeUpdate(getOptions());
      }

      @Override
      public StatementType getType() {
        final Schema schema = preparedStatement.getResultSetSchema();
        return schema.getFields().isEmpty() ? StatementType.UPDATE : StatementType.SELECT;
      }

      @Override
      public Schema getDataSetSchema() {
        return preparedStatement.getResultSetSchema();
      }

      @Override
      public void close() {
        preparedStatement.close(getOptions());
      }
    };
  }

  /**
   * Makes an RPC "getCatalogs" request.
   *
   * @return a {@code FlightStream} of results.
   */
  public FlightInfo getCatalogs() {
    return sqlClient.getCatalogs(getOptions());
  }

  /**
   * Makes an RPC "getImportedKeys" request based on the provided info.
   *
   * @param catalog The catalog name. Must match the catalog name as it is stored in the database.
   *                Retrieves those without a catalog. Null means that the catalog name should not be used to
   *                narrow the search.
   * @param schema  The schema name. Must match the schema name as it is stored in the database.
   *                "" retrieves those without a schema. Null means that the schema name should not be used to narrow
   *                the search.
   * @param table   The table name. Must match the table name as it is stored in the database.
   * @return a {@code FlightStream} of results.
   */
  public FlightInfo getImportedKeys(final String catalog, final String schema, final String table) {
    return sqlClient.getImportedKeys(TableRef.of(catalog, schema, table), getOptions());
  }

  /**
   * Makes an RPC "getExportedKeys" request based on the provided info.
   *
   * @param catalog The catalog name. Must match the catalog name as it is stored in the database.
   *                Retrieves those without a catalog. Null means that the catalog name should not be used to
   *                narrow the search.
   * @param schema  The schema name. Must match the schema name as it is stored in the database.
   *                "" retrieves those without a schema. Null means that the schema name should not be used to narrow
   *                the search.
   * @param table   The table name. Must match the table name as it is stored in the database.
   * @return a {@code FlightStream} of results.
   */
  public FlightInfo getExportedKeys(final String catalog, final String schema, final String table) {
    return sqlClient.getExportedKeys(TableRef.of(catalog, schema, table), getOptions());
  }

  /**
   * Makes an RPC "getSchemas" request based on the provided info.
   *
   * @param catalog       The catalog name. Must match the catalog name as it is stored in the database.
   *                      Retrieves those without a catalog. Null means that the catalog name should not be used to
   *                      narrow the search.
   * @param schemaPattern The schema name pattern. Must match the schema name as it is stored in the database.
   *                      Null means that schema name should not be used to narrow down the search.
   * @return a {@code FlightStream} of results.
   */
  public FlightInfo getSchemas(final String catalog, final String schemaPattern) {
    return sqlClient.getSchemas(catalog, schemaPattern, getOptions());
  }

  /**
   * Makes an RPC "getTableTypes" request.
   *
   * @return a {@code FlightStream} of results.
   */
  public FlightInfo getTableTypes() {
    return sqlClient.getTableTypes(getOptions());
  }

  /**
   * Makes an RPC "getTables" request based on the provided info.
   *
   * @param catalog          The catalog name. Must match the catalog name as it is stored in the database.
   *                         Retrieves those without a catalog. Null means that the catalog name should not be used to
   *                         narrow the search.
   * @param schemaPattern    The schema name pattern. Must match the schema name as it is stored in the database.
   *                         "" retrieves those without a schema. Null means that the schema name should not be used to
   *                         narrow the search.
   * @param tableNamePattern The table name pattern. Must match the table name as it is stored in the database.
   * @param types            The list of table types, which must be from the list of table types to include.
   *                         Null returns all types.
   * @param includeSchema    Whether to include schema.
   * @return a {@code FlightStream} of results.
   */
  public FlightInfo getTables(final String catalog, final String schemaPattern,
                              final String tableNamePattern,
                              final List<String> types, final boolean includeSchema) {

    return sqlClient.getTables(catalog, schemaPattern, tableNamePattern, types, includeSchema,
        getOptions());
  }

  /**
   * Gets SQL info.
   *
   * @return the SQL info.
   */
  public FlightInfo getSqlInfo(SqlInfo... info) {
    return sqlClient.getSqlInfo(info, getOptions());
  }

  /**
   * Makes an RPC "getPrimaryKeys" request based on the provided info.
   *
   * @param catalog The catalog name; must match the catalog name as it is stored in the database.
   *                "" retrieves those without a catalog.
   *                Null means that the catalog name should not be used to narrow the search.
   * @param schema  The schema name; must match the schema name as it is stored in the database.
   *                "" retrieves those without a schema. Null means that the schema name should not be used to narrow
   *                the search.
   * @param table   The table name. Must match the table name as it is stored in the database.
   * @return a {@code FlightStream} of results.
   */
  public FlightInfo getPrimaryKeys(final String catalog, final String schema, final String table) {
    return sqlClient.getPrimaryKeys(TableRef.of(catalog, schema, table), getOptions());
  }

  /**
   * Makes an RPC "getCrossReference" request based on the provided info.
   *
   * @param pkCatalog The catalog name. Must match the catalog name as it is stored in the database.
   *                  Retrieves those without a catalog. Null means that the catalog name should not be used to
   *                  narrow the search.
   * @param pkSchema  The schema name. Must match the schema name as it is stored in the database.
   *                  "" retrieves those without a schema. Null means that the schema name should not be used to narrow
   *                  the search.
   * @param pkTable   The table name. Must match the table name as it is stored in the database.
   * @param fkCatalog The catalog name. Must match the catalog name as it is stored in the database.
   *                  Retrieves those without a catalog. Null means that the catalog name should not be used to
   *                  narrow the search.
   * @param fkSchema  The schema name. Must match the schema name as it is stored in the database.
   *                  "" retrieves those without a schema. Null means that the schema name should not be used to narrow
   *                  the search.
   * @param fkTable   The table name. Must match the table name as it is stored in the database.
   * @return a {@code FlightStream} of results.
   */
  public FlightInfo getCrossReference(String pkCatalog, String pkSchema, String pkTable,
                                      String fkCatalog, String fkSchema, String fkTable) {
    return sqlClient.getCrossReference(TableRef.of(pkCatalog, pkSchema, pkTable),
        TableRef.of(fkCatalog, fkSchema, fkTable),
        getOptions());
  }

  /**
   * Manager of {@link FlightSqlClient} connections for {@link ArrowFlightSqlClientHandler}.
   */
  static class ConnectionManager implements AutoCloseable {
    private final Map<URI, FlightSqlClient> connections = new HashMap<>();
    private final Config config;

    public ConnectionManager(Config config) {
      this.config = config;
    }

    /**
     * Gets (or creates) a {@link FlightSqlClient} for a given URI.
     *
     * @param uri the endpoint for which a connection should be established
     * @return the {@link FlightSqlClient} connection
     */
    public FlightSqlClient getConnection(URI uri) throws SQLException {
      FlightClient client = null;
      try {
        // Use default parameters if no URI provided
        if (uri == null) {
          String scheme = config.useEncryption ? GRPC_TLS : GRPC_INSECURE;
          uri = new URI(String.format("%s://%s:%d", scheme, config.host, config.port));
        }

        // Return an existing connection if one exists
        if (connections.containsKey(uri)) {
          return connections.get(uri);
        }

        // Create a new connection and add it to the map
        String host = uri.getHost();
        int port = uri.getPort();
        boolean useEncryption = GRPC_TLS.equals(uri.getScheme());
        ClientIncomingAuthHeaderMiddleware.Factory authFactory = null;
        if (config.username != null) {
          authFactory =
              new ClientIncomingAuthHeaderMiddleware.Factory(new ClientBearerHeaderHandler());
          if(connections.isEmpty()) {
            config.middlewareFactories.add(authFactory); // Only add once
          }
        }
        final FlightClient.Builder clientBuilder = FlightClient.builder().allocator(config.allocator);
        if(connections.isEmpty()) {
          config.middlewareFactories.add(new ClientCookieMiddleware.Factory()); // Only add once
        }
        config.middlewareFactories.forEach(clientBuilder::intercept);
        Location location;
        if (useEncryption) {
          location = Location.forGrpcTls(host, port);
          clientBuilder.useTls();
        } else {
          location = Location.forGrpcInsecure(host, port);
        }
        clientBuilder.location(location);

        if (useEncryption) {
          if (config.disableCertificateVerification) {
            clientBuilder.verifyServer(false);
          } else {
            if (config.useSystemTrustStore) {
              clientBuilder.trustedCertificates(
                  ClientAuthenticationUtils.getCertificateInputStreamFromSystem(config.trustStorePassword));
            } else if (config.trustStorePath != null) {
              clientBuilder.trustedCertificates(
                  ClientAuthenticationUtils.getCertificateStream(config.trustStorePath, config.trustStorePassword));
            }
          }
        }

        client = clientBuilder.build();
        if (authFactory != null) {
          config.options.add(
              ClientAuthenticationUtils.getAuthenticate(client, config.username, config.password, authFactory));
        } else if (config.token != null) {
          config.options.add(
              ClientAuthenticationUtils.getAuthenticate(
                  client, new CredentialCallOption(new BearerCredentialWriter(config.token))));
        }

        // Create the SQL client, add it to the map, return it
        FlightSqlClient sqlClient = new FlightSqlClient(client);
        connections.put(uri, sqlClient);
        return sqlClient;
      } catch (final IllegalArgumentException | GeneralSecurityException | IOException | FlightRuntimeException |
                     URISyntaxException e) {
        final SQLException originalException = new SQLException(e);
        if (client != null) {
          try {
            client.close();
          } catch (final InterruptedException interruptedException) {
            originalException.addSuppressed(interruptedException);
          }
        }
        throw originalException;
      }
    }

    @Override
    public void close() throws Exception {
      for(FlightSqlClient it : connections.values()) {
        AutoCloseables.close(it);
      }
      connections.clear();
    }
  }

  /**
   * Config options shared by both the {@link ArrowFlightSqlClientHandler.Builder}
   * and the {@link ArrowFlightSqlClientHandler.ConnectionManager}.
   */
  static class Config {
    protected final Set<FlightClientMiddleware.Factory> middlewareFactories = new HashSet<>();
    protected final Set<CallOption> options = new HashSet<>();
    protected String host;
    protected int port;
    protected String username;
    protected String password;
    protected String trustStorePath;
    protected String trustStorePassword;
    protected String token;
    protected boolean useEncryption;
    protected boolean disableCertificateVerification;
    protected boolean useSystemTrustStore;
    protected BufferAllocator allocator;
  }

  /**
   * Builder for {@link ArrowFlightSqlClientHandler}.
   */
  public static final class Builder {
    private final Config config = new Config();

    /**
     * Sets the host for this handler.
     *
     * @param host the host.
     * @return this instance.
     */
    public Builder withHost(final String host) {
      this.config.host = host;
      return this;
    }

    /**
     * Sets the port for this handler.
     *
     * @param port the port.
     * @return this instance.
     */
    public Builder withPort(final int port) {
      this.config.port = port;
      return this;
    }

    /**
     * Sets the username for this handler.
     *
     * @param username the username.
     * @return this instance.
     */
    public Builder withUsername(final String username) {
      this.config.username = username;
      return this;
    }

    /**
     * Sets the password for this handler.
     *
     * @param password the password.
     * @return this instance.
     */
    public Builder withPassword(final String password) {
      this.config.password = password;
      return this;
    }

    /**
     * Sets the KeyStore path for this handler.
     *
     * @param trustStorePath the KeyStore path.
     * @return this instance.
     */
    public Builder withTrustStorePath(final String trustStorePath) {
      this.config.trustStorePath = trustStorePath;
      return this;
    }

    /**
     * Sets the KeyStore password for this handler.
     *
     * @param trustStorePassword the KeyStore password.
     * @return this instance.
     */
    public Builder withTrustStorePassword(final String trustStorePassword) {
      this.config.trustStorePassword = trustStorePassword;
      return this;
    }

    /**
     * Sets whether to use TLS encryption in this handler.
     *
     * @param useEncryption whether to use TLS encryption.
     * @return this instance.
     */
    public Builder withEncryption(final boolean useEncryption) {
      this.config.useEncryption = useEncryption;
      return this;
    }

    /**
     * Sets whether to disable the certificate verification in this handler.
     *
     * @param disableCertificateVerification whether to disable certificate verification.
     * @return this instance.
     */
    public Builder withDisableCertificateVerification(final boolean disableCertificateVerification) {
      this.config.disableCertificateVerification = disableCertificateVerification;
      return this;
    }

    /**
     * Sets whether to use the certificates from the operating system.
     *
     * @param useSystemTrustStore whether to use the system operating certificates.
     * @return this instance.
     */
    public Builder withSystemTrustStore(final boolean useSystemTrustStore) {
      this.config.useSystemTrustStore = useSystemTrustStore;
      return this;
    }

    /**
     * Sets the token used in the token authetication.
     * @param token the token value.
     * @return      this builder instance.
     */
    public Builder withToken(final String token) {
      this.config.token = token;
      return this;
    }

    /**
     * Sets the {@link BufferAllocator} to use in this handler.
     *
     * @param allocator the allocator.
     * @return this instance.
     */
    public Builder withBufferAllocator(final BufferAllocator allocator) {
      this.config.allocator = allocator
          .newChildAllocator("ArrowFlightSqlClientHandler", 0, allocator.getLimit());
      return this;
    }

    /**
     * Adds the provided {@code factories} to the list of middlewareFactories of this handler.
     *
     * @param factories the factories to add.
     * @return this instance.
     */
    public Builder withMiddlewareFactories(final FlightClientMiddleware.Factory... factories) {
      return withMiddlewareFactories(Arrays.asList(factories));
    }

    /**
     * Adds the provided {@code factories} to the list of middlewareFactories of this handler.
     *
     * @param factories the factories to add.
     * @return this instance.
     */
    public Builder withMiddlewareFactories(
        final Collection<FlightClientMiddleware.Factory> factories) {
      this.config.middlewareFactories.addAll(factories);
      return this;
    }

    /**
     * Adds the provided {@link CallOption}s to this handler.
     *
     * @param options the options
     * @return this instance.
     */
    public Builder withCallOptions(final CallOption... options) {
      return withCallOptions(Arrays.asList(options));
    }

    /**
     * Adds the provided {@link CallOption}s to this handler.
     *
     * @param options the options
     * @return this instance.
     */
    public Builder withCallOptions(final Collection<CallOption> options) {
      this.config.options.addAll(options);
      return this;
    }

    /**
     * Builds a new {@link ArrowFlightSqlClientHandler} from the provided fields.
     *
     * @return a new client handler.
     * @throws SQLException on error.
     */
    public ArrowFlightSqlClientHandler build() throws SQLException {
      ConnectionManager conMan = new ConnectionManager(config);
      return ArrowFlightSqlClientHandler.createNewHandler(conMan, config.options);
    }
  }
}
