package vn.ktd.taskhub.server.feature;

import com.google.gson.*;
import java.io.IOException;
import java.sql.*;
import java.util.Set;
import vn.ktd.taskhub.server.feature.audit.AuditTrail;
import vn.ktd.taskhub.server.feature.notification.Inbox;
import vn.ktd.taskhub.server.feature.progress.*;
import vn.ktd.taskhub.server.feature.task.AssignmentConfirmation;
import vn.ktd.taskhub.server.feature.workload.Workload;
import vn.ktd.taskhub.shared.protocol.MessageCodec;

public final class TeamActions {
    private TeamActions() {}
    public static final Set<String> ACTIONS=Set.of("WORKLOAD_VIEW","WORKLOAD_SUGGEST","WORKLOAD_SIMULATE",
            "CAPACITY_SAVE","PROGRESS_MINE","PROGRESS_GROUP","REPORT_VIEW","REPORT_EXPORT","TASK_ASSIGN_CONFIRM",
            "NOTIFICATION_SUBSCRIBE","NOTIFICATION_LIST","NOTIFICATION_OPEN","NOTIFICATION_COUNT");

    public static JsonObject run(TeamRequest q,String action) throws SQLException {
        var db=q.db();
        db.setAutoCommit(false);
        try {
            JsonObject result=switch(action) {
                case "WORKLOAD_VIEW","WORKLOAD_SUGGEST","WORKLOAD_SIMULATE","CAPACITY_SAVE" -> Workload.execute(q,action);
                case "TASK_ASSIGN_CONFIRM" -> AssignmentConfirmation.apply(q);
                case "PROGRESS_MINE","PROGRESS_GROUP","REPORT_VIEW" -> Progress.execute(q,action);
                case "REPORT_EXPORT" -> {
                    var report=ReportExport.generate(q,Progress.execute(q,action));
                    AuditTrail.append(db,q.session(),q.user(),q.id("groupId"),action,"report",q.field("format"),null,null,null);
                    yield report;
                }
                case "NOTIFICATION_SUBSCRIBE" -> new JsonObject();
                default -> Inbox.execute(q,action);
            };
            if(result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MessageCodec.MAX_BYTES-512)
                throw new IllegalArgumentException("RESULT_TOO_LARGE");
            db.commit();
            return result;
        } catch (IllegalArgumentException | IOException | SQLException failure) {
            db.rollback();
            String error=failure instanceof IllegalArgumentException ? failure.getMessage() :
                    failure instanceof IOException ? "REPORT_EXPORT_FAILED" : "SERVER_ERROR";
            String log=switch(action) {
                case "CAPACITY_SAVE" -> "LOAD_CONFIG_FAIL";
                case "REPORT_EXPORT" -> "REPORT_EXPORT_FAIL";
                case "NOTIFICATION_OPEN" -> "NOTIFICATION_ACCESS_DENIED";
                default -> "FORBIDDEN".equals(error)?"ACCESS_DENIED":null;
            };
            if(log!=null) {
                AuditTrail.append(db,q.session(),q.user(),null,log,"action",action,null,null,error);
                db.commit();
            }
            if(failure instanceof SQLException sql) throw sql;
            throw new IllegalArgumentException(error);
        }
    }
}
