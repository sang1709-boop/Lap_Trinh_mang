package vn.ktd.taskhub.client;

import java.io.*;
import java.net.*;
import java.util.*;
import com.google.gson.JsonObject;
import vn.ktd.taskhub.shared.protocol.MessageCodec;

public class ClientMain {
    public static void main(String[] args) throws IOException {
        Properties config = new Properties();
        try (var in = ClientMain.class.getResourceAsStream("/client.properties")) {
            if (in == null) throw new FileNotFoundException("client.properties");
            config.load(in);
        }
        String host = args.length > 0 ? args[0] : config.getProperty("server.host");
        int port = Integer.parseInt(args.length > 1 ? args[1] : config.getProperty("server.port"));
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(5000);
            var id = UUID.randomUUID().toString();
            var request = new JsonObject();
            request.addProperty("type", "request");
            request.addProperty("requestId", id);
            request.addProperty("action", "PING");
            request.add("data", new JsonObject());
            MessageCodec.write(new DataOutputStream(socket.getOutputStream()), request);
            var response = MessageCodec.read(new DataInputStream(socket.getInputStream()));
            if (!"response".equals(response.get("type").getAsString())
                    || !id.equals(response.get("requestId").getAsString())
                    || !response.get("ok").getAsBoolean()
                    || !"PONG".equals(response.getAsJsonObject("data").get("message").getAsString()))
                throw new IOException("Phản hồi PING không hợp lệ");
            System.out.println(response);
        }
    }
}
