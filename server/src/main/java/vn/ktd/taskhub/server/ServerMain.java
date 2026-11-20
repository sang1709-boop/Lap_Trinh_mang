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
        Properties config = new Properties();
        try (var in = args.length > 0 ? Files.newInputStream(Path.of(args[0]))
                : ServerMain.class.getResourceAsStream("/server.properties.example")) {
            if (in == null) throw new FileNotFoundException("server.properties.example");
            config.load(in);
        }
        RequestRouter router = new RequestRouter();
        var workers = new ThreadPoolExecutor(0, 64, 60, TimeUnit.SECONDS, new SynchronousQueue<>());
        try (var server = new ServerSocket(Integer.parseInt(config.getProperty("server.port")))) {
            System.out.println("KTD TCP " + server.getLocalPort());
            while (true) {
                Socket socket = server.accept();
                try { workers.execute(() -> serve(socket, router)); }
                catch (RejectedExecutionException busy) { socket.close(); }
            }
        } finally { workers.shutdownNow(); }
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
