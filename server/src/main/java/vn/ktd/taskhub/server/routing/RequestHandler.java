package vn.ktd.taskhub.server.routing;

import com.google.gson.JsonObject;

@FunctionalInterface
public interface RequestHandler {
    JsonObject handle(RequestContext context, JsonObject data);
}
