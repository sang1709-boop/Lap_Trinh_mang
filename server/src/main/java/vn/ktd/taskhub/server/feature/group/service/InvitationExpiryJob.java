package vn.ktd.taskhub.server.feature.group.service;

import com.google.gson.JsonObject;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import vn.ktd.taskhub.server.database.Database;

/** TV2 timer: closes direct invitations after seven days even when no client opens them. */
public final class InvitationExpiryJob implements Runnable {
    private final Database database;
    public InvitationExpiryJob(Database database){this.database=database;}

    @Override public void run(){
        try { database.transaction(db->{ expire(db); return null; }); }
        catch(Exception error){System.err.println("Invitation expiry: "+error.getMessage());}
    }

    private void expire(Connection db)throws SQLException{
        record Due(long invitationId,long groupId,long invitedBy){}
        List<Due> due=new ArrayList<>();
        try(PreparedStatement q=db.prepareStatement("SELECT invitation_id,group_id,invited_by FROM group_invitations WHERE invitation_status='pending' AND expires_at<=NOW(3) FOR UPDATE");ResultSet r=q.executeQuery()){
            while(r.next())due.add(new Due(r.getLong(1),r.getLong(2),r.getLong(3)));
        }
        try(PreparedStatement update=db.prepareStatement("UPDATE group_invitations SET invitation_status='expired',responded_at=NOW(3),close_reason='expired' WHERE invitation_id=? AND invitation_status='pending'");PreparedStatement outbox=db.prepareStatement("INSERT INTO event_outbox(event_key,channel,payload,created_at) VALUES(?,?,?,NOW(3))")){
            for(Due item:due){update.setLong(1,item.invitationId());if(update.executeUpdate()!=1)continue;JsonObject payload=new JsonObject();payload.addProperty("action","INVITE_EXPIRE");payload.addProperty("groupId",item.groupId());payload.addProperty("invitationId",item.invitationId());payload.addProperty("userId",item.invitedBy());payload.addProperty("occurredAt",Instant.now().toString());String key="invite-expire:"+item.invitationId();for(String channel:List.of("audit","notify")){outbox.setString(1,key);outbox.setString(2,channel);outbox.setString(3,payload.toString());outbox.addBatch();}}
            outbox.executeBatch();
        }
    }
}
