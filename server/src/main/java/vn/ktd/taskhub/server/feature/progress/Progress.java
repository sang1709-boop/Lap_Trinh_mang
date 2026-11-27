package vn.ktd.taskhub.server.feature.progress;

import com.google.gson.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import vn.ktd.taskhub.server.feature.TeamRequest;
import vn.ktd.taskhub.server.feature.account.Sql;

public final class Progress {
    private Progress() {}
    public static final ZoneOffset ZONE = ZoneOffset.ofHours(7);

    private static final class Counts {
        int waiting, doing, done, cancelled, lateOpen, lateDone;
        double hours, doneHours;
        void count(JsonObject item, LocalDateTime cutoff) {
            String state = item.get("task_status").getAsString();
            switch (state) {
                case "not_started" -> waiting++;
                case "in_progress" -> doing++;
                case "completed" -> done++;
                case "cancelled" -> { cancelled++; return; }
                default -> throw new IllegalStateException(state);
            }
            double estimate = item.get("estimated_hours").getAsDouble();
            hours += estimate;
            if (state.equals("completed")) {
                doneHours += estimate;
                if (flag(item,"late_at_close")) lateDone++;
            } else {
                LocalDateTime reference = waitingReview(item) ? time(item,"submitted_at") : cutoff;
                if (reference.isAfter(time(item,"deadline"))) lateOpen++;
            }
        }
        JsonObject json() {
            var r = new JsonObject();
            int total = waiting + doing + done;
            r.addProperty("total",total);
            r.addProperty("notStarted",waiting);
            r.addProperty("inProgress",doing);
            r.addProperty("completed",done);
            r.addProperty("cancelled",cancelled);
            r.addProperty("lateOpen",lateOpen);
            r.addProperty("lateCompleted",lateDone);
            r.addProperty("totalHours",hours);
            r.addProperty("completedHours",doneHours);
            r.addProperty("countPct",total == 0 ? null : 100.0 * done / total);
            r.addProperty("hoursPct",hours == 0 ? null : 100.0 * doneHours / hours);
            return r;
        }
    }

    static boolean flag(JsonObject r, String key) {
        var v = r.get(key);
        return !v.isJsonNull() && ("1".equals(v.getAsString()) || "true".equalsIgnoreCase(v.getAsString()));
    }
    static boolean waitingReview(JsonObject r) {
        return !r.get("review_state").isJsonNull() && Set.of("pending","approved").contains(r.get("review_state").getAsString());
    }
    static LocalDateTime time(JsonObject r,String key) {
        return LocalDateTime.parse(r.get(key).getAsString().replace(' ','T'));
    }
    public static String threshold(LocalDateTime now, LocalDateTime due) {
        if (now.isAfter(due)) return "overdue";
        if (due.isBefore(now.plusHours(24))) return "soon";
        if (!due.isAfter(now.plusHours(72))) return "warning";
        return "safe";
    }

    public static JsonObject execute(TeamRequest q,String action) throws SQLException {
        boolean personal = action.equals("PROGRESS_MINE");
        boolean report = action.startsWith("REPORT_");
        long group = q.id("groupId");
        q.role(group,!personal);
        Long person = personal ? Long.valueOf(q.user()) : q.field("memberId").isEmpty() ? null : Long.valueOf(q.id("memberId"));
        LocalDateTime now = LocalDateTime.now(ZONE), cutoff = now, from = null, end = null;
        if (report) {
            from = q.date("from").atStartOfDay();
            end = q.date("to").plusDays(1).atStartOfDay();
            if (!end.isAfter(from) || from.isAfter(now)) throw TeamRequest.invalid();
            if (!end.isAfter(now)) cutoff = end.minusNanos(1_000_000);
        }
        boolean historical = end != null && !end.isAfter(now);
        String sql = (historical ? TaskState.HISTORY : TaskState.LIVE) + TaskState.TREE + """
                SELECT i.*,u.full_name FROM items i LEFT JOIN users u ON u.user_id=i.owner
                WHERE i.group_id=? AND (i.leaf=1 OR i.ready=1) AND (? IS NULL OR i.owner=?)
                ORDER BY i.deadline,i.task_id
                """;
        Object[] args = historical ? new Object[]{group,end,group,person,person} : new Object[]{group,person,person};
        if (historical && !Sql.rows(q.db(),"""
                SELECT 1 FROM tasks t WHERE t.group_id=? AND t.created_at<? AND NOT EXISTS
                (SELECT 1 FROM task_revisions r WHERE r.task_id=t.task_id AND r.effective_at<?) LIMIT 1
                """,group,end,end).isEmpty()) throw new IllegalArgumentException("HISTORY_INCOMPLETE");
        var members = Sql.rows(q.db(),"""
                SELECT DISTINCT m.user_id AS userId,u.full_name AS name FROM memberships m JOIN users u USING(user_id)
                WHERE group_id=? AND joined_at<=? AND (ended_at IS NULL OR ended_at>?)
                  AND (? IS NULL OR m.user_id=?) ORDER BY m.user_id
                """, group,cutoff,cutoff,person,person);
        var counts = new LinkedHashMap<Long, Counts>();
        var names = new LinkedHashMap<Long,String>();
        var active = new HashSet<Long>();
        for (var e:members) {
            var m=e.getAsJsonObject(); long id=m.get("userId").getAsLong();
            counts.put(id,new Counts()); names.put(id,m.get("name").getAsString()); active.add(id);
        }
        var tasks=new JsonArray(); var parents=new JsonArray(); var total=new Counts();
        int safe=0, warning=0, danger=0, review=0, lateParents=0;
        for (var entry:Sql.rows(q.db(),sql,args)) {
            var r=entry.getAsJsonObject();
            if (r.get("owner").isJsonNull()) continue;
            long owner=r.get("owner").getAsLong();
            var due=time(r,"deadline");
            if (flag(r,"ready")) {
                r.addProperty("overdue",cutoff.isAfter(due));
                parents.add(r); if (cutoff.isAfter(due)) lateParents++;
                continue;
            }
            String status=r.get("task_status").getAsString();
            if (report && status.equals("completed") && !r.get("closed_at").isJsonNull() && time(r,"closed_at").isBefore(from)) continue;
            if (status.equals("in_progress") || status.equals("not_started")) {
                if (waitingReview(r)) review++;
                else {
                    String level=threshold(cutoff,due);
                    r.addProperty("threshold",level);
                    switch(level) { case "safe" -> safe++; case "warning" -> warning++; default -> danger++; }
                }
            }
            tasks.add(r); total.count(r,cutoff);
            counts.computeIfAbsent(owner,id->new Counts()).count(r,cutoff);
            names.put(owner,r.get("full_name").getAsString());
        }
        var people=new JsonArray();
        counts.forEach((id,c)->{
            var row=c.json(); row.addProperty("userId",id); row.addProperty("name",names.get(id));
            row.addProperty("leftGroup",!active.contains(id)); people.add(row);
        });
        var result=TeamRequest.result("summary",total.json());
        result.addProperty("asOf",cutoff.toString());
        result.add("members",people); result.add("tasks",tasks); result.add("parentsToClose",parents);
        var alerts=new JsonObject();
        alerts.addProperty("safe",safe); alerts.addProperty("warning",warning); alerts.addProperty("danger",danger);
        alerts.addProperty("pendingOrApproved",review); alerts.addProperty("overdueParents",lateParents);
        result.add("alerts",alerts);
        return result;
    }
}
