package vn.ktd.taskhub.server.security;

import java.sql.*;
import java.security.*;
import java.util.*;

public final class SocketSession {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final String socketId = UUID.randomUUID().toString();
    private final String ip;
    private final Properties config;
    private final String dbPassword;
    private Long userId;

    public SocketSession(Properties config, String dbPassword, String ip) {
        this.config = config;
        this.dbPassword = dbPassword;
        this.ip = ip;
    }

    public long authenticatedUser() { return java.util.Objects.requireNonNull(userId); }

    public boolean active() { return userId != null; }

    public String socketId() { return socketId; }
    public String ip() { return ip; }
    public void activate(long user) { userId = user; }

    public String insert(Connection db, long user) throws SQLException {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String token = HexFormat.of().formatHex(secret);
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        try (var insert = db.prepareStatement("""
                INSERT INTO auth_sessions(user_id, socket_id, ip_address, token_hash, expires_at, last_activity_at)
                VALUES (?, ?, ?, ?, NOW(3) + INTERVAL 12 HOUR, NOW(3))
                """)) {
            insert.setLong(1, user);
            insert.setString(2, socketId);
            insert.setString(3, ip);
            insert.setString(4, hash);
            insert.executeUpdate();
        }
        return token;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(config.getProperty("db.url"), config.getProperty("db.user"), dbPassword);
    }

    public boolean touch(boolean heartbeat) throws SQLException {
        String column = heartbeat ? "last_heartbeat_at" : "last_activity_at";
        try (var db = connect(); var update = db.prepareStatement("UPDATE auth_sessions SET " + column + """
                 = NOW(3) WHERE socket_id=? AND closed_at IS NULL AND expires_at > NOW(3)
                AND last_activity_at > NOW(3) - INTERVAL 30 MINUTE
                AND last_heartbeat_at > NOW(3) - INTERVAL 90 SECOND
                """)) {
            update.setString(1, socketId);
            return update.executeUpdate() == 1;
        }
    }

    public void close(String reason) throws SQLException {
        if (!active()) return;
        try (var db = connect()) {
            db.setAutoCommit(false);
            try (var update = db.prepareStatement("""
                    UPDATE auth_sessions SET closed_at=NOW(3),close_reason=? WHERE socket_id=? AND closed_at IS NULL
                    """);
                 var audit = db.prepareStatement("""
                    INSERT INTO audit_logs(user_id,action,socket_id,ip_address,result) VALUES (?,?,?,?,'success')
                    """)) {
                update.setString(1, reason);
                update.setString(2, socketId);
                if (update.executeUpdate() == 1) {
                    audit.setLong(1, userId);
                    audit.setString(2, reason);
                    audit.setString(3, socketId);
                    audit.setString(4, ip);
                    audit.executeUpdate();
                }
                db.commit();
                userId = null;
            } catch (SQLException ex) { db.rollback(); throw ex; }
        }
    }
}


