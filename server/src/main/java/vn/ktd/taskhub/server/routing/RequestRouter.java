package vn.ktd.taskhub.server.routing;

import com.google.gson.*;
import java.util.Map;
import java.util.Properties;
import java.sql.*;
import org.mindrot.jbcrypt.BCrypt;
import java.util.function.Function;

public final class RequestRouter {
    private final Map<String, Function<JsonObject, JsonObject>> handlers = Map.of("PING", data -> {
        JsonObject result = new JsonObject();
        result.addProperty("message", "PONG");
        return result;
    });

    private final Properties config;
    private final String dbPassword;
    private Long userId;
    public RequestRouter(Properties config, String dbPassword) {
        this.config = config;
        this.dbPassword = dbPassword;
    }

    private String login(JsonObject data, JsonObject response) {
        if (userId != null) return "ALREADY_LOGGED_IN";
        String student = field(data, "studentId");
        String password = field(data, "password");
        if (student.isBlank() || password.isEmpty()) return "BAD_REQUEST";
        try (var db = DriverManager.getConnection(config.getProperty("db.url"), config.getProperty("db.user"), dbPassword);
             var query = db.prepareStatement("SELECT user_id, full_name, password_hash, account_status FROM users WHERE student_id=?")) {
            query.setString(1, student);
            try (var rows = query.executeQuery()) {
                boolean found = rows.next();
                String hash = found ? rows.getString("password_hash") : "$2a$10$0xXsFHYs6hQ.yHs5ZC9v4ermyXAZZL0dUEuNpgNfmY1KU1clfsItK";
                if (!BCrypt.checkpw(password, hash) || !found || !"active".equals(rows.getString("account_status")))
                    return "LOGIN_FAILED";
                userId = rows.getLong("user_id");
                var profile = new JsonObject();
                profile.addProperty("userId", userId);
                profile.addProperty("fullName", rows.getString("full_name"));
                response.add("data", profile);
                return null;
            }
        } catch (SQLException | IllegalArgumentException e) {
            return "SERVER_ERROR";
        }
    }

    private static String field(JsonObject obj, String name) {
        JsonElement value = obj.get(name);
        return value instanceof JsonPrimitive p && p.isString() ? p.getAsString() : "";
    }

    public JsonObject route(JsonObject request) {
        String id = field(request, "requestId");
        String action = field(request, "action");
        JsonObject response = new JsonObject();
        response.addProperty("type", "response");
        response.addProperty("requestId", id);
        String error = null;
        if (!"request".equals(field(request, "type")) || id.isBlank() || action.isBlank()
                || !(request.get("data") instanceof JsonObject)) error = "BAD_REQUEST";
        else if (action.equals("LOGIN") && dbPassword != null) error = login(request.getAsJsonObject("data"), response);
        else if (!handlers.containsKey(action)) error = "UNKNOWN_ACTION";
        else response.add("data", handlers.get(action).apply(request.getAsJsonObject("data")));
        response.addProperty("ok", error == null);
        if (error != null) response.addProperty("error", error);
        return response;
    }
}

