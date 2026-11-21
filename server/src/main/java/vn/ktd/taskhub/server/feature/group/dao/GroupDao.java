package vn.ktd.taskhub.server.feature.group.dao;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.sql.*;

public final class GroupDao {
    public record GroupRow(long id, String name, String description, String inviteCode,
            boolean approvalEnabled, String status, long createdBy) {}
    public record Membership(long id, long groupId, long userId, String role, String status) {}
    public record Assignment(long assignmentId, long taskId, long userId) {}

    public JsonArray listGroups(Connection db, long userId) throws SQLException {
        String sql = """
            SELECT g.group_id,g.name,g.description,g.invite_code,g.approval_enabled,g.group_status,
                   m.member_role,m.membership_status,COUNT(am.membership_id) member_count
            FROM memberships m JOIN student_groups g ON g.group_id=m.group_id
            LEFT JOIN memberships am ON am.group_id=g.group_id AND am.membership_status='active'
            WHERE m.user_id=?
            GROUP BY g.group_id,g.name,g.description,g.invite_code,g.approval_enabled,g.group_status,m.member_role,m.membership_status
            ORDER BY g.created_at DESC""";
        JsonArray result = new JsonArray();
        try (PreparedStatement q = db.prepareStatement(sql)) {
            q.setLong(1, userId);
            try (ResultSet r = q.executeQuery()) { while (r.next()) result.add(groupJson(r)); }
        }
        String pending = """
            SELECT g.group_id,g.name,g.description,g.invite_code,g.approval_enabled,g.group_status,
                   NULL member_role,'pending' membership_status,COUNT(am.membership_id) member_count
            FROM group_join_requests j JOIN student_groups g ON g.group_id=j.group_id
            LEFT JOIN memberships am ON am.group_id=g.group_id AND am.membership_status='active'
            WHERE j.user_id=? AND j.request_status='pending'
              AND NOT EXISTS(SELECT 1 FROM memberships x WHERE x.group_id=g.group_id AND x.user_id=? AND x.membership_status='active')
            GROUP BY g.group_id,g.name,g.description,g.invite_code,g.approval_enabled,g.group_status
            ORDER BY g.created_at DESC""";
        try (PreparedStatement q = db.prepareStatement(pending)) {
            q.setLong(1, userId); q.setLong(2, userId);
            try (ResultSet r = q.executeQuery()) { while (r.next()) result.add(groupJson(r)); }
        }
        return result;
    }

    private JsonObject groupJson(ResultSet r) throws SQLException {
        JsonObject o = new JsonObject();
        o.addProperty("groupId", r.getLong("group_id"));
        o.addProperty("name", r.getString("name"));
        o.addProperty("description", r.getString("description"));
        o.addProperty("inviteCode", r.getString("invite_code"));
        o.addProperty("approvalEnabled", r.getBoolean("approval_enabled"));
        o.addProperty("status", r.getString("group_status"));
        o.addProperty("role", r.getString("member_role"));
        o.addProperty("membershipStatus", r.getString("membership_status"));
        o.addProperty("memberCount", r.getInt("member_count"));
        return o;
    }

    public GroupRow lockGroup(Connection db, long groupId) throws SQLException {
        try (PreparedStatement q = db.prepareStatement("SELECT * FROM student_groups WHERE group_id=? FOR UPDATE")) {
            q.setLong(1, groupId);
            try (ResultSet r = q.executeQuery()) {
                return r.next() ? new GroupRow(r.getLong("group_id"), r.getString("name"),
                        r.getString("description"), r.getString("invite_code"),
                        r.getBoolean("approval_enabled"), r.getString("group_status"),
                        r.getLong("created_by")) : null;
            }
        }
    }

