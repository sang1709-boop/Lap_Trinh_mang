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
        boolean login = args.length == 1 && "--login".equals(args[0]);
        JsonObject data = new JsonObject();
        if (login) {
            if (System.console() == null) throw new IOException("Run in an integrated terminal");
            data.addProperty("studentId", System.console().readLine("MSSV: "));
            char[] secret = System.console().readPassword("Mat khau sinh vien: ");
            if (secret == null) return;
            data.addProperty("password", new String(secret));
            Arrays.fill(secret, (char) 0);
        }
        String host = args.length > 0 && !login ? args[0] : config.getProperty("server.host");
        int port = Integer.parseInt(args.length > 1 ? args[1] : config.getProperty("server.port"));
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(5000);
            var id = UUID.randomUUID().toString();
            var request = new JsonObject();
            request.addProperty("type", "request");
            request.addProperty("requestId", id);
            request.addProperty("action", login ? "LOGIN" : "PING");
            request.add("data", data);
            MessageCodec.write(new DataOutputStream(socket.getOutputStream()), request);
            var response = MessageCodec.read(new DataInputStream(socket.getInputStream()));
            if (!"response".equals(response.get("type").getAsString())
                    || !id.equals(response.get("requestId").getAsString())
                    || (!login && (!response.get("ok").getAsBoolean()
                    || !"PONG".equals(response.getAsJsonObject("data").get("message").getAsString()))))
                throw new IOException("Phản hồi PING không hợp lệ");
            if (!login) System.out.println(response);
            else if (response.get("ok").getAsBoolean()) {
                System.out.println("Dang nhap thanh cong: " + response.getAsJsonObject("data").get("fullName").getAsString());
                System.console().readLine("Nhan Enter de dong ket noi.");
            } else System.out.println("LOGIN_FAILED".equals(response.get("error").getAsString())
                    ? "Khong the dang nhap voi thong tin da cung cap." : response.get("error").getAsString());
        }
    }
}

