/*
 * Sonar, open source software quality management tool.
 * Copyright (C) 2008-2012 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * Sonar is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * Sonar is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Sonar; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.core.persistence;

import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.dbcp.BasicDataSource;
import org.apache.commons.dbcp.BasicDataSourceFactory;
import org.apache.commons.lang.StringUtils;
import org.hibernate.cfg.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.CoreProperties;
import org.sonar.api.config.Settings;
import org.sonar.api.database.DatabaseProperties;
import org.sonar.core.persistence.dialect.Dialect;
import org.sonar.core.persistence.dialect.DialectUtils;
import org.sonar.core.persistence.dialect.H2;
import org.sonar.core.persistence.dialect.Oracle;
import org.sonar.core.persistence.dialect.PostgreSql;
import org.sonar.jpa.session.CustomHibernateConnectionProvider;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * @since 2.12
 */
public class DefaultDatabase implements Database {

  private static final Logger LOG = LoggerFactory.getLogger(Database.class);

  private static final String DEFAULT_URL = "jdbc:h2:tcp://localhost/sonar";

  private Settings settings;
  private BasicDataSource datasource;
  private Dialect dialect;
  private Properties properties;

  public DefaultDatabase(Settings settings) {
    this.settings = settings;
  }

  public final DefaultDatabase start() {
    try {
      initSettings();
      initDatasource();
      checkConnection();
      doAfterStart();
      return this;

    } catch (Exception e) {
      throw new IllegalStateException("Fail to connect to database", e);
    }
  }

  /**
   * Override to execute post-startup code.
   */
  protected void doAfterStart() {

  }

  @VisibleForTesting
  void initSettings() {
    initProperties();
    initDialect();
    initSchema();
  }

  private void initProperties() {
    properties = new Properties();
    completeProperties(settings, properties, "sonar.jdbc.");
    completeProperties(settings, properties, "sonar.hibernate.");
    completeDefaultProperties(properties);
    doCompleteProperties(properties);
  }

  private void initDialect() {
    dialect = DialectUtils.find(properties.getProperty("sonar.jdbc.dialect"), properties.getProperty("sonar.jdbc.url"));
    if (dialect == null) {
      throw new IllegalStateException("Can not guess the JDBC dialect. Please check the property sonar.jdbc.url.");
    }
    if (H2.ID.equals(dialect.getId()) && !settings.getBoolean(CoreProperties.DRY_RUN)) {
      LoggerFactory.getLogger(DefaultDatabase.class).warn("H2 database should be used for evaluation purpose only");
    }
    if (!properties.containsKey("sonar.jdbc.driverClassName")) {
      properties.setProperty("sonar.jdbc.driverClassName", dialect.getDefaultDriverClassName());
    }
  }

  private void initSchema() {
    String deprecatedSchema = null;
    if (PostgreSql.ID.equals(dialect.getId())) {
      // this property has been deprecated in version 2.13
      deprecatedSchema = getSchemaPropertyValue(properties, "sonar.jdbc.postgreSearchPath");

    } else if (Oracle.ID.equals(dialect.getId())) {
      // this property has been deprecated in version 2.13
      deprecatedSchema = getSchemaPropertyValue(properties, "sonar.hibernate.default_schema");
    }
    if (StringUtils.isNotBlank(deprecatedSchema)) {
      properties.setProperty("sonar.jdbc.schema", deprecatedSchema);
    }
  }

  private void initDatasource() throws Exception {// NOSONAR this exception is thrown by BasicDataSourceFactory
    // but it's correctly caught by start()
    LOG.info("Create JDBC datasource for " + properties.getProperty(DatabaseProperties.PROP_URL, DEFAULT_URL));
    datasource = (BasicDataSource) BasicDataSourceFactory.createDataSource(extractCommonsDbcpProperties(properties));
    datasource.setConnectionInitSqls(dialect.getConnectionInitStatements(getSchema()));
    datasource.setValidationQuery(dialect.getValidationQuery());
  }

