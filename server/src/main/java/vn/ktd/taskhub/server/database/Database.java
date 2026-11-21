package vn.ktd.taskhub.server.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public final class Database {
    @FunctionalInterface public interface Work<T> { T run(Connection connection) throws Exception; }

    private final String url;
    private final String user;
    private final String password;

    public Database(Properties config, String password) {
        this.url = config.getProperty("db.url");
        this.user = config.getProperty("db.user");
        this.password = password;
    }

    public Connection open() throws SQLException {
        if (password == null) throw new SQLException("Database password is not available");
        return DriverManager.getConnection(url, user, password);
    }

    public <T> T read(Work<T> work) throws Exception {
        try (Connection connection = open()) { return work.run(connection); }
    }

    public <T> T transaction(Work<T> work) throws Exception {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (Exception error) {
                try { connection.rollback(); } catch (SQLException ignored) {}
                throw error;
            } finally {
                try { connection.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        }
    }
}
