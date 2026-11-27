package vn.ktd.taskhub.server.feature.account;

import com.google.gson.JsonObject;
import java.sql.DriverManager;
import java.util.Properties;
import java.util.concurrent.*;
import vn.ktd.taskhub.server.security.SocketSession;

/** Reply before any lookup so the public response cannot reveal email ownership by its timing. */
public final class ResetRequests {
    private static final ExecutorService worker=new ThreadPoolExecutor(1,2,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64),new ThreadPoolExecutor.AbortPolicy());
    public static JsonObject enqueue(Properties config,String dbPassword,SocketSession socket,JsonObject input){
        var data=new JsonObject();
        String student=AccountActions.text(input,"studentId"),email=AccountActions.text(input,"email");
        if(student.length()<=12 && email.length()<=254){
            data.addProperty("studentId",student);data.addProperty("email",email);
            // A detached context keeps a later login/logout on this socket from changing the public request.
            var context=new SocketSession(config,dbPassword,socket.ip());
            try {worker.execute(()->{
                try(var db=DriverManager.getConnection(config.getProperty("db.url"),config.getProperty("db.user"),dbPassword)){
                    new AccountActions(db,context,data).execute("RESET_REQUEST");
                }catch(Exception ex){System.err.println("Reset request: "+ex.getClass().getSimpleName());}
            });}catch(RejectedExecutionException full){System.err.println("Hàng đợi yêu cầu khôi phục đã đầy.");}
        }
        var out=new JsonObject();
        out.addProperty("message","Nếu thông tin phù hợp, mã xác nhận sẽ được gửi đến email đã đăng ký.");
        return out;
    }
}
