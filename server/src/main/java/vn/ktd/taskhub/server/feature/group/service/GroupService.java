package vn.ktd.taskhub.server.feature.group.service;

import com.google.gson.*;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import vn.ktd.taskhub.server.database.Database;
import vn.ktd.taskhub.server.feature.group.dao.GroupDao;
import vn.ktd.taskhub.server.feature.group.dao.GroupDao.GroupRow;
import vn.ktd.taskhub.server.feature.group.dao.GroupDao.Membership;
import vn.ktd.taskhub.server.routing.RequestContext;

public final class GroupService {
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789abcdefghjkmnpqrstuvwxyz";
    private final SecureRandom random = new SecureRandom();
    private final Database database;
    private final GroupDao dao;
    private final TaskHandoverPort handoverPort;

    public GroupService(Database database) { this(database, new GroupDao(), TaskHandoverPort.unavailable()); }
    public GroupService(Database database, GroupDao dao, TaskHandoverPort handoverPort) {
        this.database=database; this.dao=dao; this.handoverPort=handoverPort;
    }

    public JsonObject execute(String action, RequestContext context, JsonObject data) {
        long actor = requireActor(context);
        try {
            return switch (action) {
                case "GROUP_LIST" -> database.read(db -> wrap("groups", dao.listGroups(db, actor)));
                case "GROUP_DETAIL" -> database.read(db -> detail(db, actor, id(data,"groupId")));
                case "GROUP_MEMBER_LIST" -> database.read(db -> members(db, actor, id(data,"groupId")));
                case "GROUP_INVITATION_LIST" -> database.read(db -> invitations(db, actor, optionalId(data,"groupId")));
                case "GROUP_JOIN_REQUEST_LIST" -> database.read(db -> joinRequests(db, actor, id(data,"groupId")));
                case "GROUP_BLOCK_LIST" -> database.read(db -> blocks(db, actor, id(data,"groupId")));
                case "GROUP_HANDOVER_LIST" -> database.read(db -> handovers(db, actor, id(data,"groupId")));
                case "GROUP_CREATE" -> database.transaction(db -> create(db, actor, data));
                case "GROUP_UPDATE" -> database.transaction(db -> update(db, actor, data));
                case "GROUP_INVITE_CODE_ROTATE" -> database.transaction(db -> rotateCode(db, actor, data, false));
                case "GROUP_INVITE_CODE_REVOKE" -> database.transaction(db -> rotateCode(db, actor, data, true));
                case "GROUP_DISBAND" -> database.transaction(db -> disband(db, actor, data));
                case "GROUP_JOIN" -> joinWithFailureRecord(actor, context.ipAddress(), data);
                case "GROUP_INVITE_CREATE" -> database.transaction(db -> invite(db, actor, data));
                case "GROUP_INVITE_REVOKE" -> database.transaction(db -> revokeInvite(db, actor, data));
                case "GROUP_INVITE_RESPOND" -> database.transaction(db -> respondInvite(db, actor, data));
                case "GROUP_JOIN_REQUEST_DECIDE" -> database.transaction(db -> decideJoin(db, actor, data));
                case "GROUP_MEMBER_REMOVE" -> database.transaction(db -> removeMember(db, actor, data));
                case "GROUP_ROLE_TRANSFER" -> database.transaction(db -> transferRole(db, actor, data));
                case "GROUP_LEAVE" -> database.transaction(db -> leave(db, actor, data));
                case "GROUP_MEMBER_UNBLOCK" -> database.transaction(db -> unblock(db, actor, data));
                case "GROUP_HANDOVER_REQUEST" -> database.transaction(db -> requestHandover(db, actor, data));
                case "GROUP_HANDOVER_DECIDE" -> database.transaction(db -> decideHandover(db, actor, data));
                default -> throw new GroupException("UNKNOWN_ACTION");
            };
        } catch (GroupException e) { throw e; }
        catch (SQLIntegrityConstraintViolationException e) { throw new GroupException("CONFLICT", "Dữ liệu vừa được thay đổi, hãy tải lại"); }
        catch (Exception e) { throw new GroupException("SERVER_ERROR", e.getMessage()==null?"Lỗi máy chủ":e.getMessage()); }
    }

