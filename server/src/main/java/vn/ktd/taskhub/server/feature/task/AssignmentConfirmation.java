package vn.ktd.taskhub.server.feature.task;

import com.google.gson.*;
import java.sql.*;
import java.time.*;
import vn.ktd.taskhub.server.feature.TeamRequest;
import vn.ktd.taskhub.server.feature.account.Sql;
import vn.ktd.taskhub.server.feature.audit.AuditTrail;
import vn.ktd.taskhub.server.feature.progress.Progress;

/** Final confirmation shared by the draft and the workload suggestion. */
public final class AssignmentConfirmation {
    private AssignmentConfirmation() {}

    public static JsonObject apply(TeamRequest q) throws SQLException {
        long group=q.id("groupId"), receiver=q.id("assigneeId");
        Sql.rows(q.db(),"SELECT group_id FROM student_groups WHERE group_id=? FOR UPDATE",group);
        q.role(group,true);
        String nonce=q.field("confirmationId");
        if(!nonce.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) throw TeamRequest.invalid();
        String key="assign:"+group+":"+q.user()+":"+nonce;
        var previous=Sql.rows(q.db(),"SELECT new_value FROM audit_logs WHERE event_key=?",key);
        if(!previous.isEmpty())return JsonParser.parseString(previous.get(0).getAsJsonObject().get("new_value").getAsString()).getAsJsonObject();
        if(Sql.rows(q.db(),"SELECT user_id FROM memberships WHERE group_id=? AND active_user_key=? FOR UPDATE",
                group,receiver).isEmpty()) throw new IllegalArgumentException("INVALID_ASSIGNEE");
        boolean create=q.field("taskId").isEmpty();
        long task;
        if(create) {
            String title=q.field("title");
            var due=q.date("deadline").atTime(23,59,59);
            if(title.length()<3 || title.length()>100 || !due.isAfter(LocalDateTime.now(Progress.ZONE))) throw TeamRequest.invalid();
            Sql.update(q.db(),"INSERT INTO tasks(group_id,title,estimated_hours,deadline,created_by) VALUES (?,?,?,?,?)",
                    group,title,q.hours("hours"),due,q.user());
            task=lastId(q.db());
        } else {
            task=q.id("taskId");
            var rows=Sql.rows(q.db(),"""
                    SELECT t.task_status,t.deleted_at,t.task_type,a.assignee_user_id,a.assignment_id,t.round_no
                    FROM tasks t JOIN task_assignments a ON a.current_task_key=t.task_id
                    WHERE t.group_id=? AND t.task_id=? FOR UPDATE
                    """,group,task);
            if(rows.isEmpty()) throw new IllegalArgumentException("TASK_CHANGED");
            var r=rows.get(0).getAsJsonObject();
            if(!r.get("task_status").getAsString().equals("not_started") || !r.get("deleted_at").isJsonNull()
                    || r.get("assignee_user_id").getAsLong()!=q.id("fromId") || receiver==q.id("fromId")
                    || !r.get("task_type").getAsString().equals("simple")) throw new IllegalArgumentException("TASK_CHANGED");
            var eligible=Sql.rows(q.db(),vn.ktd.taskhub.server.feature.progress.TaskState.LIVE
                    +vn.ktd.taskhub.server.feature.progress.TaskState.TREE+
                    " SELECT 1 FROM items WHERE task_id=? AND leaf=1 AND estimated_hours=? AND DATE(deadline)=?",
                    task,q.hours("hours"),q.date("deadline"));
            if(eligible.isEmpty()) throw new IllegalArgumentException("TASK_CHANGED");
            Sql.update(q.db(),"UPDATE task_assignments SET is_current=0,unassigned_at=NOW(3) WHERE assignment_id=?",
                    r.get("assignment_id").getAsLong());
        }
        Sql.update(q.db(),"""
                INSERT INTO task_assignments(task_id,assignee_user_id,assigned_by,round_no,change_kind)
                SELECT task_id,?,?,round_no,? FROM tasks WHERE task_id=?
                """,receiver,q.user(),create?"initial":"reassign",task);
        Sql.update(q.db(),"""
                INSERT INTO task_revisions(task_id,effective_at,reason,actor_id,parent_task_id,assignment_id,
                        round_no,task_type,task_status,hidden,estimated_hours,deadline,deadline_version)
                SELECT t.task_id,NOW(3),'TASK_ASSIGN',?,t.parent_task_id,a.assignment_id,t.round_no,t.task_type,
                        t.task_status,0,t.estimated_hours,t.deadline,t.deadline_version
                FROM tasks t JOIN task_assignments a ON a.current_task_key=t.task_id WHERE t.task_id=?
                """,q.user(),task);
        var result=new JsonObject();
        result.addProperty("taskId",task); result.addProperty("assigneeId",receiver);
        AuditTrail.append(q.db(),q.session(),q.user(),group,"TASK_ASSIGN","task",Long.toString(task),null,result,null,key);
        return result;
    }
    private static long lastId(Connection db) throws SQLException {
        return Sql.rows(db,"SELECT LAST_INSERT_ID() AS id").get(0).getAsJsonObject().get("id").getAsLong();
    }
}
