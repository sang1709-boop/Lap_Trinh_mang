package vn.ktd.taskhub.server.routing;

public final class RequestContext {
    private final String socketId;
    private final String ipAddress;
    private Long userId;

    public RequestContext(String socketId, String ipAddress) {
        this.socketId = socketId;
        this.ipAddress = ipAddress;
    }

    public String socketId() { return socketId; }
    public String ipAddress() { return ipAddress; }
    public Long userId() { return userId; }
    public void authenticate(long value) { userId = value; }
    public boolean authenticated() { return userId != null; }
}