    public void recordFailure(String action, RequestContext context, String reason) {
        if (!context.authenticated()) return;
        try { database.transaction(db -> { event(db,"audit",action+"_FAIL",context.userId(),null,reason); return null; }); }
        catch (Exception ignored) {}
    }

    private JsonObject joinWithFailureRecord(long actor,String ip,JsonObject data)throws Exception{
        try { return database.transaction(db -> join(db,actor,ip,data)); }
        catch(GroupException error){
            if("INVALID_JOIN_CODE".equals(error.code())) {
                try { database.transaction(db -> { recordJoinFailure(db,actor,ip); return null; }); }
                catch(Exception ignored) {}
            }
            throw error;
        }
    }

    private JsonObject detail(Connection db,long actor,long groupId) throws Exception {
        GroupRow g=group(db,groupId); Membership m=requireMember(db,groupId,actor,false);
        JsonObject o=new JsonObject();o.addProperty("groupId",g.id());o.addProperty("name",g.name());o.addProperty("description",g.description());
        if("leader".equals(m.role()))o.addProperty("inviteCode",g.inviteCode());
        o.addProperty("approvalEnabled",g.approvalEnabled());o.addProperty("status",g.status());o.addProperty("role",m.role());
        o.add("members",dao.listMembers(db,groupId));return o;
    }

    private JsonObject members(Connection db,long actor,long groupId) throws Exception { requireMember(db,groupId,actor,false); return wrap("members",dao.listMembers(db,groupId)); }
    private JsonObject invitations(Connection db,long actor,Long groupId) throws Exception { if(groupId!=null)requireLeader(db,groupId,actor,false);return wrap("invitations",dao.listInvitations(db,actor,groupId)); }
    private JsonObject joinRequests(Connection db,long actor,long groupId)throws Exception{requireLeader(db,groupId,actor,false);return wrap("requests",dao.listJoinRequests(db,groupId));}
    private JsonObject blocks(Connection db,long actor,long groupId)throws Exception{requireLeader(db,groupId,actor,false);return wrap("blocks",dao.listBlocks(db,groupId));}
    private JsonObject handovers(Connection db,long actor,long groupId)throws Exception{requireMember(db,groupId,actor,false);return wrap("handovers",dao.listHandovers(db,groupId));}

    private JsonObject create(Connection db,long actor,JsonObject d)throws Exception{
        String name=GroupValidation.name(string(d,"name"));String desc=GroupValidation.description(string(d,"description"));boolean approval=bool(d,"approvalEnabled",true);
        if(dao.duplicateName(db,actor,name,0))throw new GroupException("DUPLICATE_GROUP_NAME");
        String code=uniqueCode(db);long groupId=dao.createGroup(db,actor,name,desc,code,approval);dao.addMembership(db,groupId,actor,"leader");
        event(db,"audit","GROUP_CREATE",actor,groupId,name);JsonObject o=okId("groupId",groupId);o.addProperty("inviteCode",code);return o;
    }

    private JsonObject update(Connection db,long actor,JsonObject d)throws Exception{
        long groupId=id(d,"groupId");GroupRow g=activeGroup(db,groupId);requireLeader(db,groupId,actor,true);
        String name=GroupValidation.name(string(d,"name"));String desc=GroupValidation.description(string(d,"description"));boolean approval=bool(d,"approvalEnabled",g.approvalEnabled());
        if(dao.duplicateName(db,g.createdBy(),name,groupId))throw new GroupException("DUPLICATE_GROUP_NAME");
        try(PreparedStatement q=db.prepareStatement("UPDATE student_groups SET name=?,description=?,approval_enabled=? WHERE group_id=?")){q.setString(1,name);q.setString(2,desc.isEmpty()?null:desc);q.setBoolean(3,approval);q.setLong(4,groupId);q.executeUpdate();}
        event(db,"audit",g.approvalEnabled()==approval?"GROUP_UPDATE":"GROUP_JOIN_MODE_CHANGE",actor,groupId,name);return okId("groupId",groupId);
    }

