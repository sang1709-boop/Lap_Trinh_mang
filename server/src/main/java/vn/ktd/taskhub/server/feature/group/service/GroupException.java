package vn.ktd.taskhub.server.feature.group.service;

public final class GroupException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    public GroupException(String code) { this(code, code); }
    public GroupException(String code, String message) {
        super(message);
        this.code = code;
    }
    public String code() { return code; }
}
