package vn.ktd.taskhub.shared.protocol;

import com.google.gson.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class MessageCodec {
    public static final int MAX_BYTES = 1024 * 1024;
    private MessageCodec() {}

    public static JsonObject read(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 1 || length > MAX_BYTES) throw new IOException("Kích thước bản tin không hợp lệ");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException ex) {
            throw new IOException("Bản tin JSON không hợp lệ", ex);
        }
    }

    public static void write(DataOutputStream out, JsonObject message) throws IOException {
        byte[] bytes = message.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Bản tin quá lớn");
        out.writeInt(bytes.length);
        out.write(bytes);
        out.flush();
    }
}
