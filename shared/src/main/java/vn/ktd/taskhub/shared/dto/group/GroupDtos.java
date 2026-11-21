package vn.ktd.taskhub.shared.dto.group;

import java.util.List;

/** Wire DTOs shared by the Swing client and PH2 server handlers. */
public final class GroupDtos {
    private GroupDtos() {}

    public record GroupSummary(long groupId, String name, String description, String inviteCode,
            boolean approvalEnabled, String status, String role, int memberCount,
            String membershipStatus) {}

    public record Member(long userId, String studentId, String fullName, String role,
            boolean online, int openTaskCount) {}

    public record Invitation(long invitationId, long groupId, String groupName, long userId,
            String studentId, String fullName, String status, String expiresAt) {}

    public record JoinRequest(long requestId, long groupId, long userId, String studentId,
            String fullName, String status, String requestedAt) {}

    public record BlockedMember(long userId, String studentId, String fullName, String blockedAt) {}

    public record HandoverRequest(long requestId, long taskId, long assignmentId,
            long requestedBy, Long suggestedUserId, String reason, String status,
            String requestedAt) {}

    public record GroupDetail(GroupSummary group, List<Member> members) {}
}
