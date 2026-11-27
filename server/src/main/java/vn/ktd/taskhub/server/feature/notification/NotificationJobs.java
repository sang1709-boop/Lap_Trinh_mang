package vn.ktd.taskhub.server.feature.notification;

import com.google.gson.*;
import java.io.IOException;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import vn.ktd.taskhub.server.feature.account.Sql;
import vn.ktd.taskhub.server.feature.progress.Progress;
import vn.ktd.taskhub.server.feature.progress.TaskState;

public final class NotificationJobs implements AutoCloseable {
    @FunctionalInterface public interface Sink { void send(JsonObject event) throws IOException; }
    private record Subscriber(long user, Sink sink) {}
    private final ConcurrentMap<String,Subscriber> subscribers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timers = Executors.newScheduledThreadPool(2);
    private final Properties config;
    private final String password;
    public NotificationJobs(Properties config,String password) { this.config=config; this.password=password; }
    public void listen(String socket,long user,Sink sink) { subscribers.put(socket,new Subscriber(user,sink)); }
    public void forget(String socket) { subscribers.remove(socket); }
    private Connection connect() throws SQLException {
        return DriverManager.getConnection(config.getProperty("db.url"),config.getProperty("db.user"),password);
    }
    public void start() {
        timers.scheduleWithFixedDelay(()->run(true),0,30,TimeUnit.MINUTES);
        timers.scheduleWithFixedDelay(()->run(false),0,1,TimeUnit.SECONDS);
    }
    public void close() { timers.shutdownNow(); subscribers.clear(); }
    private void run(boolean deadlines) {
        try (var db=connect()) {
            if (deadlines) scan(db); else deliver(db);
        } catch (SQLException | RuntimeException ex) {
            System.err.println("TV4 "+(deadlines?"deadline":"outbox")+": "+ex.getMessage());
            if (deadlines) try(var audit=connect()) {
                Sql.update(audit,"INSERT INTO audit_logs(action,result,reason) VALUES ('JOB_ERROR','failed','deadline scan')");
            } catch (SQLException failed) { System.err.println(failed.getMessage()); }
        }
    }

    private void deliver(Connection db) throws SQLException {
        db.setAutoCommit(false);
        var events=Sql.rows(db,"""
                SELECT event_key, payload->>'$.userId' AS uid FROM event_outbox
                WHERE channel='notify' AND delivered_at IS NULL AND event_key LIKE 'bell:%'
                ORDER BY created_at,event_key LIMIT 100 FOR UPDATE SKIP LOCKED
                """);
        for (var e:events) {
            var row=e.getAsJsonObject();
            long user=row.get("uid").getAsLong();
            var event=new JsonObject(); event.addProperty("type","event"); event.addProperty("action","INBOX_CHANGED");
            for (var listener:subscribers.entrySet()) {
                var sub=listener.getValue();
                if(sub.user()!=user) continue;
                if(Sql.rows(db,"""
                        SELECT 1 FROM auth_sessions WHERE socket_id=? AND user_id=? AND closed_at IS NULL
                        AND expires_at>NOW(3) AND last_heartbeat_at>NOW(3)-INTERVAL 90 SECOND
                        AND last_activity_at>NOW(3)-INTERVAL 30 MINUTE
                        """,listener.getKey(),user).isEmpty()) continue;
                try { sub.sink().send(event); }
                catch (IOException ex) {
                    subscribers.remove(listener.getKey(),sub);
                    Sql.update(db,"INSERT INTO audit_logs(action,target_type,target_id,result,reason) VALUES ('NOTIFY_FAIL','user',?,'failed','socket')",user);
                }
            }
            Sql.update(db,"UPDATE event_outbox SET delivered_at=NOW(3),attempts=attempts+1 WHERE event_key=? AND channel='notify'",
                    row.get("event_key").getAsString());
        }
        db.commit();
    }

    private static final String DUE = TaskState.LIVE+TaskState.TREE+"""
            SELECT i.task_id,i.group_id,i.round_no,i.deadline_version,i.owner,i.title,i.deadline,i.ready
            FROM items i JOIN student_groups g USING(group_id) JOIN memberships m ON m.group_id=i.group_id
                AND m.active_user_key=i.owner
            WHERE g.group_status='active' AND i.task_status IN ('in_progress','not_started')
                AND (i.ready=1 OR (i.leaf=1 AND COALESCE(i.review_state,'') NOT IN ('pending','approved')))
                AND i.deadline<=?
            """;

    public static void scan(Connection db) throws SQLException {
        var now=LocalDateTime.now(Progress.ZONE);
        var candidates=Sql.rows(db,DUE,now.plusHours(72));
        db.setAutoCommit(false);
        try {
            for (var entry:candidates) {
                long id=entry.getAsJsonObject().get("task_id").getAsLong();
                Sql.rows(db,"SELECT task_id FROM tasks WHERE task_id=? FOR UPDATE",id);
                var current=Sql.rows(db,DUE+" AND i.task_id=?",now.plusHours(72),id);
                if (!current.isEmpty()) recordAlert(db,current.get(0).getAsJsonObject(),now);
                db.commit();
            }
        } catch (SQLException | RuntimeException ex) { db.rollback(); throw ex; }
        finally { db.setAutoCommit(true); }
    }
    private static void recordAlert(Connection db,JsonObject row,LocalDateTime now) throws SQLException {
        long task=row.get("task_id").getAsLong(), user=row.get("owner").getAsLong();
        int round=row.get("round_no").getAsInt(), version=row.get("deadline_version").getAsInt();
        String due=row.get("deadline").getAsString();
        String threshold=Progress.threshold(now,LocalDateTime.parse(due.replace(' ','T')));
        String scope=row.get("ready").getAsInt()==1?"parent_close":"leaf";
        if (!Sql.rows(db,"SELECT 1 FROM deadline_alerts WHERE task_id=? AND user_id=? AND round_no=? AND deadline_version=? AND alert_scope=? AND threshold_key=?",
                task,user,round,version,scope,threshold).isEmpty()) return;
        Sql.update(db,"INSERT INTO deadline_alerts(task_id,user_id,round_no,deadline_version,alert_scope,threshold_key,alert_level) VALUES (?,?,?,?,?,?,?)",
                task,user,round,version,scope,threshold,threshold.equals("warning")?"warning":"danger");
        long group=row.get("group_id").getAsLong();
        String key="due:"+task+":"+user+":"+round+":"+version+":"+scope+":"+threshold;
        Sql.update(db,"CALL tv4_notify(?,?,?,'deadline',?,?,'task',?)",key,user,group,
                scope.equals("leaf")?"Cảnh báo hạn công việc":"Nhắc đóng việc cha",row.get("title").getAsString()+" · "+due,task);
        Sql.update(db,"INSERT INTO audit_logs(group_id,action,target_type,target_id,result,event_key) VALUES (?,'DEADLINE_ALERT_SENT','task',?,'success',?)",
                group,task,key);
    }
}
