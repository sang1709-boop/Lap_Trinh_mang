package vn.ktd.taskhub.server.feature.audit;

import com.google.gson.JsonObject;
import java.sql.Connection;
import java.sql.SQLException;
import vn.ktd.taskhub.server.feature.account.Sql;
import vn.ktd.taskhub.server.security.SocketSession;

/** Call in the same transaction as the operation. Pass only display-safe snapshots. */
public final class AuditTrail {
    private AuditTrail() {}
    public static void append(Connection db, SocketSession session, Long actor, Long group, String action,
            String type,String target,JsonObject old,JsonObject next,String failure) throws SQLException {
        Sql.update(db,"""
            INSERT INTO audit_logs(user_id,group_id,action,target_type,target_id,old_value,new_value,ip_address,socket_id,result,reason)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """,actor,group,action,type,target,old==null?null:old.toString(),next==null?null:next.toString(),
            session.ip(),session.socketId(),failure==null?"success":"failed",failure);
    }
}
