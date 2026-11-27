package vn.ktd.taskhub.server.routing;

import com.google.gson.*;
import java.util.Map;
import java.util.Properties;
import java.sql.*;
import vn.ktd.taskhub.server.security.LoginService;
import java.util.function.Function;

public final class RequestRouter {
    private final Map<String, Function<JsonObject, JsonObject>> handlers = Map.of("PING", data -> {
        JsonObject result = new JsonObject();
        result.addProperty("message", "PONG");
        return result;
    });

    private final Properties config;
    private final String dbPassword;
    private final vn.ktd.taskhub.server.security.SocketSession session;
    private boolean finished;
    public boolean finished() { return finished; }
    public void close(String reason) throws SQLException { session.close(reason); }
    public RequestRouter(Properties config, String dbPassword, String ip) {
        session = new vn.ktd.taskhub.server.security.SocketSession(config, dbPassword, ip);
        this.config = config;
        this.dbPassword = dbPassword;
    }

    private String login(JsonObject data, JsonObject response) {
        if (session.active()) return "ALREADY_LOGGED_IN";
        String student = field(data, "studentId");
        String password = field(data, "password");
        if (!student.matches("[A-Za-z0-9]{6,12}") || password.isEmpty()) return "BAD_REQUEST";
        try (var db = DriverManager.getConnection(config.getProperty("db.url"), config.getProperty("db.user"), dbPassword)) {
            JsonObject profile = LoginService.authenticate(db, session, student, password);
            if (profile == null) return "LOGIN_FAILED";
            response.add("data", profile);
            if(profile.has("verificationRequired")) return "VERIFY_REQUIRED";
            return null;
        } catch (SQLException | IllegalArgumentException e) {
            return "SERVER_ERROR";
        }
    }

    private static String field(JsonObject obj, String name) {
        JsonElement value = obj.get(name);
        return value instanceof JsonPrimitive p && p.isString() ? p.getAsString() : "";
    }

    public JsonObject route(JsonObject request) throws SQLException {
        String id = field(request, "requestId");
        String action = field(request, "action");
        JsonObject response = new JsonObject();
        response.addProperty("type", "response");
        response.addProperty("requestId", id);
        String error = null;
        if (!"request".equals(field(request, "type")) || id.isBlank() || action.isBlank()
                || !(request.get("data") instanceof JsonObject)) error = "BAD_REQUEST";
        else if (session.active()  && !session.touch(action.equals("HEARTBEAT"))) {
            session.close("SESSION_TIMEOUT");
            finished = true;
            error = "SESSION_EXPIRED";
        }
        else if (action.equals("LOGOUT") || action.equals("HEARTBEAT")) {
            if (!session.active()) error = "UNAUTHENTICATED";
            else {
                if (action.equals("LOGOUT")) { session.close("LOGOUT"); finished = true; }
                response.add("data", new JsonObject());
            }
        }
        else if (action.equals("CHANGE_PASSWORD")) {
            if (!session.active()) error = "UNAUTHENTICATED";
            else {
                var data = request.getAsJsonObject("data");
                try (var db = DriverManager.getConnection(config.getProperty("db.url"), config.getProperty("db.user"), dbPassword)) {
                    error = vn.ktd.taskhub.server.security.PasswordChange.apply(db, session,
                            field(data,"oldPassword"), field(data,"newPassword"), field(data,"confirmation"));
                } catch (SQLException ex) { error = "SERVER_ERROR"; }
                if (error == null) response.add("data", new JsonObject());
                finished = error == null || "SESSION_EXPIRED".equals(error);
            }
        }
        else if (action.equals("LOGIN") && dbPassword != null) error = login(request.getAsJsonObject("data"), response);
        else if (dbPassword != null && action.equals("RESET_REQUEST")) {
            response.add("data",vn.ktd.taskhub.server.feature.account.ResetRequests.enqueue(config,dbPassword,session,request.getAsJsonObject("data")));
        }
        else if (dbPassword != null && java.util.Set.of("REGISTER","OTP_RESEND","VERIFY_OTP","PROFILE_GET","PROFILE_UPDATE","EMAIL_CHANGE","AUDIT_LIST","AUDIT_EXPORT").contains(action)) {
            try (var db = DriverManager.getConnection(config.getProperty("db.url"),config.getProperty("db.user"),dbPassword)) {
                var result = new vn.ktd.taskhub.server.feature.account.AccountActions(db,session,request.getAsJsonObject("data")).execute(action);
                if (result.has("error")) error = result.get("error").getAsString();
                else response.add("data",result);
            } catch (IllegalArgumentException invalid) { error = invalid.getMessage(); }
              catch (SQLIntegrityConstraintViolationException duplicate) { error = "CONFLICT"; }
              catch (SQLException unavailable) { error = "SERVER_ERROR"; }
        }
        else if (!handlers.containsKey(action)) error = "UNKNOWN_ACTION";
        else response.add("data", handlers.get(action).apply(request.getAsJsonObject("data")));
        response.addProperty("ok", error == null);
        if (error != null) response.addProperty("error", error);
        return response;
    }
}







