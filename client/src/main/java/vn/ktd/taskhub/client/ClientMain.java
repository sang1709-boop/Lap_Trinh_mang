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
            var response = exchange(socket, login ? "LOGIN" : "PING", data);
            if (!login) System.out.println(response);
            else if (response.get("ok").getAsBoolean()) {
                System.out.println("Dang nhap thanh cong: " + response.getAsJsonObject("data").get("fullName").getAsString());
                var timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
                var pulse = timer.scheduleWithFixedDelay(() -> {
                    try {
                        var reply = exchange(socket, "HEARTBEAT", new JsonObject());
                        if (!reply.get("ok").getAsBoolean()) throw new IOException("SESSION_EXPIRED");
                    } catch (IOException ex) {
                        System.err.println("Phien da ket thuc. Nhan Enter de thoat.");
                        try { socket.close(); } catch (IOException ignored) {}
                        throw new java.io.UncheckedIOException(ex);
                    }
                }, 30, 30, java.util.concurrent.TimeUnit.SECONDS);
                try {
                    while ("1".equals(System.console().readLine("1: Doi mat khau | Enter: Dang xuat > "))) {
                        if (socket.isClosed()) return;
                        var change = new JsonObject();
                        if (!readSecret(change, "oldPassword", "Mat khau hien tai")
                                || !readSecret(change, "newPassword", "Mat khau moi")
                                || !readSecret(change, "confirmation", "Nhap lai mat khau moi")) return;
                        var reply = exchange(socket, "CHANGE_PASSWORD", change);
                        if (reply.get("ok").getAsBoolean()) {
                            System.out.println("Da doi mat khau. Vui long dang nhap lai.");
                            return;
                        }
                        System.out.println(reply.get("error").getAsString());
                    }
                }
                finally { pulse.cancel(false); timer.shutdown(); }
                if (!socket.isClosed()) {
                    var reply = exchange(socket, "LOGOUT", new JsonObject());
                    System.out.println(reply.get("ok").getAsBoolean() ? "Da dang xuat." : reply.get("error"));
                }
            } else System.out.println("LOGIN_FAILED".equals(response.get("error").getAsString())
                    ? "Khong the dang nhap voi thong tin da cung cap." : response.get("error").getAsString());
        }
    }

    private static boolean readSecret(JsonObject data, String key, String prompt) {
        char[] secret = System.console().readPassword("%s: ", prompt);
        if (secret == null) return false;
        data.addProperty(key, new String(secret));
        Arrays.fill(secret, (char) 0);
        return true;
    }

    static JsonObject exchange(Socket socket, String action, JsonObject data) throws IOException {
        synchronized (socket) {
            String id = UUID.randomUUID().toString();
            var request = new JsonObject();
            request.addProperty("type", "request");
            request.addProperty("requestId", id);
            request.addProperty("action", action);
            request.add("data", data);
            MessageCodec.write(new DataOutputStream(socket.getOutputStream()), request);
            var response = MessageCodec.read(new DataInputStream(socket.getInputStream()));
            if (!"response".equals(response.get("type").getAsString())
                    || !id.equals(response.get("requestId").getAsString())) throw new IOException("Invalid response");
            return response;
        }
    }
}


