package vn.ktd.taskhub.server;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Properties;
import java.util.concurrent.*;
import vn.ktd.taskhub.shared.protocol.MessageCodec;
import vn.ktd.taskhub.server.routing.RequestRouter;

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
        try (var server = new ServerSocket(Integer.parseInt(config.getProperty("server.port")), 50, login ? InetAddress.getLoopbackAddress() : null)) {
            System.out.println("KTD TCP " + server.getLocalPort());
            while (true) {
                Socket socket = server.accept();
                try { workers.execute(() -> serve(socket, new RequestRouter(config, dbPassword, socket.getInetAddress().getHostAddress()))); }
                catch (RejectedExecutionException busy) { socket.close(); }
            }
        } finally { workers.shutdownNow(); }
    }

    private static void serve(Socket socket, RequestRouter router) {
        String reason = "DISCONNECT";
        try (socket; var in = new DataInputStream(socket.getInputStream());
                var out = new DataOutputStream(socket.getOutputStream())) {
            socket.setSoTimeout(90000);
            while (!router.finished()) MessageCodec.write(out, router.route(MessageCodec.read(in)));
        } catch (SocketTimeoutException timeout) {
            reason = "SESSION_TIMEOUT";
        } catch (EOFException closed) {
            // Client đã ngắt kết nối.
        } catch (IOException | java.sql.SQLException ex) { System.err.println(ex.getMessage()); }
        finally {
            try { router.close(reason); }
            catch (java.sql.SQLException ex) { System.err.println("Session cleanup: " + ex.getMessage()); }
        }
    }
}