    private JsonObject rotateCode(Connection db,long actor,JsonObject d,boolean revoke)throws Exception{
        long groupId=id(d,"groupId");activeGroup(db,groupId);requireLeader(db,groupId,actor,true);String code=revoke?null:uniqueCode(db);
        try(PreparedStatement q=db.prepareStatement("UPDATE student_groups SET invite_code=?,code_revoked_at=NOW(3) WHERE group_id=?")){q.setString(1,code);q.setLong(2,groupId);q.executeUpdate();}
        event(db,"audit",revoke?"JOIN_CODE_REVOKE":"JOIN_CODE_ROTATE",actor,groupId,null);JsonObject o=okId("groupId",groupId);if(code!=null)o.addProperty("inviteCode",code);return o;
    }

    private JsonObject disband(Connection db,long actor,JsonObject d)throws Exception{
        long groupId=id(d,"groupId");GroupRow g=activeGroup(db,groupId);requireLeader(db,groupId,actor,true);
        if(!bool(d,"confirmed",false)||!g.name().equals(string(d,"confirmName")))throw new GroupException("BAD_REQUEST","Xác nhận giải tán không hợp lệ");
        if(dao.hasOpenTasks(db,groupId,null))throw new GroupException("OPEN_TASKS_EXIST");
        try(PreparedStatement q=db.prepareStatement("UPDATE student_groups SET group_status='disbanded',invite_code=NULL,code_revoked_at=NOW(3),disbanded_at=NOW(3) WHERE group_id=?")){q.setLong(1,groupId);q.executeUpdate();}
        try(PreparedStatement q=db.prepareStatement("UPDATE group_invitations SET invitation_status='revoked',responded_at=NOW(3),close_reason='group_disbanded' WHERE group_id=? AND invitation_status='pending'")){q.setLong(1,groupId);q.executeUpdate();}
        try(PreparedStatement q=db.prepareStatement("UPDATE group_join_requests SET request_status='cancelled',decided_at=NOW(3),close_reason='group_disbanded' WHERE group_id=? AND request_status='pending'")){q.setLong(1,groupId);q.executeUpdate();}
        event(db,"audit","GROUP_DISBAND",actor,groupId,null);event(db,"notify","GROUP_DISBAND",actor,groupId,null);return okId("groupId",groupId);
    }

    private JsonObject join(Connection db,long actor,String ip,JsonObject d)throws Exception{
        String code=GroupValidation.required(string(d,"inviteCode"),32,"Mã gia nhập");checkJoinRate(db,actor,ip);
        GroupRow g=null;try(PreparedStatement q=db.prepareStatement("SELECT * FROM student_groups WHERE invite_code=? FOR UPDATE")){q.setString(1,code);try(ResultSet r=q.executeQuery()){if(r.next())g=new GroupRow(r.getLong("group_id"),r.getString("name"),r.getString("description"),r.getString("invite_code"),r.getBoolean("approval_enabled"),r.getString("group_status"),r.getLong("created_by"));}}
        if(g==null)throw new GroupException("INVALID_JOIN_CODE");if(!"active".equals(g.status()))throw new GroupException("GROUP_DISBANDED");
        if(dao.membership(db,g.id(),actor,true)!=null)throw new GroupException("ALREADY_MEMBER");if(dao.blocked(db,g.id(),actor))throw new GroupException("BLOCKED");
        JsonObject o=okId("groupId",g.id());
        if(g.approvalEnabled()){
            try(PreparedStatement q=db.prepareStatement("INSERT INTO group_join_requests(group_id,user_id) VALUES(?,?)",Statement.RETURN_GENERATED_KEYS)){q.setLong(1,g.id());q.setLong(2,actor);q.executeUpdate();try(ResultSet r=q.getGeneratedKeys()){r.next();o.addProperty("requestId",r.getLong(1));}}
            o.addProperty("status","pending");event(db,"audit","GROUP_JOIN_REQUEST",actor,g.id(),null);event(db,"notify","GROUP_JOIN_REQUEST",actor,g.id(),null);
        }else{dao.addMembership(db,g.id(),actor,"member");closeCompeting(db,g.id(),actor,"joined");o.addProperty("status","joined");event(db,"audit","GROUP_JOIN",actor,g.id(),null);}
        return o;
    }

