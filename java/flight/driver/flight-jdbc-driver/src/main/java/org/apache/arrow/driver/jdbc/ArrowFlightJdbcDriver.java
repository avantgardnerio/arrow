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

package org.apache.arrow.driver.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Properties;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.apache.arrow.util.Preconditions;
import org.apache.calcite.avatica.AvaticaConnection;
import org.apache.calcite.avatica.DriverVersion;
import org.apache.calcite.avatica.Meta;
import org.apache.calcite.avatica.UnregisteredDriver;

/**
 * JDBC driver for querying data from an Apache Arrow Flight server.
 */
public class ArrowFlightJdbcDriver extends UnregisteredDriver {

  private static final String CONNECT_STRING_PREFIX = "jdbc:arrow-flight://";

  static {
    (new ArrowFlightJdbcDriver()).register();
  }

  @Override
  public Connection connect(String url, Properties info) throws SQLException {

    try {
      String[] args = getUrlsArgs(Preconditions.checkNotNull(url));

      addToProperties(info, args);

      return new ArrowFlightConnection(this, factory, url, info);
    } catch (Throwable e) {
      throw new SQLException("Failed to connect: " + e.getMessage());
    }
  }

  @Override
  protected DriverVersion createDriverVersion() {
    return Version.CURRENT.getDriverVersion();
  }

  @Override
  public Meta createMeta(AvaticaConnection connection) {
    return new ArrowFlightMetaImpl(connection);
  }

  @Override
  protected String getConnectStringPrefix() {
    return CONNECT_STRING_PREFIX;
  }

  @Override
  public boolean acceptsURL(String url) throws SQLException {
    return Preconditions.checkNotNull(url).startsWith(CONNECT_STRING_PREFIX);
  }

  /**
   * Parses the provided url based on the format this driver accepts, retrieving
   * arguments after the {@link #CONNECT_STRING_PREFIX}.
   *
   * @param url
   *          The url to parse.
   * @return the parsed arguments.
   * @throws SQLException
   *           If an error occurs while trying to parse the URL.
   */
  private String[] getUrlsArgs(String url) throws SQLException {
    // URL must ALWAYS start with "jdbc:arrow-flight://"
    assert acceptsURL(url);

    /*
     * Granted the URL format will always be
     * "jdbc:arrow-flight://<host>:<port>[/<catalog>]," it should be safe to
     * split the URL arguments "host," "port[/catalog]" by the colon in between.
     */
    Deque<String> args = Arrays
        .stream(url.substring(getConnectStringPrefix().length()).split(":"))
        .collect(Collectors.toCollection(ArrayDeque::new));

    /*
     * If "catalog" is present in the provided URL, it should be alongside the
     * port. The following lines separate "port" from "catalog," replacing the
     * last index of the ArrayDeque of arguments with two new values: "port" and
     * "catalog," separated from each other.
     */
    String portAndCatalog = args.getLast().trim();
    args.removeLast();

    int indexOfSeparator = portAndCatalog.indexOf('/');
    boolean hasCatalog = indexOfSeparator != -1;

    /*
     * Separates "port" and "catalog" in the provided URL. The reason for using
     * a label is to make the code more readable, preventing an else clause.
     */
    SeparatePortFromCatalog: {

      if (hasCatalog) {
        // Adds "port" and "catalog" to the ArrayDeque of URL arguments.
        args.offer(portAndCatalog.substring(0, indexOfSeparator));
        args.offer(portAndCatalog.substring(indexOfSeparator));
        break SeparatePortFromCatalog;
      }

      // If execution reaches this line, the catalog doesn't exist.
      args.offer(portAndCatalog);
    }

    // Returning the arguments.
    return args.toArray(new String[args.size()]);
  }

  private static void addToProperties(Properties info, String... args) {
    String host = (String) args[0];
    int port = Integer.parseInt(args[1]);

    @Nullable
    String catalog = args.length >= 3 ? args[2] : null;

    Preconditions.checkNotNull(info).put("host", host);
    info.put("port", port);

    if (catalog != null) {
      Preconditions.checkArgument(!catalog.trim().equals(""),
          "When provided, catalog cannot be blank!");
      info.put("catalog", catalog);
    }
  }

  /**
   * Enum representation of this driver's version.
   */
  public enum Version {
    // TODO Double-check this.
    CURRENT(new DriverVersion("Arrow Flight JDBC Driver", "0.0.1-SNAPSHOT",
        "Arrow Flight", "0.0.1-SNAPSHOT", true, 0, 1, 0, 1));

    private final DriverVersion driverVersion;

    private Version(DriverVersion driverVersion) {
      this.driverVersion = Preconditions.checkNotNull(driverVersion);
    }

    public final DriverVersion getDriverVersion() {
      return driverVersion;
    }
  }
}
