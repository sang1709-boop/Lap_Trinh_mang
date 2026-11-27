package vn.ktd.taskhub.server.feature.account;

import com.google.gson.*;
import java.sql.*;
import vn.ktd.taskhub.shared.dto.AccountRules;
import vn.ktd.taskhub.server.feature.audit.AuditTrail;
import java.security.SecureRandom;
import java.util.*;
import org.mindrot.jbcrypt.BCrypt;
import vn.ktd.taskhub.server.security.*;
import static vn.ktd.taskhub.server.feature.account.Sql.*;

public final class AccountActions {
    private static final SecureRandom RANDOM=new SecureRandom();
    private final Connection db;
    private final SocketSession session;
    private final JsonObject in;
    private final JsonObject out=new JsonObject();
    private final List<Runnable> messages=new ArrayList<>();
    public AccountActions(Connection db, SocketSession session,JsonObject in){
        this.db=db;this.session=session;this.in=in;
    }
    static String text(JsonObject obj,String key){
        var value=obj.get(key);
        return value!=null && value.isJsonPrimitive()?value.getAsString():"";
    }
    String get(String key){return text(in,key);}
    static void require(boolean condition,String code){if(!condition)throw new IllegalArgumentException(code);}
    private long actor(){require(session.active(),"UNAUTHENTICATED");return session.authenticatedUser();}
    private String student(){String id=get("studentId");require(AccountRules.student(id),"BAD_STUDENT_ID");return id;}
    private String email(){String e=get("email").trim();require(AccountRules.email(e),"BAD_EMAIL");return e;}
    private String birthday(){return AccountRules.birthDate(get("birthDate")).toString();}
    private String name(){String n=get("fullName").trim();require(!n.isEmpty()&&n.length()<=100,"BAD_NAME");return n;}
    private String newHash(String id){
        String pw=get("password");
        require(pw.equals(get("confirmation")) && PasswordChange.valid(pw,id),"BAD_PASSWORD");
        return BCrypt.hashpw(pw, BCrypt.gensalt());
    }
    private void mail(String recipient,String body){messages.add(()->Mail.send(recipient,body));}
    private JsonObject profile(long id){
        try{return one(db,"SELECT student_id,full_name,email,birth_date FROM users WHERE user_id=?",id);}
        catch(SQLException ex){throw new IllegalStateException(ex);}
    }
    public JsonObject execute(String action) throws SQLException {
        db.setAutoCommit(false);
        try{
            // A single transaction lock keeps per-account and per-IP OTP quotas consistent.
            update(db,"INSERT INTO login_sources(subject_key,source_key) VALUES ('#tv1','#otp') ON DUPLICATE KEY UPDATE consecutive_failures=consecutive_failures");
            if(session.active()){
                require(one(db,"SELECT session_id FROM auth_sessions WHERE socket_id=? AND closed_at IS NULL AND expires_at>NOW(3) AND last_heartbeat_at>NOW(3)-INTERVAL 90 SECOND AND last_activity_at>NOW(3)-INTERVAL 30 MINUTE FOR UPDATE",session.socketId())!=null,"SESSION_EXPIRED");
            }
            switch(action){
                case "REGISTER" -> register();
                case "OTP_RESEND" -> resend();
                case "RESET_REQUEST" -> resetRequest();
                case "VERIFY_OTP" -> verify();
                case "PROFILE_GET" -> out.add("profile",profile(actor()));
                case "PROFILE_UPDATE" -> editProfile();
                case "EMAIL_CHANGE" -> changeEmail();
                case "AUDIT_LIST","AUDIT_EXPORT" -> AuditQuery.run(db,session,in,out,action.equals("AUDIT_EXPORT"));
                default -> throw new IllegalArgumentException("UNKNOWN_ACTION");
            }
            db.commit();
            messages.forEach(Runnable::run);
            return out;
        } catch(IllegalArgumentException ex){
            db.rollback();
            audit(action.equals("AUDIT_EXPORT")?"AUDIT_EXPORT_CSV_FAIL":action+"_FAIL",null,null,"failed",ex.getMessage());
            db.commit();
            throw ex;
        } catch(SQLException | RuntimeException ex){db.rollback();
            if (ex instanceof SQLIntegrityConstraintViolationException) {
                audit(action+"_FAIL",null,null,"failed","CONFLICT");
                db.commit();
            }
            throw ex;}
    }
    void audit(String action,JsonObject old,JsonObject value,String result,String reason)throws SQLException{
        Long actor=session.active()?session.authenticatedUser():null;
        String target=actor==null?get("studentId").substring(0,Math.min(12,get("studentId").length())):actor.toString();
        AuditTrail.append(db,session,actor,null,action,"account",target,old,value,reason);
    }
    private boolean directory(String id,String email)throws SQLException{
        return number(db,"SELECT COUNT(*) FROM faculty_directory WHERE student_id=? AND school_email=?",id,email)>0;
    }
    private void register()throws SQLException{
        String id=student(),e=email(),n=name(),dob=birthday(),hash=newHash(id);
        if(!directory(id,e)){
            update(db,"INSERT INTO identity_reconciliation(student_id,claimed_email,requested_at) VALUES(?,?,NOW(3))",id,e);
            out.addProperty("reconciliationId",number(db,"SELECT LAST_INSERT_ID()"));
            audit("REGISTER_FAIL",null,null,"failed","DIRECTORY_MISMATCH");return;
        }
        require(number(db,"SELECT COUNT(*) FROM users WHERE student_id=? OR email=?",id,e)==0,"ACCOUNT_EXISTS");
        update(db,"INSERT INTO registration_requests(student_id,school_email,full_name,birth_date,password_hash,created_at,expires_at) VALUES(?,?,?,?,?,NOW(3),NOW(3)+INTERVAL 24 HOUR)",id,e,n,dob,hash);
        long request=number(db,"SELECT LAST_INSERT_ID()");
        issue(id,e,"register",null,request);
        out.addProperty("requestId",request);
        audit("REGISTER_REQUEST",null,null,"success",null);
    }
    private void resend()throws SQLException{
        var row=one(db,"SELECT * FROM registration_requests WHERE request_id=? AND expires_at>NOW(3) AND closed_at IS NULL",get("requestId"));
        require(row!=null && BCrypt.checkpw(get("password"),text(row,"password_hash")),"INVALID_REQUEST");
        issue(text(row,"student_id"),text(row,"school_email"),"register",null,row.get("request_id").getAsLong());
    }
    private int hourly(String id,String kind)throws SQLException{
        return (int)Math.max(number(db,"SELECT COUNT(*) FROM security_events WHERE kind=? AND subject_key=? AND occurred_at>NOW(3)-INTERVAL 1 HOUR",kind,id),
            number(db,"SELECT COUNT(*) FROM security_events WHERE kind=? AND ip_address=INET6_ATON(?) AND occurred_at>NOW(3)-INTERVAL 1 HOUR",kind,session.ip()));
    }
    private void event(String id,String kind)throws SQLException{
        update(db,"INSERT INTO security_events(subject_key,ip_address,kind,occurred_at) VALUES(?,INET6_ATON(?),?,NOW(3))",id,session.ip(),kind);
    }
    private void issue(String id,String e,String purpose,Long user,Long request)throws SQLException{
        require(Mail.configured(),"SMTP_NOT_CONFIGURED");
        require(hourly(id,"otp_issued")<5 && hourly(id,"otp_failed")<10,"OTP_LIMIT");
        String owner=" AND (user_id=? OR registration_id IN (SELECT request_id FROM registration_requests WHERE student_id=?))";
        require(number(db,"SELECT COUNT(*) FROM otp_challenges WHERE purpose=? AND issued_at>NOW(3)-INTERVAL 60 SECOND"+owner,purpose,user,id)==0,"WAIT_60_SECONDS");
        update(db,"UPDATE otp_challenges SET revoked_at=NOW(3) WHERE consumed_at IS NULL AND revoked_at IS NULL AND purpose=?"+owner,purpose,user,id);
        String code=String.format(Locale.ROOT,"%06d",RANDOM.nextInt(1000000));
        update(db,"INSERT INTO otp_challenges(purpose,user_id,registration_id,recipient,code_digest,issued_at,expires_at) VALUES(?,?,?,?,?,NOW(3),NOW(3)+INTERVAL 5 MINUTE)",
            purpose,user,request,e,BCrypt.hashpw(code,BCrypt.gensalt()));
        long challenge=number(db,"SELECT LAST_INSERT_ID()");
        event(id,"otp_issued");
        mail(e,"Mục đích: "+purpose+"\nMã yêu cầu: "+challenge+"\nOTP: "+code+"\nHiệu lực 5 phút.");
    }
    private void resetRequest()throws SQLException{
        out.addProperty("message","Nếu thông tin phù hợp, mã xác nhận sẽ được gửi đến email đã đăng ký.");
        var row=one(db,"SELECT user_id,student_id,email FROM users WHERE student_id=? AND email=?",get("studentId"),get("email"));
        if(row!=null){
            try{issue(text(row,"student_id"),text(row,"email"),"reset_password",row.get("user_id").getAsLong(),null);}
            catch(IllegalArgumentException ignored){/* Public response is identical. */}
        }
        audit("PASSWORD_RESET_REQUEST",null,null,"success",null);
    }
    private void editProfile()throws SQLException{
        long user=actor();
        var old=profile(user);
        update(db,"UPDATE users SET full_name=?,birth_date=? WHERE user_id=?",name(),birthday(),user);
        var current=profile(user);
        audit("PROFILE_UPDATE",old,current,"success",null);out.add("profile",current);
    }
    private void changeEmail()throws SQLException{
        long user=actor();String e=email();
        var row=one(db,"SELECT * FROM users WHERE user_id=? FOR UPDATE",user);
        require(BCrypt.checkpw(get("password"),text(row,"password_hash")),"OLD_PASSWORD_INCORRECT");
        require(number(db,"SELECT COUNT(*) FROM users WHERE email=?",e)==0,"EMAIL_IN_USE");
        issue(text(row,"student_id"),e,"change_email",user,null);
        audit("EMAIL_CHANGE_REQUEST",null,null,"success",null);
    }
    private void verify()throws SQLException{
        String purpose=get("purpose");long challenge;
        try{challenge=Long.parseLong(get("challengeId"));}catch(NumberFormatException ex){throw new IllegalArgumentException("INVALID_OTP");}
        var row=one(db,"""
            SELECT c.*,CONVERT(c.code_digest USING ascii) AS hash,COALESCE(u.student_id,r.student_id) AS student
            FROM otp_challenges c LEFT JOIN users u ON u.user_id=c.user_id
            LEFT JOIN registration_requests r ON r.request_id=c.registration_id
            WHERE c.challenge_id=? AND c.purpose=? AND c.expires_at>NOW(3) AND c.revoked_at IS NULL
            AND c.consumed_at IS NULL AND c.failed_attempts<3
            AND (c.registration_id IS NULL OR (r.closed_at IS NULL AND r.expires_at>NOW(3))) FOR UPDATE
            """,challenge,purpose);
        require(row!=null,"INVALID_OTP");
        String id=text(row,"student"),e=text(row,"recipient");
        long user=row.get("user_id").isJsonNull()?0:row.get("user_id").getAsLong();
        if(purpose.equals("change_email"))require(actor()==user,"PERMISSION_DENIED");
        require(hourly(id,"otp_failed")<10,"OTP_LIMIT");
        if(!get("code").matches("[0-9]{6}") || !BCrypt.checkpw(get("code"),text(row,"hash"))){
            update(db,"UPDATE otp_challenges SET failed_attempts=failed_attempts+1 WHERE challenge_id=?",challenge);
            event(id,"otp_failed");audit("OTP_FAIL",null,null,"failed","INVALID_OTP");
            out.addProperty("error","INVALID_OTP");return;
        }
        if(purpose.equals("register")){
            require(directory(id,e),"DIRECTORY_MISMATCH");
            update(db,"INSERT INTO users(student_id,email,full_name,birth_date,password_hash) SELECT student_id,school_email,full_name,birth_date,password_hash FROM registration_requests WHERE request_id=?",row.get("registration_id").getAsLong());
            update(db,"UPDATE registration_requests SET closed_at=NOW(3),close_reason='ACTIVATED' WHERE (student_id=? OR school_email=?) AND closed_at IS NULL",id,e);
            long created=number(db,"SELECT user_id FROM users WHERE student_id=?",id);
            update(db,"INSERT INTO audit_logs(user_id,action,target_type,target_id,new_value,ip_address,socket_id,result) VALUES(?,'REGISTER','account',?,?,?, ?,'success')",created,created,profile(created).toString(),session.ip(),session.socketId());
        }else if(purpose.equals("reset_password")){
            require(number(db,"SELECT COUNT(*) FROM users WHERE user_id=? AND email=?",user,e)==1,"INVALID_OTP");
            update(db,"UPDATE users SET password_hash=?,failed_login_count=0,locked_until=NULL,account_status=IF(account_status='locked','active',account_status) WHERE user_id=?",newHash(id),user);
            update(db,"DELETE FROM login_sources WHERE subject_key=?",id);
            update(db,"UPDATE auth_sessions SET closed_at=NOW(3),close_reason='PASSWORD_RESET' WHERE user_id=? AND closed_at IS NULL",user);
            update(db,"UPDATE otp_challenges SET revoked_at=NOW(3) WHERE user_id=? AND challenge_id<>? AND consumed_at IS NULL AND revoked_at IS NULL",user,challenge);
            update(db,"INSERT INTO audit_logs(user_id,action,target_type,target_id,old_value,new_value,ip_address,socket_id,result) VALUES(?,'PASSWORD_RESET','account',?,JSON_QUOTE('đã thay đổi'),JSON_QUOTE('đã thay đổi'),?,?,'success')",user,user,session.ip(),session.socketId());mail(e,"Mật khẩu của bạn đã được đặt lại.");
        }else if(purpose.equals("change_email")){
            var old=profile(user);
            require(number(db,"SELECT COUNT(*) FROM users WHERE email=? AND user_id<>?",e,user)==0,"EMAIL_IN_USE");
            update(db,"UPDATE users SET email=? WHERE user_id=?",e,user);
            audit("PROFILE_UPDATE",old,profile(user),"success",null);
            mail(text(old,"email"),"Địa chỉ email tài khoản KTD đã thay đổi.");
        }else throw new IllegalArgumentException("BAD_PURPOSE");
        update(db,"UPDATE otp_challenges SET consumed_at=NOW(3) WHERE challenge_id=?",challenge);
        out.addProperty("message","Đã xác minh.");
    }
}




