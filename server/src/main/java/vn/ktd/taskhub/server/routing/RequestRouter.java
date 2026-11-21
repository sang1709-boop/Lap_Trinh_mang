package vn.ktd.taskhub.server.routing;

import com.google.gson.*;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.mindrot.jbcrypt.BCrypt;
import vn.ktd.taskhub.server.database.Database;
import vn.ktd.taskhub.server.feature.group.handler.GroupHandler;
import vn.ktd.taskhub.server.feature.group.service.GroupException;
import vn.ktd.taskhub.server.feature.group.service.GroupService;

public final class RequestRouter {
    private final Map<String,RequestHandler> handlers=new HashMap<>();
    private final Properties config;
    private final String dbPassword;
    private final RequestContext context;

    public RequestRouter(Properties config,String dbPassword,RequestContext context) {
        this.config=config;this.dbPassword=dbPassword;this.context=context;
        handlers.put("PING",(ctx,data)->{JsonObject o=new JsonObject();o.addProperty("message","PONG");return o;});
        GroupHandler groups=new GroupHandler(new GroupService(new Database(config,dbPassword)));
        for(String action:GroupHandler.ACTIONS)handlers.put(action,groups.forAction(action));
    }

    public JsonObject route(JsonObject request) {
        String id=field(request,"requestId"),action=field(request,"action");JsonObject response=new JsonObject();
        response.addProperty("type","response");response.addProperty("requestId",id);String error=null;String message=null;
        try {
            if(!"request".equals(field(request,"type"))||id.isBlank()||action.isBlank()||!(request.get("data") instanceof JsonObject))throw new GroupException("BAD_REQUEST");
            JsonObject data=request.getAsJsonObject("data");JsonObject result;
            if("LOGIN".equals(action))result=login(data);
            else {RequestHandler handler=handlers.get(action);if(handler==null)throw new GroupException("UNKNOWN_ACTION");result=handler.handle(context,data);}
            response.add("data",result);response.addProperty("ok",true);
        } catch(GroupException ex){error=ex.code();message=ex.getMessage();}
        catch(Exception ex){error="SERVER_ERROR";message=ex.getMessage();}
        if(error!=null){response.addProperty("ok",false);response.addProperty("error",error);if(message!=null&&!message.equals(error))response.addProperty("message",message);}
        return response;
    }

    private JsonObject login(JsonObject data) {
        if(context.authenticated())throw new GroupException("ALREADY_LOGGED_IN");
        if(dbPassword==null)throw new GroupException("SERVER_ERROR","Server chưa bật chế độ database");
        String student=field(data,"studentId"),password=field(data,"password");if(student.isBlank()||password.isEmpty())throw new GroupException("BAD_REQUEST");
        try(var db=DriverManager.getConnection(config.getProperty("db.url"),config.getProperty("db.user"),dbPassword);
            var query=db.prepareStatement("SELECT user_id,full_name,password_hash,account_status FROM users WHERE student_id=?")){
            query.setString(1,student);try(var rows=query.executeQuery()){
                boolean found=rows.next();String hash=found?rows.getString("password_hash"):"$2a$10$0xXsFHYs6hQ.yHs5ZC9v4ermyXAZZL0dUEuNpgNfmY1KU1clfsItK";
                if(!BCrypt.checkpw(password,hash)||!found||!"active".equals(rows.getString("account_status")))throw new GroupException("LOGIN_FAILED");
                context.authenticate(rows.getLong("user_id"));JsonObject profile=new JsonObject();profile.addProperty("userId",context.userId());profile.addProperty("fullName",rows.getString("full_name"));return profile;
            }
        }catch(GroupException e){throw e;}catch(SQLException|IllegalArgumentException e){throw new GroupException("SERVER_ERROR",e.getMessage());}
    }

    private static String field(JsonObject obj,String name){JsonElement value=obj.get(name);return value instanceof JsonPrimitive p&&p.isString()?p.getAsString():"";}
}