  private void checkConnection() {
    Connection connection = null;
    try {
      LOG.debug("Testing JDBC connection");
      connection = datasource.getConnection();
    } catch (Exception e) {
      LOG.error("Can not connect to database. Please check connectivity and settings (see the properties prefixed by 'sonar.jdbc.').", e);
    } finally {
      DatabaseUtils.closeQuietly(connection);
    }
  }

  public final DefaultDatabase stop() {
    if (datasource != null) {
      try {
        datasource.close();
      } catch (SQLException e) {
        throw new IllegalStateException("Fail to stop JDBC connection pool", e);
      }
    }
    return this;
  }

  public final Dialect getDialect() {
    return dialect;
  }

  public final String getSchema() {
    return properties.getProperty("sonar.jdbc.schema");
  }

  public Properties getHibernateProperties() {
    Properties props = new Properties();

    List<String> hibernateKeys = settings.getKeysStartingWith("sonar.hibernate.");
    for (String hibernateKey : hibernateKeys) {
      props.put(StringUtils.removeStart(hibernateKey, "sonar."), settings.getString(hibernateKey));
    }
    props.put(Environment.DIALECT, getDialect().getHibernateDialectClass().getName());
    props.put("hibernate.generate_statistics", settings.getBoolean(DatabaseProperties.PROP_HIBERNATE_GENERATE_STATISTICS));
    props.put("hibernate.hbm2ddl.auto", "validate");
    props.put(Environment.CONNECTION_PROVIDER, CustomHibernateConnectionProvider.class.getName());

    String schema = getSchema();
    if (StringUtils.isNotBlank(schema)) {
      props.put("hibernate.default_schema", schema);
    }
    return props;
  }

  public final DataSource getDataSource() {
    return datasource;
  }

  public final Properties getProperties() {
    return properties;
  }

  protected void doCompleteProperties(Properties properties) {

  }

  private static void completeProperties(Settings settings, Properties properties, String prefix) {
    List<String> jdbcKeys = settings.getKeysStartingWith(prefix);
    for (String jdbcKey : jdbcKeys) {
      String value = settings.getString(jdbcKey);
      properties.setProperty(jdbcKey, value);
    }
  }

  @VisibleForTesting
  static Properties extractCommonsDbcpProperties(Properties properties) {
    Properties result = new Properties();
    for (Map.Entry<Object, Object> entry : properties.entrySet()) {
      String key = (String) entry.getKey();
      if (StringUtils.startsWith(key, "sonar.jdbc.")) {
        result.setProperty(StringUtils.removeStart(key, "sonar.jdbc."), (String) entry.getValue());
      }
    }

    // This property is required by the Ruby Oracle enhanced adapter.
    // It directly uses the Connection implementation provided by the Oracle driver
    result.setProperty("accessToUnderlyingConnectionAllowed", "true");
    return result;
  }

  private static String getSchemaPropertyValue(Properties props, String deprecatedKey) {
    String value = props.getProperty("sonar.jdbc.schema");
    if (StringUtils.isBlank(value) && deprecatedKey != null) {
      value = props.getProperty(deprecatedKey);
    }
    return StringUtils.isNotBlank(value) ? value : null;
  }

  private static void completeDefaultProperties(Properties props) {
    completeDefaultProperty(props, DatabaseProperties.PROP_DRIVER, props.getProperty(DatabaseProperties.PROP_DRIVER_DEPRECATED));
    completeDefaultProperty(props, DatabaseProperties.PROP_URL, DEFAULT_URL);
    completeDefaultProperty(props, DatabaseProperties.PROP_USER, props.getProperty(DatabaseProperties.PROP_USER_DEPRECATED, DatabaseProperties.PROP_USER_DEFAULT_VALUE));
    completeDefaultProperty(props, DatabaseProperties.PROP_PASSWORD, DatabaseProperties.PROP_PASSWORD_DEFAULT_VALUE);
    completeDefaultProperty(props, DatabaseProperties.PROP_HIBERNATE_HBM2DLL, "validate");
  }

  private static void completeDefaultProperty(Properties props, String key, String defaultValue) {
    if (props.getProperty(key) == null && defaultValue != null) {
      props.setProperty(key, defaultValue);
    }
  }
}
