package vn.ktd.taskhub.server.feature.account;

import com.google.gson.*;
import java.sql.*;
import java.util.*;
import vn.ktd.taskhub.server.security.SocketSession;
import static vn.ktd.taskhub.server.feature.account.Sql.*;

final class AuditQuery {
    static void run(Connection db,SocketSession session,JsonObject filters,JsonObject out,boolean export)throws SQLException{
        AccountActions.require(session.active(),"UNAUTHENTICATED");
        long user=session.authenticatedUser();
        String group=AccountActions.text(filters,"groupId");
        var args=new ArrayList<Object>();
        // Never select another user's connection identifiers into the result.
        StringBuilder query=new StringBuilder("""
            SELECT audit_id,occurred_at,user_id,action,target_type,target_id,old_value,new_value,result,reason,
            IF(user_id=?,ip_address,NULL) ip_address,IF(user_id=?,socket_id,NULL) socket_id
            FROM audit_logs a WHERE
            """);
        args.add(user);args.add(user);
        if(group.isBlank()){
            AccountActions.require(!export,"GROUP_REQUIRED");
            query.append(" group_id IS NULL AND user_id=? ");args.add(user);
        }else{
            var membership=one(db,"SELECT member_role FROM memberships WHERE group_id=? AND user_id=? AND membership_status='active' FOR UPDATE",group,user);
            AccountActions.require(membership!=null,"PERMISSION_DENIED");
            boolean leader=AccountActions.text(membership,"member_role").equals("leader");
            AccountActions.require(!export || leader,"PERMISSION_DENIED");
            query.append(" group_id=? AND action NOT IN ('LOGIN','LOAD_CONFIG') ");args.add(group);
            if(!leader){
                query.append("""
                     AND (user_id=? OR (target_type='task' AND EXISTS
                     (SELECT 1 FROM tasks t JOIN task_assignments s ON t.task_id=s.task_id
                     WHERE t.group_id=a.group_id AND CAST(t.task_id AS CHAR)=a.target_id
                     AND s.assignee_user_id=? AND s.is_current=1)))
                    """);args.add(user);args.add(user);
            }
        }
        String[] keys={"from","to","actorId","action","targetId","beforeId"};
        String[] conditions={"occurred_at>=?","occurred_at<=?","user_id=?","action=?","target_id=?","audit_id<?"};
        JsonObject applied=new JsonObject();
        for(int i=0;i<keys.length;i++){
            String value=AccountActions.text(filters,keys[i]);
            if(!value.isBlank()){query.append(" AND ").append(conditions[i]);args.add(value);applied.addProperty(keys[i],value);}
        }
        query.append(" ORDER BY audit_id DESC LIMIT ").append(export?1001:51);
        var result=rows(db,query.toString(),args.toArray());
        if(export){
            AccountActions.require(result.size()<=1000,"NARROW_FILTER");
            String[] columns={"audit_id","occurred_at","user_id","action","target_type","target_id","old_value","new_value","result","reason","ip_address","socket_id"};
            var csv=new StringBuilder(String.join(",",columns)).append("\r\n");
            for(var entry:result){
                var fields=new ArrayList<String>();
                for(String col:columns){
                    String s=AccountActions.text(entry.getAsJsonObject(),col);
                    if(s.stripLeading().matches("(?s)^[=+@-].*") || s.startsWith("\t"))s="'"+s;
                    fields.add("\""+s.replace("\"","\"\"")+"\"");
                }
                csv.append(String.join(",",fields)).append("\r\n");
            }
            out.addProperty("csv",csv.toString());
            AccountActions.require(out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<950000,"NARROW_FILTER");
            applied.addProperty("count",result.size());
            update(db,"INSERT INTO audit_logs(user_id,group_id,action,target_type,target_id,new_value,ip_address,socket_id,result) VALUES (?,?,'AUDIT_EXPORT_CSV','group',?,?,?,?,'success')",
                user,group,group,applied.toString(),session.ip(),session.socketId());
        }else{
            if(result.size()>50){result.remove(50);out.addProperty("nextBeforeId",result.get(49).getAsJsonObject().get("audit_id").getAsLong());}
            out.add("rows",result);
        }
    }
}
