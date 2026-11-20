package vn.ktd.taskhub.server.database;

import java.io.Console;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Properties;

public final class DatabaseCheck {
    public static void main(String[] args) throws IOException, SQLException {
        Properties config = new Properties();
        try (var input = args.length > 0 ? Files.newInputStream(Path.of(args[0]))
                : DatabaseCheck.class.getResourceAsStream("/server.properties.example")) {
            if (input == null) throw new IOException("Không tìm thấy cấu hình server.");
            config.load(input);
        }
        Console terminal = System.console();
        if (terminal == null) throw new IOException("Hãy chạy trong Terminal để nhập mật khẩu.");
        char[] password = terminal.readPassword("MySQL password (%s): ", config.getProperty("db.user"));
        if (password == null) return;
        try (var connection = DriverManager.getConnection(config.getProperty("db.url"),
                     config.getProperty("db.user"), new String(password));
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT DATABASE(), COUNT(*) FROM users")) {
            rows.next();
            System.out.printf("MySQL OK | %s | users = %d%n", rows.getString(1), rows.getLong(2));
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