    private JsonObject invite(Connection db,long actor,JsonObject d)throws Exception{
        long groupId=id(d,"groupId");activeGroup(db,groupId);requireLeader(db,groupId,actor,true);String identifier=GroupValidation.required(string(d,"identifier"),254,"Email/MSSV");Long user=dao.findUser(db,identifier);
        if(user==null)throw new GroupException("BAD_REQUEST","Không tìm thấy người dùng");if(dao.membership(db,groupId,user,true)!=null)throw new GroupException("ALREADY_MEMBER");if(dao.blocked(db,groupId,user))throw new GroupException("BLOCKED");expireInvites(db,groupId,user);
        try(PreparedStatement pending=db.prepareStatement("SELECT 1 FROM group_invitations WHERE group_id=? AND invited_user_id=? AND invitation_status='pending' LIMIT 1")){pending.setLong(1,groupId);pending.setLong(2,user);try(ResultSet r=pending.executeQuery()){if(r.next())throw new GroupException("INVITE_PENDING");}}
        long invitationId;try(PreparedStatement q=db.prepareStatement("INSERT INTO group_invitations(group_id,invited_user_id,invited_by,expires_at) VALUES(?,?,?,DATE_ADD(NOW(3),INTERVAL 7 DAY))",Statement.RETURN_GENERATED_KEYS)){q.setLong(1,groupId);q.setLong(2,user);q.setLong(3,actor);q.executeUpdate();try(ResultSet r=q.getGeneratedKeys()){r.next();invitationId=r.getLong(1);}}
        event(db,"audit","INVITE_CREATE",actor,groupId,String.valueOf(invitationId));event(db,"notify","INVITE_CREATE",user,groupId,String.valueOf(invitationId));return okId("invitationId",invitationId);
    }

    private JsonObject revokeInvite(Connection db,long actor,JsonObject d)throws Exception{
        long invitationId=id(d,"invitationId");long groupId=invitationGroup(db,invitationId,true);requireLeader(db,groupId,actor,true);
        try(PreparedStatement q=db.prepareStatement("UPDATE group_invitations SET invitation_status='revoked',responded_at=NOW(3),close_reason='leader_revoked' WHERE invitation_id=? AND invitation_status='pending'")){q.setLong(1,invitationId);if(q.executeUpdate()!=1)throw new GroupException("CONFLICT");}
        event(db,"audit","INVITE_REVOKE",actor,groupId,String.valueOf(invitationId));return okId("invitationId",invitationId);
    }

    private JsonObject respondInvite(Connection db,long actor,JsonObject d)throws Exception{
        long invitationId=id(d,"invitationId");String decision=string(d,"decision").toLowerCase(Locale.ROOT);if(!decision.equals("accept")&&!decision.equals("decline"))throw new GroupException("BAD_REQUEST");
        long groupId;Timestamp expires;long invited;String status;try(PreparedStatement q=db.prepareStatement("SELECT group_id,invited_user_id,invitation_status,expires_at FROM group_invitations WHERE invitation_id=? FOR UPDATE")){q.setLong(1,invitationId);try(ResultSet r=q.executeQuery()){if(!r.next())throw new GroupException("BAD_REQUEST");groupId=r.getLong(1);invited=r.getLong(2);status=r.getString(3);expires=r.getTimestamp(4);}}
        if(invited!=actor)throw new GroupException("FORBIDDEN");activeGroup(db,groupId);if(!"pending".equals(status))throw new GroupException("CONFLICT");if(expires.before(Timestamp.from(Instant.now()))){expireInvitation(db,invitationId);throw new GroupException("INVITE_EXPIRED");}
        if(dao.blocked(db,groupId,actor))throw new GroupException("BLOCKED");
        if(decision.equals("accept")){if(dao.membership(db,groupId,actor,true)==null)dao.addMembership(db,groupId,actor,"member");closeCompeting(db,groupId,actor,"joined");event(db,"audit","INVITE_ACCEPT",actor,groupId,String.valueOf(invitationId));}
        else{try(PreparedStatement q=db.prepareStatement("UPDATE group_invitations SET invitation_status='declined',responded_at=NOW(3) WHERE invitation_id=?")){q.setLong(1,invitationId);q.executeUpdate();}event(db,"audit","INVITE_REJECT",actor,groupId,String.valueOf(invitationId));}
        event(db,"notify","INVITE_RESULT",actor,groupId,String.valueOf(invitationId));JsonObject o=okId("groupId",groupId);o.addProperty("status",decision.equals("accept")?"joined":"declined");return o;
    }

