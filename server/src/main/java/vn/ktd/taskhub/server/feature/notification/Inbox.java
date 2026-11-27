package vn.ktd.taskhub.server.feature.notification;

import com.google.gson.*;
import java.sql.*;
import vn.ktd.taskhub.server.feature.TeamRequest;
import vn.ktd.taskhub.server.feature.account.Sql;

public final class Inbox {
    private Inbox() {}
    private static final String COLUMNS = """
            SELECT notification_id AS id,notification_type AS kind,title,body,group_id AS groupId,
                target_type AS targetType,target_id AS targetId,CAST(is_read AS UNSIGNED) AS isRead,
                created_at AS createdAt,read_at AS readAt,chat_count AS chatCount,last_message_id AS lastMessageId
            FROM notifications
            """;

    public static JsonObject execute(TeamRequest q, String action) throws SQLException {
        var result = new JsonObject();
        if (action.equals("NOTIFICATION_OPEN")) {
            long id = q.id("notificationId");
            var rows = Sql.rows(q.db(), COLUMNS+" WHERE user_id=? AND notification_id=? FOR UPDATE", q.user(), id);
            if (rows.isEmpty()) throw new IllegalArgumentException("NOTIFICATION_NOT_FOUND");
            var row = rows.get(0).getAsJsonObject();
            if (row.get("isRead").getAsInt()==0) {
                Sql.update(q.db(),"UPDATE notifications SET is_read=1,read_at=NOW(3) WHERE notification_id=? AND user_id=?",id,q.user());
                if (row.get("kind").getAsString().equals("group_chat")) markChat(q,row);
                Sql.update(q.db(),"""
                        INSERT INTO event_outbox(event_key,channel,payload,created_at)
                        VALUES (?,'notify',JSON_OBJECT('userId',?,'notificationId',?),NOW(3))
                        ""","bell:read:"+id,q.user(),id);
            }
            result.add("notification",Sql.rows(q.db(),COLUMNS+" WHERE user_id=? AND notification_id=?",q.user(),id).get(0));
            if (!row.get("targetType").isJsonNull() && row.get("targetType").getAsString().equals("task")) {
                var task=Sql.rows(q.db(),"""
                        SELECT t.task_id AS id,t.title,t.description,t.task_status AS status,t.deadline,
                            t.estimated_hours AS hours,u.full_name AS assignee FROM tasks t
                        JOIN memberships m ON m.group_id=t.group_id AND m.active_user_key=?
                        JOIN task_assignments a ON a.current_task_key=t.task_id JOIN users u ON u.user_id=a.assignee_user_id
                        WHERE t.task_id=? AND t.group_id=? AND t.deleted_at IS NULL
                          AND (m.member_role='leader' OR a.assignee_user_id=m.user_id)
                        """,q.user(),row.get("targetId").getAsLong(),row.get("groupId").getAsLong());
                if(!task.isEmpty())result.add("task",task.get(0));
            }
        } else if (!action.equals("NOTIFICATION_COUNT")) {
            String status = q.field("status"), kind = q.field("kind");
            Integer read = switch (status) {
                case "", "all" -> null;
                case "read" -> 1;
                case "unread" -> 0;
                default -> throw TeamRequest.invalid();
            };
            long before = q.field("beforeId").isEmpty() ? Long.MAX_VALUE : q.id("beforeId");
            var rows = Sql.rows(q.db(),COLUMNS+"""
                    WHERE user_id=? AND notification_id<? AND (? IS NULL OR is_read=?) AND (?='' OR notification_type=?)
                    ORDER BY notification_id DESC LIMIT 31
                    """,q.user(),before,read,read,kind,kind);
            if (rows.size()>30) {
                rows.remove(30);
                result.add("nextBeforeId",rows.get(29).getAsJsonObject().get("id"));
            }
            result.add("notifications",rows);
        }
        result.add("unreadCount",Sql.rows(q.db(),"SELECT COUNT(*) AS n FROM notifications WHERE user_id=? AND is_read=0",
                q.user()).get(0).getAsJsonObject().get("n"));
        return result;
    }

    private static void markChat(TeamRequest q,JsonObject row) throws SQLException {
        long id = row.get("id").getAsLong(), group = row.get("groupId").getAsLong();
        long last = row.get("lastMessageId").getAsLong();
        long seen = q.field("seenMessageId").isEmpty() ? last : q.id("seenMessageId");
        if (Sql.rows(q.db(),"SELECT 1 FROM group_chat_receipts WHERE notification_id=? AND user_id=? AND message_id=?",
                id,q.user(),seen).isEmpty()) throw TeamRequest.invalid();
        Sql.update(q.db(),"""
                INSERT INTO chat_read_cursors(user_id,group_id,last_seen_message_id) VALUES (?,?,?)
                ON DUPLICATE KEY UPDATE last_seen_message_id=GREATEST(last_seen_message_id,?)
                """,q.user(),group,seen,seen);
        if (seen == last) return;
        var tail=Sql.rows(q.db(),"SELECT COUNT(*) AS n FROM group_chat_receipts WHERE notification_id=? AND message_id>?",id,seen)
                .get(0).getAsJsonObject();
        String key="chat-tail:"+id+":"+seen;
        Sql.update(q.db(),"CALL tv4_notify(?,?,?,'group_chat','Tin nhắn nhóm',?,'group',?)",key,q.user(),group,row.get("body").getAsString(),group);
        Sql.update(q.db(),"""
                UPDATE group_chat_receipts r JOIN notifications n ON n.user_id=r.user_id AND n.event_key=?
                SET r.notification_id=n.notification_id WHERE r.notification_id=? AND r.message_id>?
                """,key,id,seen);
        Sql.update(q.db(),"UPDATE notifications SET chat_count=?,last_message_id=? WHERE user_id=? AND event_key=?",
                tail.get("n").getAsInt(),last,q.user(),key);
        Sql.update(q.db(),"UPDATE notifications SET chat_count=chat_count-?,last_message_id=? WHERE notification_id=?",
                tail.get("n").getAsInt(),seen,id);
    }
}
