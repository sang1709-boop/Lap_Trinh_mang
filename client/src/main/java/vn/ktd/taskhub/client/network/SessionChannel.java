package vn.ktd.taskhub.client.network;

import com.google.gson.JsonObject;
import java.io.*;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.*;
import vn.ktd.taskhub.shared.protocol.MessageCodec;

/** One reader owns the input stream so an idle desktop can receive pushed events. */
public final class SessionChannel {
    private final Socket socket;
    private final DataOutputStream out;
    private final ConcurrentMap<String,CompletableFuture<JsonObject>> pending=new ConcurrentHashMap<>();
    public SessionChannel(Socket socket,Runnable inboxChanged) throws IOException {
        this.socket=socket;
        this.out=new DataOutputStream(socket.getOutputStream());
        var in=new DataInputStream(socket.getInputStream());
        var reader=new Thread(()->{
            try {
                while(!socket.isClosed()) {
                    var msg=MessageCodec.read(in);
                    if("event".equals(msg.get("type").getAsString())) {
                        if("INBOX_CHANGED".equals(msg.get("action").getAsString())) inboxChanged.run();
                    } else {
                        var waiter=pending.remove(msg.get("requestId").getAsString());
                        if(waiter!=null) waiter.complete(msg);
                    }
                }
            } catch(IOException | RuntimeException ex) {
                try { socket.close(); } catch(IOException ignored) {}
                pending.forEach((id,f)->f.completeExceptionally(ex));
            }
        },"ktd-desktop-reader");
        reader.setDaemon(true); reader.start();
    }
    public JsonObject exchange(String action,JsonObject data) throws IOException {
        String id=UUID.randomUUID().toString();
        var msg=new JsonObject(); msg.addProperty("type","request"); msg.addProperty("requestId",id);
        msg.addProperty("action",action); msg.add("data",data);
        var waiter=new CompletableFuture<JsonObject>(); pending.put(id,waiter);
        try {
            if(socket.isClosed()) throw new EOFException();
            synchronized(out) { MessageCodec.write(out,msg); }
            return waiter.get(15,TimeUnit.SECONDS);
        } catch(InterruptedException ex) {
            Thread.currentThread().interrupt(); throw new IOException(ex);
        } catch(ExecutionException | TimeoutException ex) { throw new IOException(ex); }
        finally {pending.remove(id);}
    }
}
