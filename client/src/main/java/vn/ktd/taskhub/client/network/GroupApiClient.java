package vn.ktd.taskhub.client.network;

import com.google.gson.JsonObject;
import java.io.*;
import java.net.*;
import java.util.UUID;
import vn.ktd.taskhub.shared.protocol.MessageCodec;

public final class GroupApiClient implements Closeable {
    public static final class ApiException extends IOException {
        private static final long serialVersionUID = 1L;
        private final String code;
        public ApiException(String code,String message){super(message==null||message.isBlank()?code:message);this.code=code;}
        public String code(){return code;}
    }

    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;

    public GroupApiClient(String host,int port)throws IOException{
        socket=new Socket();socket.connect(new InetSocketAddress(host,port),5000);socket.setSoTimeout(15000);
        input=new DataInputStream(socket.getInputStream());output=new DataOutputStream(socket.getOutputStream());
    }

    public JsonObject login(String studentId,String password)throws IOException{
        JsonObject d=new JsonObject();d.addProperty("studentId",studentId);d.addProperty("password",password);return request("LOGIN",d);
    }

    public synchronized JsonObject request(String action,JsonObject data)throws IOException{
        String id=UUID.randomUUID().toString();JsonObject req=new JsonObject();req.addProperty("type","request");req.addProperty("requestId",id);req.addProperty("action",action);req.add("data",data==null?new JsonObject():data);
        MessageCodec.write(output,req);JsonObject response=MessageCodec.read(input);
        if(!"response".equals(response.get("type").getAsString())||!id.equals(response.get("requestId").getAsString()))throw new IOException("Phản hồi không khớp requestId");
        if(!response.get("ok").getAsBoolean()){String code=response.get("error").getAsString();String message=response.has("message")?response.get("message").getAsString():code;throw new ApiException(code,message);}
        return response.getAsJsonObject("data");
    }

    @Override public void close()throws IOException{socket.close();}
}
