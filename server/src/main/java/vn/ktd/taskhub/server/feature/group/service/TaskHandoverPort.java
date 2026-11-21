package vn.ktd.taskhub.server.feature.group.service;

import java.sql.Connection;

/** Contract implemented by TV3. TV2 never changes task assignments directly. */
public interface TaskHandoverPort {
    void handover(Connection connection, long taskId, long assignmentId, long oldUserId,
            long newUserId, long actorId, String reason);

    static TaskHandoverPort unavailable() {
        return (connection, taskId, assignmentId, oldUserId, newUserId, actorId, reason) -> {
            throw new GroupException("DEPENDENCY_UNAVAILABLE", "Chức năng đổi người phụ trách của TV3 chưa được tích hợp");
        };
    }
}
