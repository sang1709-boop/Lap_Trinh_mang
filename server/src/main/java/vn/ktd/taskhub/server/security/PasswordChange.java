package vn.ktd.taskhub.server.security;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.Locale;
import org.mindrot.jbcrypt.BCrypt;

public final class PasswordChange {
    private PasswordChange() {}

    public static String apply(Connection db, SocketSession session, String oldPassword,
                               String newPassword, String confirmation) throws SQLException {
        db.setAutoCommit(false);
        try {
            long user = session.authenticatedUser();
            String student, hash, email;
            try (var q = db.prepareStatement("SELECT student_id,password_hash,email FROM users WHERE user_id=? FOR UPDATE")) {
                q.setLong(1, user);
                try (var rows = q.executeQuery()) {
                    if (!rows.next()) throw new SQLException("Missing session owner");
                    student = rows.getString(1);
                    hash = rows.getString(2);
                    email = rows.getString(3);
                }
            }
            try (var q = db.prepareStatement("""
                    SELECT session_id FROM auth_sessions WHERE socket_id=? AND user_id=? AND closed_at IS NULL
                    AND expires_at>NOW(3) AND last_activity_at>NOW(3)-INTERVAL 30 MINUTE
                    AND last_heartbeat_at>NOW(3)-INTERVAL 90 SECOND FOR UPDATE
                    """)) {
                q.setString(1, session.socketId());
                q.setLong(2, user);
                try (var rows = q.executeQuery()) {
                    if (!rows.next()) { db.rollback(); return "SESSION_EXPIRED"; }
                }
            }
            String error = null;
            if (!BCrypt.checkpw(oldPassword, hash)) error = "OLD_PASSWORD_INCORRECT";
            else if (!newPassword.equals(confirmation)) error = "CONFIRMATION_MISMATCH";
            else if (oldPassword.equals(newPassword)) error = "PASSWORD_UNCHANGED";
            else if (!valid(newPassword, student)) error = "WEAK_PASSWORD";

            if (error == null) {
                vn.ktd.taskhub.server.feature.account.Sql.update(db,"UPDATE otp_challenges SET revoked_at=NOW(3) WHERE user_id=? AND consumed_at IS NULL AND revoked_at IS NULL",user);
                try (var q = db.prepareStatement("UPDATE users SET password_hash=? WHERE user_id=?")) {
                    q.setString(1, BCrypt.hashpw(newPassword, BCrypt.gensalt()));
                    q.setLong(2, user);
                    q.executeUpdate();
                }
                try (var q = db.prepareStatement("""
                        UPDATE auth_sessions SET closed_at=NOW(3),close_reason='PASSWORD_CHANGE'
                        WHERE user_id=? AND closed_at IS NULL
                        """)) {
                    q.setLong(1, user);
                    q.executeUpdate();
                }
            }
            try (var q = db.prepareStatement("""
                    INSERT INTO audit_logs(user_id,action,ip_address,socket_id,result,reason,old_value,new_value)
                    VALUES (?,?,?,?,?,?,JSON_QUOTE(?),JSON_QUOTE(?))
                    """)) {
                q.setLong(1, user);
                q.setString(2, error == null ? "PASSWORD_CHANGE" : "PASSWORD_CHANGE_FAIL");
                q.setString(3, session.ip());
                q.setString(4, session.socketId());
                q.setString(5, error == null ? "success" : "failed");
                q.setString(6, error);
                q.setString(7, error == null ? "đã thay đổi" : null);
                q.setString(8, error == null ? "đã thay đổi" : null);
                q.executeUpdate();
            }
            db.commit();
            if (error == null) vn.ktd.taskhub.server.feature.account.Mail.send(email,"Mật khẩu tài khoản KTD của bạn đã thay đổi.");
            return error;
        } catch (SQLException | RuntimeException ex) { db.rollback(); throw ex; }
    }

    public static boolean valid(String value, String student) {
        return vn.ktd.taskhub.shared.dto.AccountRules.password(value,student);
    }
}
