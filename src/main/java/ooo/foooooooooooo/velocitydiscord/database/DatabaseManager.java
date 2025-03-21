package ooo.foooooooooooo.velocitydiscord.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import ooo.foooooooooooo.velocitydiscord.VelocityDiscord;
import ooo.foooooooooooo.velocitydiscord.config.BotConfig;

import java.sql.*;
import java.util.concurrent.CompletableFuture;

public class DatabaseManager {
  private final HikariDataSource dataSource;

  public DatabaseManager(BotConfig config) {
    // Validate config during initialization
    String dbUrl = VelocityDiscord.CONFIG.bot.DB_URL;
    String dbUser = VelocityDiscord.CONFIG.bot.DB_USER;
    String dbPassword = VelocityDiscord.CONFIG.bot.DB_PASSWORD;

    if (dbUrl == null || dbUser == null || dbPassword == null) {
      throw new IllegalStateException("Database credentials not configured in config.toml");
    }

    // Ensure the URL uses the correct prefix for MariaDB
    if (!dbUrl.startsWith("jdbc:mariadb://")) {
      VelocityDiscord.LOGGER.warn("Database URL should start with 'jdbc:mariadb://' for MariaDB. Current URL: {}", dbUrl);
    }

    HikariConfig hikariConfig = new HikariConfig();
    hikariConfig.setJdbcUrl(dbUrl);
    hikariConfig.setUsername(dbUser);
    hikariConfig.setPassword(dbPassword);
    hikariConfig.setDriverClassName("ooo.foooooooooooo.velocitydiscord.lib.org.mariadb.jdbc.Driver");
    hikariConfig.setMaximumPoolSize(5);
    hikariConfig.setMinimumIdle(1);
    hikariConfig.setConnectionTimeout(30000); // 30 seconds
    hikariConfig.setIdleTimeout(600000); // 10 minutes
    hikariConfig.setMaxLifetime(1800000); // 30 minutes

    try {
      this.dataSource = new HikariDataSource(hikariConfig);
      VelocityDiscord.LOGGER.info("DatabaseManager initialized successfully");

      // Explicitly call createTables to set up the database schema, otherwise no data can be saved
      createTables().join();
      verifyTables();
    } catch (Exception e) {
      VelocityDiscord.LOGGER.error("Failed to initialize DatabaseManager: {}", e.getMessage());
      throw new RuntimeException("Cannot initialize database manager", e);
    }
  }

  private CompletableFuture<Void> createTables() {
    return CompletableFuture.runAsync(() -> {
      try (Connection conn = dataSource.getConnection();
           Statement stmt = conn.createStatement()) {
        // Create SchemaVersion table to track migrations
        stmt.executeUpdate(
          "CREATE TABLE IF NOT EXISTS SchemaVersion (" +
            "version INT PRIMARY KEY)"
        );

        // Check the current schema version
        ResultSet rs = stmt.executeQuery("SELECT version FROM SchemaVersion LIMIT 1");
        int currentVersion = 0;
        if (rs.next()) {
          currentVersion = rs.getInt("version");
        }

        // Initial schema (version 1)
        if (currentVersion < 1) {
          stmt.executeUpdate(
            "CREATE TABLE IF NOT EXISTS PendingLinks (" +
              "discord_id VARCHAR(32) PRIMARY KEY, " +
              "code VARCHAR(6) NOT NULL, " +
              "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)"
          );
          stmt.executeUpdate(
            "CREATE TABLE IF NOT EXISTS LinkedAccounts (" +
              "uuid VARCHAR(36) PRIMARY KEY, " +
              "discord_id VARCHAR(32) NOT NULL)"
          );
          stmt.executeUpdate(
            "CREATE TABLE IF NOT EXISTS VerifiedUsers (" +
              "discord_id VARCHAR(32) PRIMARY KEY, " +
              "verified TINYINT(1) NOT NULL DEFAULT 0)"
          );
          stmt.executeUpdate("INSERT INTO SchemaVersion (version) VALUES (1) ON DUPLICATE KEY UPDATE version = 1");
          VelocityDiscord.LOGGER.info("Applied schema version 1");
        }

        // Add future migrations here
        // Example: if (currentVersion < 2) { ... }

        VelocityDiscord.LOGGER.info("Database tables created or verified successfully");
      } catch (SQLException e) {
        VelocityDiscord.LOGGER.error("Failed to create database tables: {}", e.getMessage());
        throw new RuntimeException("Failed to create database tables", e);
      }
    });
  }

  private void verifyTables() throws SQLException {
    try (Connection conn = dataSource.getConnection()) {
      DatabaseMetaData metaData = conn.getMetaData();

      // Verify PendingLinks table
      verifyTableSchema(metaData, "PendingLinks", new String[]{
        "discord_id", "code", "created_at"
      });

      // Verify LinkedAccounts table
      verifyTableSchema(metaData, "LinkedAccounts", new String[]{
        "uuid", "discord_id"
      });

      // Verify VerifiedUsers table
      verifyTableSchema(metaData, "VerifiedUsers", new String[]{
        "discord_id", "verified"
      });

      // Verify SchemaVersion table
      verifyTableSchema(metaData, "SchemaVersion", new String[]{
        "version"
      });

      VelocityDiscord.LOGGER.info("Database schema verification completed successfully");
    } catch (SQLException e) {
      VelocityDiscord.LOGGER.error("Failed to verify database schema: {}", e.getMessage());
      throw e;
    }
  }

  private void verifyTableSchema(DatabaseMetaData metaData, String tableName, String[] expectedColumns) throws SQLException {
    ResultSet columns = metaData.getColumns(null, null, tableName, null);
    boolean tableExists = false;
    boolean[] columnFound = new boolean[expectedColumns.length];

    while (columns.next()) {
      tableExists = true;
      String columnName = columns.getString("COLUMN_NAME");
      for (int i = 0; i < expectedColumns.length; i++) {
        if (expectedColumns[i].equalsIgnoreCase(columnName)) {
          columnFound[i] = true;
        }
      }
    }

    if (!tableExists) {
      throw new SQLException("Table " + tableName + " does not exist after creation");
    }

    for (int i = 0; i < expectedColumns.length; i++) {
      if (!columnFound[i]) {
        VelocityDiscord.LOGGER.error("Table {} is missing required column: {}", tableName, expectedColumns[i]);
        throw new SQLException("Table " + tableName + " is missing required column: " + expectedColumns[i]);
      }
    }
  }
  public Connection getConnection() throws SQLException {
    try {
      Connection conn = dataSource.getConnection();
      VelocityDiscord.LOGGER.debug("Obtained database connection from pool");
      return conn;
    } catch (SQLException e) {
      VelocityDiscord.LOGGER.error("Failed to obtain database connection: {}", e.getMessage());
      throw e;
    }
  }

  public void close() {
    if (dataSource != null) {
      dataSource.close();
      VelocityDiscord.LOGGER.info("HikariCP connection pool closed");
    }
  }
}