    private JsonObject decideJoin(Connection db,long actor,JsonObject d)throws Exception{
        long requestId=id(d,"requestId");String decision=string(d,"decision").toLowerCase(Locale.ROOT);if(!decision.equals("approve")&&!decision.equals("reject"))throw new GroupException("BAD_REQUEST");
        long groupId,user;String status;try(PreparedStatement q=db.prepareStatement("SELECT group_id,user_id,request_status FROM group_join_requests WHERE request_id=? FOR UPDATE")){q.setLong(1,requestId);try(ResultSet r=q.executeQuery()){if(!r.next())throw new GroupException("BAD_REQUEST");groupId=r.getLong(1);user=r.getLong(2);status=r.getString(3);}}
        activeGroup(db,groupId);requireLeader(db,groupId,actor,true);if(!"pending".equals(status))throw new GroupException("CONFLICT");if(dao.blocked(db,groupId,user))throw new GroupException("BLOCKED");
        if(decision.equals("approve")){if(dao.membership(db,groupId,user,true)==null)dao.addMembership(db,groupId,user,"member");closeCompeting(db,groupId,user,"joined");event(db,"audit","JOIN_APPROVE",actor,groupId,String.valueOf(requestId));}
        else{try(PreparedStatement q=db.prepareStatement("UPDATE group_join_requests SET request_status='rejected',decided_by=?,decided_at=NOW(3) WHERE request_id=?")){q.setLong(1,actor);q.setLong(2,requestId);q.executeUpdate();}event(db,"audit","JOIN_REJECT",actor,groupId,String.valueOf(requestId));}
        event(db,"notify","JOIN_RESULT",user,groupId,String.valueOf(requestId));return okId("requestId",requestId);
    }

    private JsonObject removeMember(Connection db,long actor,JsonObject d)throws Exception{
        long groupId=id(d,"groupId"),user=id(d,"userId");activeGroup(db,groupId);requireLeader(db,groupId,actor,true);if(user==actor)throw new GroupException("FORBIDDEN");Membership m=requireMember(db,groupId,user,true);if("leader".equals(m.role()))throw new GroupException("FORBIDDEN");if(dao.hasOpenTasks(db,groupId,user))throw new GroupException("OPEN_TASKS_EXIST");
        if(!bool(d,"confirmed",false))throw new GroupException("BAD_REQUEST","Cần xác nhận xóa thành viên");
        try(PreparedStatement q=db.prepareStatement("UPDATE memberships SET membership_status='removed',ended_at=NOW(3) WHERE membership_id=?")){q.setLong(1,m.id());q.executeUpdate();}
        try(PreparedStatement q=db.prepareStatement("INSERT INTO group_blocks(group_id,user_id,blocked_by,blocked_at) VALUES(?,?,?,NOW(3)) ON DUPLICATE KEY UPDATE blocked_by=VALUES(blocked_by),blocked_at=VALUES(blocked_at)")){q.setLong(1,groupId);q.setLong(2,user);q.setLong(3,actor);q.executeUpdate();}
        try(PreparedStatement q=db.prepareStatement("UPDATE group_invitations SET invitation_status='revoked',responded_at=NOW(3),close_reason='member_removed' WHERE group_id=? AND invited_user_id=? AND invitation_status='pending'")){q.setLong(1,groupId);q.setLong(2,user);q.executeUpdate();}
        event(db,"audit","MEMBER_REMOVE",actor,groupId,String.valueOf(user));event(db,"notify","MEMBER_REMOVE",user,groupId,null);return okId("userId",user);
    }

