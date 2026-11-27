package vn.ktd.taskhub.server.feature.workload;

import com.google.gson.*;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import vn.ktd.taskhub.server.feature.TeamRequest;
import vn.ktd.taskhub.server.feature.account.Sql;
import vn.ktd.taskhub.server.feature.audit.AuditTrail;
import vn.ktd.taskhub.server.feature.progress.TaskState;

public final class Workload {
    private Workload() {}

    public static JsonObject execute(TeamRequest q, String action) throws SQLException {
        long group = q.id("groupId");
        boolean view = action.equals("WORKLOAD_VIEW");
        boolean save = action.equals("CAPACITY_SAVE");
        boolean leader = q.role(group, !view && !save).equals("leader");
        String period = q.field("period");
        LocalDate date = q.date("date");
        LocalDate start, end;
        BigDecimal defaultHours;
        switch (period) {
            case "week" -> {
                start = date.minusDays(date.getDayOfWeek().getValue()-1);
                end = start.plusWeeks(1);
                defaultHours = BigDecimal.valueOf(20);
            }
            case "month" -> {
                start = date.withDayOfMonth(1);
                end = start.plusMonths(1);
                defaultHours = BigDecimal.valueOf(20L * start.lengthOfMonth()).divide(BigDecimal.valueOf(7), 2, RoundingMode.HALF_UP);
            }
            default -> throw TeamRequest.invalid();
        }

        if (save) {
            if (!q.field("userId").isEmpty() && q.id("userId") != q.user()) throw new IllegalArgumentException("FORBIDDEN");
            var hours = q.hours("hours");
            var old = Sql.rows(q.db(),"SELECT available_hours AS hours FROM personal_capacity WHERE user_id=? AND period_type=? AND period_start=? FOR UPDATE",
                    q.user(), period, start);
            Sql.update(q.db(), """
                    INSERT INTO personal_capacity(user_id,period_type,period_start,available_hours,updated_at) VALUES (?,?,?,?,NOW(3))
                    ON DUPLICATE KEY UPDATE available_hours=?,updated_at=NOW(3)
                    """, q.user(), period, start, hours, hours);
            var next = new JsonObject();
            next.addProperty("hours", hours);
            next.addProperty("period", period);
            next.addProperty("start", start.toString());
            AuditTrail.append(q.db(),q.session(),q.user(),group,"LOAD_CONFIG_CHANGE","capacity",start.toString(),
                    old.isEmpty()?null:old.get(0).getAsJsonObject(),next,null);
            return next;
        }

        boolean proposal = action.equals("WORKLOAD_SUGGEST") || action.equals("WORKLOAD_SIMULATE");
        LocalDate due = proposal ? q.date("deadline") : date;
        var hours = proposal ? q.hours("hours") : BigDecimal.ZERO;
        var rows = Sql.rows(q.db(), TaskState.LIVE + TaskState.TREE + """
                SELECT m.user_id AS userId,u.full_name AS name,COALESCE(p.available_hours,?) AS capacity,
                    COUNT(i.task_id) AS openTasks,COALESCE(SUM(i.estimated_hours),0) AS usedHours,
                    COUNT(CASE WHEN ABS(DATEDIFF(i.deadline,?))<=1 THEN 1 END) AS nearby
                FROM memberships m JOIN users u USING(user_id)
                LEFT JOIN personal_capacity p ON p.user_id=m.user_id AND p.period_type=? AND p.period_start=?
                LEFT JOIN items i ON i.owner=m.user_id AND i.leaf=1 AND i.task_status IN ('not_started','in_progress')
                    AND i.deadline<?
                WHERE m.group_id=? AND m.membership_status='active' AND (? OR m.user_id=?)
                GROUP BY m.user_id,u.full_name,p.available_hours ORDER BY m.user_id
                """, defaultHours,due,period,start,end,group,leader,q.user());
        var candidates = new ArrayList<JsonObject>();
        for (var entry : rows) {
            var row = entry.getAsJsonObject();
            double used = row.get("usedHours").getAsDouble(), cap = row.get("capacity").getAsDouble();
            double pct = 100 * used / cap;
            row.addProperty("loadPct", pct);
            row.addProperty("color", pct<70 ? "green" : pct<=100 ? "yellow" : "red");
            row.addProperty("remainingHours", cap-used);
            if (proposal) {
                double added = due.isBefore(end) ? hours.doubleValue() : 0;
                row.addProperty("afterHours", used+added);
                row.addProperty("afterPct", (used+added)*100/cap);
                row.addProperty("score", cap-used-added-row.get("nearby").getAsInt()-row.get("openTasks").getAsInt()*0.25);
            }
            candidates.add(row);
        }
        var result = TeamRequest.result("members", rows);
        result.addProperty("periodStart", start.toString());
        result.addProperty("endExclusive", end.toString());
        if (action.equals("WORKLOAD_SIMULATE")) {
            long receiver = q.id("assigneeId");
            var selected = candidates.stream().filter(r->r.get("userId").getAsLong()==receiver).findFirst()
                    .orElseThrow(()->new IllegalArgumentException("INVALID_ASSIGNEE"));
            return TeamRequest.result("simulation", selected);
        }
        if (action.equals("WORKLOAD_SUGGEST")) {
            candidates.sort(Comparator.comparingDouble((JsonObject r)->r.get("score").getAsDouble()).reversed()
                    .thenComparingLong(r->r.get("userId").getAsLong()));
            var ranked = new JsonArray();
            candidates.forEach(ranked::add);
            result = TeamRequest.result("candidates",ranked);
        } else if (leader) {
            double avg = candidates.stream().mapToDouble(r->r.get("loadPct").getAsDouble()).average().orElse(0);
            var moves = new JsonArray();
            for (var source : candidates) {
                double load = source.get("loadPct").getAsDouble();
                if (load<=100 || load<=avg*1.25) continue;
                var receiver = candidates.stream().filter(r->r.get("loadPct").getAsDouble()<Math.min(70,avg*0.5))
                        .min(Comparator.comparingDouble(r->r.get("loadPct").getAsDouble()));
                if (receiver.isEmpty()) continue;
                var tasks = Sql.rows(q.db(),TaskState.LIVE+TaskState.TREE+"""
                        SELECT task_id AS taskId,title,estimated_hours AS hours,DATE(deadline) AS deadline
                        FROM items WHERE group_id=? AND owner=? AND leaf=1 AND task_status='not_started' AND deadline<?
                        ORDER BY deadline,task_id
                        """,group,source.get("userId").getAsLong(),end);
                for (var task:tasks) {
                    var move = task.getAsJsonObject();
                    move.add("fromId",source.get("userId"));
                    move.add("assigneeId",receiver.get().get("userId"));
                    moves.add(move);
                }
            }
            result.add("suggestedMoves",moves);
        }
        return result;
    }
}
