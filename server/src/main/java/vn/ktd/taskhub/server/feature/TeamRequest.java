package vn.ktd.taskhub.server.feature;

import com.google.gson.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import vn.ktd.taskhub.server.feature.account.Sql;
import vn.ktd.taskhub.server.security.SocketSession;

public record TeamRequest(Connection db, SocketSession session, JsonObject data) {
    public long user() { return session.authenticatedUser(); }

    public String field(String key) {
        var value = data.get(key);
        if (value == null || value.isJsonNull()) return "";
        if (!value.isJsonPrimitive()) throw invalid();
        return value.getAsString().trim();
    }

    public static IllegalArgumentException invalid() { return new IllegalArgumentException("BAD_REQUEST"); }

    public long id(String key) {
        try {
            long value = Long.parseLong(field(key));
            if (value > 0) return value;
        } catch (NumberFormatException ignored) {}
        throw invalid();
    }

    public BigDecimal hours(String key) {
        try {
            var value = new BigDecimal(field(key)).setScale(2, java.math.RoundingMode.UNNECESSARY);
            if (value.signum() > 0 && value.precision() <= 7) return value;
        } catch (NumberFormatException | ArithmeticException ignored) {}
        throw invalid();
    }

    public LocalDate date(String key) {
        try { return LocalDate.parse(field(key)); }
        catch (DateTimeException ex) { throw invalid(); }
    }

    public String role(long group, boolean leaderOnly) throws SQLException {
        var rows = Sql.rows(db, """
                SELECT m.member_role FROM memberships m JOIN student_groups g USING(group_id)
                WHERE group_id=? AND user_id=? AND membership_status='active' AND group_status='active' FOR SHARE
                """, group, user());
        if (rows.isEmpty()) throw new IllegalArgumentException("FORBIDDEN");
        String role = rows.get(0).getAsJsonObject().get("member_role").getAsString();
        if (leaderOnly && !role.equals("leader")) throw new IllegalArgumentException("FORBIDDEN");
        return role;
    }

    public static JsonObject result(String name, JsonElement value) {
        var result = new JsonObject();
        result.add(name, value);
        return result;
    }
}