    private JsonObject transferRole(Connection db,long actor,JsonObject d)throws Exception{
        long groupId=id(d,"groupId"),user=id(d,"userId");activeGroup(db,groupId);Membership old=requireLeader(db,groupId,actor,true);Membership next=requireMember(db,groupId,user,true);if(user==actor)throw new GroupException("BAD_REQUEST");
        try(PreparedStatement q=db.prepareStatement("UPDATE memberships SET member_role='member' WHERE membership_id=?")){q.setLong(1,old.id());q.executeUpdate();}
        try(PreparedStatement q=db.prepareStatement("UPDATE memberships SET member_role='leader' WHERE membership_id=?")){q.setLong(1,next.id());q.executeUpdate();}
        event(db,"audit","ROLE_CHANGE",actor,groupId,String.valueOf(user));event(db,"notify","ROLE_CHANGE",user,groupId,null);return okId("leaderUserId",user);
    }

    private JsonObject leave(Connection db,long actor,JsonObject d)throws Exception{
        long groupId=id(d,"groupId");activeGroup(db,groupId);Membership m=requireMember(db,groupId,actor,true);if("leader".equals(m.role()))throw new GroupException("ROLE_TRANSFER_REQUIRED");if(dao.hasOpenTasks(db,groupId,actor))throw new GroupException("OPEN_TASKS_EXIST");
        if(!bool(d,"confirmed",false))throw new GroupException("BAD_REQUEST","Cần xác nhận rời nhóm");
        try(PreparedStatement q=db.prepareStatement("UPDATE memberships SET membership_status='left',ended_at=NOW(3) WHERE membership_id=?")){q.setLong(1,m.id());q.executeUpdate();}
        event(db,"audit","MEMBER_LEAVE",actor,groupId,null);event(db,"notify","MEMBER_LEAVE",actor,groupId,null);return okId("groupId",groupId);
    }

    private JsonObject unblock(Connection db,long actor,JsonObject d)throws Exception{
        long groupId=id(d,"groupId"),user=id(d,"userId");activeGroup(db,groupId);requireLeader(db,groupId,actor,true);
        if(!bool(d,"confirmed",false))throw new GroupException("BAD_REQUEST","Cần xác nhận gỡ chặn");
        try(PreparedStatement q=db.prepareStatement("DELETE FROM group_blocks WHERE group_id=? AND user_id=?")){q.setLong(1,groupId);q.setLong(2,user);if(q.executeUpdate()!=1)throw new GroupException("CONFLICT");}
        event(db,"audit","MEMBER_UNBLOCK",actor,groupId,String.valueOf(user));return okId("userId",user);
    }

    private JsonObject requestHandover(Connection db,long actor,JsonObject d)throws Exception{
        long taskId=id(d,"taskId");String reason=GroupValidation.required(string(d,"reason"),500,"Lý do");GroupDao.Assignment a=dao.lockAssignment(db,taskId,actor);if(a==null)throw new GroupException("FORBIDDEN");Long suggested=optionalId(d,"suggestedUserId");long requestId;
        try(PreparedStatement q=db.prepareStatement("INSERT INTO handover_requests(task_id,assignment_id,requested_by,suggested_user_id,reason,requested_at) VALUES(?,?,?,?,?,NOW(3))",Statement.RETURN_GENERATED_KEYS)){q.setLong(1,taskId);q.setLong(2,a.assignmentId());q.setLong(3,actor);if(suggested==null)q.setNull(4,Types.BIGINT);else q.setLong(4,suggested);q.setString(5,reason);q.executeUpdate();try(ResultSet r=q.getGeneratedKeys()){r.next();requestId=r.getLong(1);}}
        event(db,"audit","HANDOVER_REQUEST",actor,null,String.valueOf(requestId));event(db,"notify","HANDOVER_REQUEST",actor,null,String.valueOf(requestId));return okId("requestId",requestId);
    }

