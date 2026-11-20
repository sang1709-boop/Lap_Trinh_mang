package vn.ktd.taskhub.server.routing;

import com.google.gson.*;
import java.util.Map;
import java.util.function.Function;

public final class RequestRouter {
    private final Map<String, Function<JsonObject, JsonObject>> handlers = Map.of("PING", data -> {
        JsonObject result = new JsonObject();
        result.addProperty("message", "PONG");
        return result;
    });

    private static String field(JsonObject obj, String name) {
        JsonElement value = obj.get(name);
        return value instanceof JsonPrimitive p && p.isString() ? p.getAsString() : "";
    }

    public JsonObject route(JsonObject request) {
        String id = field(request, "requestId");
        String action = field(request, "action");
        JsonObject response = new JsonObject();
        response.addProperty("type", "response");
        response.addProperty("requestId", id);
        String error = null;
        if (!"request".equals(field(request, "type")) || id.isBlank() || action.isBlank()
                || !(request.get("data") instanceof JsonObject)) error = "BAD_REQUEST";
        else if (!handlers.containsKey(action)) error = "UNKNOWN_ACTION";
        else response.add("data", handlers.get(action).apply(request.getAsJsonObject("data")));
        response.addProperty("ok", error == null);
        if (error != null) response.addProperty("error", error);
        return response;
    }
}
