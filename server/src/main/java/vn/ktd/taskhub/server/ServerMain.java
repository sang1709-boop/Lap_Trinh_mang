package vn.ktd.taskhub.server;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Properties;
import java.util.concurrent.*;
import vn.ktd.taskhub.shared.protocol.MessageCodec;
import vn.ktd.taskhub.server.routing.RequestRouter;
import vn.ktd.taskhub.server.routing.RequestContext;
import vn.ktd.taskhub.server.database.Database;
import vn.ktd.taskhub.server.feature.group.service.InvitationExpiryJob;

public class ServerMain {
    public static void main(String[] args) throws IOException {
        boolean login = args.length == 1 && "--login".equals(args[0]);
        Properties config = new Properties();
        try (var in = args.length > 0 && !login ? Files.newInputStream(Path.of(args[0]))
                : ServerMain.class.getResourceAsStream("/server.properties.example")) {
            if (in == null) throw new FileNotFoundException("server.properties.example");
            config.load(in);
        }
        String password = null;
        if (login) {
            if (System.console() == null) throw new IOException("Run in an integrated terminal");
            char[] secret = System.console().readPassword("MySQL password (%s): ", config.getProperty("db.user"));
            if (secret == null) return;
            password = new String(secret);
            java.util.Arrays.fill(secret, (char) 0);
        }
        final String dbPassword = password;
        var workers = new ThreadPoolExecutor(0, 64, 60, TimeUnit.SECONDS, new SynchronousQueue<>());
        ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
        if (dbPassword != null) timers.scheduleWithFixedDelay(
                new InvitationExpiryJob(new Database(config, dbPassword)), 0, 1, TimeUnit.MINUTES);
        try (var server = new ServerSocket(Integer.parseInt(config.getProperty("server.port")), 50, login ? InetAddress.getLoopbackAddress() : null)) {
            System.out.println("KTD TCP " + server.getLocalPort());
            while (true) {
                Socket socket = server.accept();
                try {
                    String socketId = java.util.UUID.randomUUID().toString();
                    String ip = socket.getInetAddress().getHostAddress();
                    workers.execute(() -> serve(socket, new RequestRouter(config, dbPassword,
                            new RequestContext(socketId, ip))));
                }
                catch (RejectedExecutionException busy) { socket.close(); }
            }
        } finally { workers.shutdownNow(); timers.shutdownNow(); }
    }

    private static void serve(Socket socket, RequestRouter router) {
        try (socket; var in = new DataInputStream(socket.getInputStream());
                var out = new DataOutputStream(socket.getOutputStream())) {
            socket.setSoTimeout(120000);
            while (true) MessageCodec.write(out, router.route(MessageCodec.read(in)));
        } catch (EOFException closed) {
            // Client đã ngắt kết nối.
        } catch (IOException ex) { System.err.println(ex.getMessage()); }
    }
}

