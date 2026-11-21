package vn.ktd.taskhub.server.feature.group.handler;

import java.util.Set;
import vn.ktd.taskhub.server.feature.group.service.GroupException;
import vn.ktd.taskhub.server.feature.group.service.GroupService;
import vn.ktd.taskhub.server.routing.RequestHandler;

public final class GroupHandler {
    public static final Set<String> ACTIONS = Set.of(
        "GROUP_LIST","GROUP_DETAIL","GROUP_MEMBER_LIST","GROUP_INVITATION_LIST",
        "GROUP_JOIN_REQUEST_LIST","GROUP_BLOCK_LIST","GROUP_HANDOVER_LIST",
        "GROUP_CREATE","GROUP_UPDATE","GROUP_INVITE_CODE_ROTATE","GROUP_INVITE_CODE_REVOKE",
        "GROUP_DISBAND","GROUP_JOIN","GROUP_INVITE_CREATE","GROUP_INVITE_REVOKE",
        "GROUP_INVITE_RESPOND","GROUP_JOIN_REQUEST_DECIDE","GROUP_MEMBER_REMOVE",
        "GROUP_ROLE_TRANSFER","GROUP_LEAVE","GROUP_MEMBER_UNBLOCK",
        "GROUP_HANDOVER_REQUEST","GROUP_HANDOVER_DECIDE");

    private final GroupService service;
    public GroupHandler(GroupService service) { this.service=service; }
    public RequestHandler forAction(String value) {
        return (context,data) -> {
            try { return service.execute(value,context,data); }
            catch (GroupException error) { service.recordFailure(value,context,error.code()); throw error; }
        };
    }
}