    private JsonObject decideHandover(Connection db,long actor,JsonObject d)throws Exception{
        long requestId=id(d,"requestId");String decision=string(d,"decision").toLowerCase(Locale.ROOT);if(!decision.equals("approve")&&!decision.equals("reject"))throw new GroupException("BAD_REQUEST");
        long taskId,assignmentId,oldUser,groupId;Long suggested;String reason,status;try(PreparedStatement q=db.prepareStatement("SELECT h.task_id,h.assignment_id,h.requested_by,h.suggested_user_id,h.reason,h.status,t.group_id FROM handover_requests h JOIN tasks t ON t.task_id=h.task_id WHERE h.request_id=? FOR UPDATE")){q.setLong(1,requestId);try(ResultSet r=q.executeQuery()){if(!r.next())throw new GroupException("BAD_REQUEST");taskId=r.getLong(1);assignmentId=r.getLong(2);oldUser=r.getLong(3);long s=r.getLong(4);suggested=r.wasNull()?null:s;reason=r.getString(5);status=r.getString(6);groupId=r.getLong(7);}}
        activeGroup(db,groupId);requireLeader(db,groupId,actor,true);if(!"pending".equals(status))throw new GroupException("CONFLICT");
        if(decision.equals("approve")){Long newUser=optionalId(d,"newUserId");if(newUser==null)newUser=suggested;if(newUser==null||newUser==oldUser)throw new GroupException("BAD_REQUEST");requireMember(db,groupId,newUser,true);if(dao.blocked(db,groupId,newUser))throw new GroupException("BLOCKED");handoverPort.handover(db,taskId,assignmentId,oldUser,newUser,actor,reason);try(PreparedStatement q=db.prepareStatement("UPDATE handover_requests SET status='approved',decided_by=?,decided_at=NOW(3) WHERE request_id=?")){q.setLong(1,actor);q.setLong(2,requestId);q.executeUpdate();}event(db,"audit","TASK_HANDOVER",actor,groupId,String.valueOf(taskId));}
        else{String decisionReason=GroupValidation.required(string(d,"reason"),500,"Lý do từ chối");try(PreparedStatement q=db.prepareStatement("UPDATE handover_requests SET status='rejected',decided_by=?,decided_at=NOW(3),decision_reason=? WHERE request_id=?")){q.setLong(1,actor);q.setString(2,decisionReason);q.setLong(3,requestId);q.executeUpdate();}event(db,"audit","HANDOVER_REJECT",actor,groupId,String.valueOf(requestId));}
        event(db,"notify","HANDOVER_RESULT",oldUser,groupId,String.valueOf(requestId));return okId("requestId",requestId);
    }

    private GroupRow group(Connection db,long groupId)throws Exception{GroupRow g=dao.lockGroup(db,groupId);if(g==null)throw new GroupException("GROUP_NOT_FOUND");return g;}
    private GroupRow activeGroup(Connection db,long groupId)throws Exception{GroupRow g=group(db,groupId);if(!"active".equals(g.status()))throw new GroupException("GROUP_DISBANDED");return g;}
    private Membership requireMember(Connection db,long groupId,long user,boolean lock)throws Exception{Membership m=dao.membership(db,groupId,user,lock);if(m==null)throw new GroupException("NOT_MEMBER");return m;}
    private Membership requireLeader(Connection db,long groupId,long user,boolean lock)throws Exception{Membership m=requireMember(db,groupId,user,lock);if(!"leader".equals(m.role()))throw new GroupException("FORBIDDEN");return m;}

    private void closeCompeting(Connection db,long groupId,long user,String reason)throws SQLException{
        try(PreparedStatement q=db.prepareStatement("UPDATE group_invitations SET invitation_status='accepted',responded_at=NOW(3),close_reason=? WHERE group_id=? AND invited_user_id=? AND invitation_status='pending'")){q.setString(1,reason);q.setLong(2,groupId);q.setLong(3,user);q.executeUpdate();}
        try(PreparedStatement q=db.prepareStatement("UPDATE group_join_requests SET request_status='approved',decided_at=NOW(3),close_reason=? WHERE group_id=? AND user_id=? AND request_status='pending'")){q.setString(1,reason);q.setLong(2,groupId);q.setLong(3,user);q.executeUpdate();}
    }
    private void expireInvites(Connection db,long groupId,long user)throws SQLException{try(PreparedStatement q=db.prepareStatement("UPDATE group_invitations SET invitation_status='expired',responded_at=NOW(3),close_reason='expired' WHERE group_id=? AND invited_user_id=? AND invitation_status='pending' AND expires_at<=NOW(3)")){q.setLong(1,groupId);q.setLong(2,user);q.executeUpdate();}}
    private void expireInvitation(Connection db,long invitationId)throws SQLException{try(PreparedStatement q=db.prepareStatement("UPDATE group_invitations SET invitation_status='expired',responded_at=NOW(3),close_reason='expired' WHERE invitation_id=? AND invitation_status='pending'")){q.setLong(1,invitationId);q.executeUpdate();}}
    private long invitationGroup(Connection db,long invitationId,boolean lock)throws SQLException{try(PreparedStatement q=db.prepareStatement("SELECT group_id FROM group_invitations WHERE invitation_id=?"+(lock?" FOR UPDATE":""))){q.setLong(1,invitationId);try(ResultSet r=q.executeQuery()){if(!r.next())throw new GroupException("BAD_REQUEST");return r.getLong(1);}}}