    public Membership membership(Connection db, long groupId, long userId, boolean lock) throws SQLException {
        String sql = "SELECT * FROM memberships WHERE group_id=? AND user_id=? AND membership_status='active'" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement q = db.prepareStatement(sql)) {
            q.setLong(1, groupId); q.setLong(2, userId);
            try (ResultSet r = q.executeQuery()) {
                return r.next() ? new Membership(r.getLong("membership_id"), groupId, userId,
                        r.getString("member_role"), r.getString("membership_status")) : null;
            }
        }
    }

    public JsonArray listMembers(Connection db, long groupId) throws SQLException {
        String sql = """
            SELECT u.user_id,u.student_id,u.full_name,m.member_role,
              EXISTS(SELECT 1 FROM auth_sessions s WHERE s.user_id=u.user_id AND s.closed_at IS NULL
                AND s.expires_at>NOW(3) AND s.last_heartbeat_at>DATE_SUB(NOW(3),INTERVAL 90 SECOND)) online,
              (SELECT COUNT(*) FROM task_assignments a JOIN tasks t ON t.task_id=a.task_id
                WHERE a.assignee_user_id=u.user_id AND a.is_current=1 AND t.group_id=?
                  AND t.deleted_at IS NULL AND t.task_status NOT IN('completed','cancelled')) open_tasks
            FROM memberships m JOIN users u ON u.user_id=m.user_id
            WHERE m.group_id=? AND m.membership_status='active'
            ORDER BY m.member_role='leader' DESC,u.full_name""";
        JsonArray a = new JsonArray();
        try (PreparedStatement q = db.prepareStatement(sql)) {
            q.setLong(1, groupId); q.setLong(2, groupId);
            try (ResultSet r=q.executeQuery()) { while(r.next()) {
                JsonObject o=new JsonObject(); o.addProperty("userId",r.getLong(1)); o.addProperty("studentId",r.getString(2));
                o.addProperty("fullName",r.getString(3)); o.addProperty("role",r.getString(4));
                o.addProperty("online",r.getBoolean(5)); o.addProperty("openTaskCount",r.getInt(6)); a.add(o);
            }}
        }
        return a;
    }

    public long createGroup(Connection db, long actor, String name, String description, String code, boolean approval) throws SQLException {
        try (PreparedStatement q=db.prepareStatement("INSERT INTO student_groups(name,description,invite_code,approval_enabled,created_by) VALUES(?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)) {
            q.setString(1,name); q.setString(2,description.isEmpty()?null:description); q.setString(3,code); q.setBoolean(4,approval); q.setLong(5,actor); q.executeUpdate();
            try(ResultSet r=q.getGeneratedKeys()){r.next(); return r.getLong(1);}
        }
    }

    public void addMembership(Connection db,long groupId,long userId,String role) throws SQLException {
        try(PreparedStatement q=db.prepareStatement("INSERT INTO memberships(group_id,user_id,member_role) VALUES(?,?,?)")){
            q.setLong(1,groupId);q.setLong(2,userId);q.setString(3,role);q.executeUpdate();
        }
    }

    public boolean duplicateName(Connection db,long actor,String name,long exceptGroupId) throws SQLException {
        try(PreparedStatement q=db.prepareStatement("SELECT 1 FROM student_groups WHERE created_by=? AND name=? AND group_id<>? LIMIT 1")){
            q.setLong(1,actor);q.setString(2,name);q.setLong(3,exceptGroupId);try(ResultSet r=q.executeQuery()){return r.next();}
        }
    }

    public boolean blocked(Connection db,long groupId,long userId) throws SQLException {
        try(PreparedStatement q=db.prepareStatement("SELECT 1 FROM group_blocks WHERE group_id=? AND user_id=?")){
            q.setLong(1,groupId);q.setLong(2,userId);try(ResultSet r=q.executeQuery()){return r.next();}
        }
    }

    public boolean hasOpenTasks(Connection db,long groupId,Long userId) throws SQLException {
        String sql="SELECT 1 FROM tasks t JOIN task_assignments a ON a.task_id=t.task_id AND a.is_current=1 WHERE t.group_id=? AND t.deleted_at IS NULL AND t.task_status NOT IN('completed','cancelled')"+(userId==null?"":" AND a.assignee_user_id=?")+" LIMIT 1";
        try(PreparedStatement q=db.prepareStatement(sql)){q.setLong(1,groupId);if(userId!=null)q.setLong(2,userId);try(ResultSet r=q.executeQuery()){return r.next();}}
    }

    public Long findUser(Connection db,String identifier) throws SQLException {
        try(PreparedStatement q=db.prepareStatement("SELECT user_id FROM users WHERE (student_id=? OR email=?) AND account_status='active'")){
            q.setString(1,identifier);q.setString(2,identifier);try(ResultSet r=q.executeQuery()){return r.next()?r.getLong(1):null;}
        }
    }

    public JsonArray listInvitations(Connection db,long actor,Long groupId) throws SQLException {
        String sql = groupId == null ? """
            SELECT i.invitation_id,i.group_id,g.name group_name,i.invited_user_id user_id,u.student_id,u.full_name,i.invitation_status,i.expires_at
            FROM group_invitations i JOIN student_groups g ON g.group_id=i.group_id JOIN users u ON u.user_id=i.invited_by
            WHERE i.invited_user_id=? AND i.invitation_status='pending' ORDER BY i.created_at DESC""" : """
            SELECT i.invitation_id,i.group_id,g.name group_name,i.invited_user_id user_id,u.student_id,u.full_name,i.invitation_status,i.expires_at
            FROM group_invitations i JOIN student_groups g ON g.group_id=i.group_id JOIN users u ON u.user_id=i.invited_user_id
            WHERE i.group_id=? ORDER BY i.created_at DESC""";
        JsonArray a=new JsonArray();try(PreparedStatement q=db.prepareStatement(sql)){q.setLong(1,groupId==null?actor:groupId);try(ResultSet r=q.executeQuery()){while(r.next()){
            JsonObject o=new JsonObject();o.addProperty("invitationId",r.getLong(1));o.addProperty("groupId",r.getLong(2));o.addProperty("groupName",r.getString(3));o.addProperty("userId",r.getLong(4));o.addProperty("studentId",r.getString(5));o.addProperty("fullName",r.getString(6));o.addProperty("status",r.getString(7));o.addProperty("expiresAt",r.getTimestamp(8).toInstant().toString());a.add(o);
        }}}return a;
    }

    public JsonArray listJoinRequests(Connection db,long groupId) throws SQLException {
        JsonArray a=new JsonArray();String sql="SELECT j.request_id,j.user_id,u.student_id,u.full_name,j.request_status,j.requested_at FROM group_join_requests j JOIN users u ON u.user_id=j.user_id WHERE j.group_id=? ORDER BY j.requested_at DESC";
        try(PreparedStatement q=db.prepareStatement(sql)){q.setLong(1,groupId);try(ResultSet r=q.executeQuery()){while(r.next()){
            JsonObject o=new JsonObject();o.addProperty("requestId",r.getLong(1));o.addProperty("userId",r.getLong(2));o.addProperty("studentId",r.getString(3));o.addProperty("fullName",r.getString(4));o.addProperty("status",r.getString(5));o.addProperty("requestedAt",r.getTimestamp(6).toInstant().toString());a.add(o);
        }}}return a;
    }

    public JsonArray listBlocks(Connection db,long groupId) throws SQLException {
        JsonArray a=new JsonArray();try(PreparedStatement q=db.prepareStatement("SELECT u.user_id,u.student_id,u.full_name,b.blocked_at FROM group_blocks b JOIN users u ON u.user_id=b.user_id WHERE b.group_id=? ORDER BY b.blocked_at DESC")){
            q.setLong(1,groupId);try(ResultSet r=q.executeQuery()){while(r.next()){JsonObject o=new JsonObject();o.addProperty("userId",r.getLong(1));o.addProperty("studentId",r.getString(2));o.addProperty("fullName",r.getString(3));o.addProperty("blockedAt",r.getTimestamp(4).toInstant().toString());a.add(o);}}}return a;
    }

    public JsonArray listHandovers(Connection db,long groupId) throws SQLException {
        JsonArray a=new JsonArray();String sql="SELECT h.request_id,h.task_id,h.assignment_id,h.requested_by,h.suggested_user_id,h.reason,h.status,h.requested_at FROM handover_requests h JOIN tasks t ON t.task_id=h.task_id WHERE t.group_id=? ORDER BY h.requested_at DESC";
        try(PreparedStatement q=db.prepareStatement(sql)){q.setLong(1,groupId);try(ResultSet r=q.executeQuery()){while(r.next()){JsonObject o=new JsonObject();o.addProperty("requestId",r.getLong(1));o.addProperty("taskId",r.getLong(2));o.addProperty("assignmentId",r.getLong(3));o.addProperty("requestedBy",r.getLong(4));long s=r.getLong(5);if(!r.wasNull())o.addProperty("suggestedUserId",s);o.addProperty("reason",r.getString(6));o.addProperty("status",r.getString(7));o.addProperty("requestedAt",r.getTimestamp(8).toInstant().toString());a.add(o);}}}return a;
    }

    public Assignment lockAssignment(Connection db,long taskId,long actor) throws SQLException {
        String sql="SELECT a.assignment_id,a.task_id,a.assignee_user_id FROM task_assignments a JOIN tasks t ON t.task_id=a.task_id WHERE a.task_id=? AND a.assignee_user_id=? AND a.is_current=1 AND t.deleted_at IS NULL AND t.task_status NOT IN('completed','cancelled') FOR UPDATE";
        try(PreparedStatement q=db.prepareStatement(sql)){q.setLong(1,taskId);q.setLong(2,actor);try(ResultSet r=q.executeQuery()){return r.next()?new Assignment(r.getLong(1),r.getLong(2),r.getLong(3)):null;}}
    }
}
