package vn.ktd.taskhub.server.security;

import com.google.gson.JsonObject;
import java.sql.*;
import vn.ktd.taskhub.server.feature.account.Sql;
import vn.ktd.taskhub.server.feature.account.Mail;
import org.mindrot.jbcrypt.BCrypt;

public final class LoginService {
    private static final String DUMMY_HASH = "$2a$10$0xXsFHYs6hQ.yHs5ZC9v4ermyXAZZL0dUEuNpgNfmY1KU1clfsItK";
    private LoginService() {}

    public static JsonObject authenticate(Connection db, SocketSession session, String student,
                                          String password) throws SQLException {
        db.setAutoCommit(false);
        try {
            String ip = session.ip();
            // Serialize concurrent attempts from one IP before reading its rolling count.
            lockSource(db, "#ip", ip);
            boolean limited;
            try (var q = db.prepareStatement("""
                    SELECT COUNT(*) >= 20 FROM security_events WHERE ip_address=INET6_ATON(?)
                      AND kind='login_failed' AND occurred_at > NOW(3) - INTERVAL 15 MINUTE
                    """)) {
                q.setString(1, ip);
                try (var r = q.executeQuery()) { r.next(); limited = r.getBoolean(1); }
            }
            Long user = null;
            String email = null;
            String name = null, hash = DUMMY_HASH, status = "";
            try (var q = db.prepareStatement("SELECT user_id,full_name,password_hash,account_status,email FROM users WHERE student_id=? FOR UPDATE")) {
                q.setString(1, student);
                try (var r = q.executeQuery()) {
                    if (r.next()) {
                        user = r.getLong(1);
                        name = r.getString(2);
                        hash = r.getString(3);
                        status = r.getString(4);
                        email = r.getString(5);
                    }
                }
            }
            int failures = 0;
            boolean locked = false;
            if (!limited) {
                lockSource(db, student, ip);
                try (var q = db.prepareStatement("""
                        SELECT IF(locked_until IS NULL,consecutive_failures,0), locked_until > NOW(3)
                        FROM login_sources WHERE subject_key=? AND source_key=? FOR UPDATE
                        """)) {
                    q.setString(1, student);
                    q.setString(2, ip);
                    try (var r = q.executeQuery()) { r.next(); failures = r.getInt(1); locked = r.getBoolean(2); }
                }
            }
            long pendingId = 0;
            if (user == null && !limited && !locked) {
                Sql.update(db,"UPDATE registration_requests SET closed_at=NOW(3),close_reason='EXPIRED' WHERE student_id=? AND closed_at IS NULL AND expires_at<=NOW(3)",student);
                var pending = Sql.rows(db,"SELECT request_id,password_hash FROM registration_requests WHERE student_id=? AND expires_at>NOW(3) AND closed_at IS NULL ORDER BY request_id DESC",student);
                for(var entry:pending){
                    var row=entry.getAsJsonObject();
                    if(BCrypt.checkpw(password,row.get("password_hash").getAsString())){
                        pendingId=row.get("request_id").getAsLong();break;
                    }
                }
            }
            boolean warn=false;
            String reason = null;
            if (limited) reason = "IP_LIMIT";
            else if (locked) reason = "SOURCE_LOCKED";
            else if (pendingId != 0) reason = "VERIFY_REQUIRED";
            else if (!BCrypt.checkpw(password, hash) || user == null) {
                reason = "INVALID_CREDENTIALS";
                setFailures(db, student, ip, failures + 1);
                if(failures==4 && email!=null){
                    warn=Sql.update(db,"INSERT INTO security_events(subject_key,ip_address,kind,occurred_at) SELECT ?,INET6_ATON(?),'lock_email',NOW(3) WHERE NOT EXISTS(SELECT 1 FROM security_events WHERE subject_key=? AND kind='lock_email' AND occurred_at>NOW(3)-INTERVAL 1 HOUR)",student,ip,student)==1;
                }
                try (var q = db.prepareStatement("""
                        INSERT INTO security_events(subject_key,ip_address,kind,occurred_at)
                        VALUES (?,INET6_ATON(?),'login_failed',NOW(3))
                        """)) {
                    q.setString(1, student);
                    q.setString(2, ip);
                    q.executeUpdate();
                }
            } else if (!"active".equals(status)) reason = "ACCOUNT_UNAVAILABLE";

            try (var q = db.prepareStatement("""
                    INSERT INTO audit_logs(user_id,action,target_type,target_id,ip_address,socket_id,result,reason)
                    VALUES (?,?,'student',?,?,?,?,?)
                    """)) {
                q.setObject(1, user);
                q.setString(2, reason == null ? "LOGIN" : pendingId != 0 ? "VERIFY_REQUIRED" : "LOGIN_FAIL");
                q.setString(3, student);
                q.setString(4, ip);
                q.setString(5, session.socketId());
                q.setString(6, reason == null ? "success" : "failed");
                q.setString(7, reason);
                q.executeUpdate();
            }
            if (reason != null) {
                if(pendingId!=0)setFailures(db,student,ip,0);
                db.commit();
                if(warn)Mail.send(email,"Tài khoản KTD có nhiều lần đăng nhập sai từ cùng một nguồn.");
                if(pendingId!=0){
                    var notice=new JsonObject();
                    notice.addProperty("verificationRequired",true);notice.addProperty("requestId",pendingId);
                    return notice;
                }
                return null;
            }
            setFailures(db, student, ip, 0);
            String token = session.insert(db, user);
            var groups=Sql.rows(db,"SELECT g.group_id,g.name,m.member_role FROM memberships m JOIN student_groups g ON g.group_id=m.group_id WHERE m.user_id=? AND m.membership_status='active' AND g.group_status='active'",user);
            var unread=Sql.rows(db,"SELECT COUNT(*) AS total FROM notifications WHERE user_id=? AND is_read=0",user);
            db.commit();
            session.activate(user);
            var profile = new JsonObject();
            profile.add("groups",groups);
            profile.add("unreadCount",unread.get(0).getAsJsonObject().get("total"));
            profile.addProperty("userId", user);
            profile.addProperty("fullName", name);
            profile.addProperty("sessionToken", token);
            return profile;
        } catch (SQLException | RuntimeException ex) {
            db.rollback();
            throw ex;
        }
    }

    private static void lockSource(Connection db, String key, String ip) throws SQLException {
        try (var q = db.prepareStatement("""
                INSERT INTO login_sources(subject_key,source_key) VALUES (?,?)
                ON DUPLICATE KEY UPDATE consecutive_failures=consecutive_failures
                """)) {
            q.setString(1, key);
            q.setString(2, ip);
            q.executeUpdate();
        }
    }

    private static void setFailures(Connection db, String key, String ip, int count) throws SQLException {
        try (var q = db.prepareStatement("""
                UPDATE login_sources SET consecutive_failures=?,
                  locked_until=IF(? >= 5,NOW(3) + INTERVAL 15 MINUTE,NULL)
                WHERE subject_key=? AND source_key=?
                """)) {
            q.setInt(1, count);
            q.setInt(2, count);
            q.setString(3, key);
            q.setString(4, ip);
            q.executeUpdate();
        }
    }
}