    private void checkJoinRate(Connection db,long actor,String ip)throws Exception{String subject="user:"+actor;try(PreparedStatement q=db.prepareStatement("SELECT COUNT(*) FROM security_events WHERE kind='join_code_failed' AND occurred_at>=DATE_SUB(NOW(3),INTERVAL 15 MINUTE) AND (subject_key=? OR ip_address=INET6_ATON(?))")){q.setString(1,subject);q.setString(2,ip);try(ResultSet r=q.executeQuery()){r.next();if(r.getInt(1)>=5)throw new GroupException("JOIN_RATE_LIMITED");}}}
    private void recordJoinFailure(Connection db,long actor,String ip)throws SQLException{try(PreparedStatement q=db.prepareStatement("INSERT INTO security_events(subject_key,ip_address,kind,occurred_at) VALUES(?,INET6_ATON(?),'join_code_failed',NOW(3))")){q.setString(1,"user:"+actor);q.setString(2,normalizeIp(ip));q.executeUpdate();}}
    private String normalizeIp(String ip){try{return InetAddress.getByName(ip).getHostAddress();}catch(Exception e){return "127.0.0.1";}}

    private String uniqueCode(Connection db)throws SQLException{for(int attempt=0;attempt<20;attempt++){StringBuilder b=new StringBuilder(12);for(int i=0;i<12;i++)b.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));try(PreparedStatement q=db.prepareStatement("SELECT 1 FROM student_groups WHERE invite_code=?")){q.setString(1,b.toString());try(ResultSet r=q.executeQuery()){if(!r.next())return b.toString();}}}throw new GroupException("SERVER_ERROR","Không thể sinh mã nhóm");}
    private void event(Connection db,String channel,String action,long userId,Long groupId,String target)throws SQLException{JsonObject p=new JsonObject();p.addProperty("action",action);p.addProperty("userId",userId);if(groupId!=null)p.addProperty("groupId",groupId);if(target!=null)p.addProperty("targetId",target);p.addProperty("occurredAt",Instant.now().toString());String key=action.toLowerCase(Locale.ROOT)+":"+UUID.randomUUID().toString();try(PreparedStatement q=db.prepareStatement("INSERT INTO event_outbox(event_key,channel,payload,created_at) VALUES(?,?,?,NOW(3))")){q.setString(1,key);q.setString(2,channel);q.setString(3,p.toString());q.executeUpdate();}}

    private static long requireActor(RequestContext c){if(!c.authenticated())throw new GroupException("AUTH_REQUIRED");return c.userId();}
    private static long id(JsonObject o,String name){Long v=optionalId(o,name);if(v==null||v<=0)throw new GroupException("BAD_REQUEST",name+" không hợp lệ");return v;}
    private static Long optionalId(JsonObject o,String name){JsonElement e=o.get(name);try{return e!=null&&!e.isJsonNull()?e.getAsLong():null;}catch(Exception ex){throw new GroupException("BAD_REQUEST",name+" không hợp lệ");}}
    private static String string(JsonObject o,String name){JsonElement e=o.get(name);return e!=null&&e.isJsonPrimitive()?e.getAsString():"";}
    private static boolean bool(JsonObject o,String name,boolean fallback){JsonElement e=o.get(name);return e!=null&&e.isJsonPrimitive()?e.getAsBoolean():fallback;}
    private static JsonObject wrap(String name,JsonElement value){JsonObject o=new JsonObject();o.add(name,value);return o;}
    private static JsonObject okId(String name,long value){JsonObject o=new JsonObject();o.addProperty(name,value);return o;}
}
