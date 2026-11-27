USE ktd_task_hub;
-- Install once on V13. Producers call these routines inside their business transaction.
DELIMITER //
CREATE PROCEDURE tv4_notify(IN k VARCHAR(100), IN uid BIGINT UNSIGNED, IN gid BIGINT UNSIGNED,
    IN kind VARCHAR(50), IN heading VARCHAR(200), IN msg TEXT, IN obj VARCHAR(50), IN tid BIGINT UNSIGNED)
proc: BEGIN
    DECLARE nid BIGINT UNSIGNED;
    IF uid IS NULL THEN LEAVE proc; END IF;
    INSERT INTO notifications(user_id,group_id,notification_type,title,body,target_type,target_id,event_key)
    VALUES(uid,gid,kind,heading,msg,obj,tid,k)
    ON DUPLICATE KEY UPDATE notification_id=LAST_INSERT_ID(notification_id);
    SET nid=LAST_INSERT_ID();
    INSERT INTO event_outbox(event_key,channel,payload,created_at)
    VALUES(CONCAT('bell:',nid),'notify',JSON_OBJECT('userId',uid,'notificationId',nid),NOW(3))
    ON DUPLICATE KEY UPDATE event_key=event_key;
    INSERT INTO audit_logs(group_id,action,target_type,target_id,new_value,result,event_key)
    SELECT gid,'NOTIFY_SENT','user',uid,JSON_OBJECT('kind',kind,'notificationId',nid),'success',CONCAT('notify:',nid)
    WHERE NOT EXISTS(SELECT 1 FROM audit_logs WHERE event_key=CONCAT('notify:',nid));
END//

CREATE FUNCTION tv4_leader(gid BIGINT UNSIGNED) RETURNS BIGINT UNSIGNED READS SQL DATA
RETURN (SELECT user_id FROM memberships WHERE group_id=gid AND active_leader_key=1)//

CREATE TRIGGER tv4_assignment AFTER INSERT ON task_assignments FOR EACH ROW
BEGIN
    IF NEW.is_current=1 THEN
        CALL tv4_notify(CONCAT('assign:',NEW.assignment_id),NEW.assignee_user_id,
            (SELECT group_id FROM tasks WHERE task_id=NEW.task_id),'task_assigned','Được giao công việc',
            (SELECT title FROM tasks WHERE task_id=NEW.task_id),'task',NEW.task_id);
    END IF;
END//

CREATE TRIGGER tv4_deadline_version BEFORE UPDATE ON tasks FOR EACH ROW
BEGIN
    IF NEW.deadline<>OLD.deadline THEN
        SET NEW.deadline_version=GREATEST(NEW.deadline_version,OLD.deadline_version+1);
    END IF;
END//

CREATE TRIGGER tv4_task_change AFTER UPDATE ON tasks FOR EACH ROW
BEGIN
    DECLARE change_key VARCHAR(100) DEFAULT UUID();
    DECLARE owner_id BIGINT UNSIGNED;
    DECLARE parent_owner BIGINT UNSIGNED;
    SELECT MAX(assignee_user_id) INTO owner_id FROM task_assignments WHERE current_task_key=NEW.task_id;
    IF OLD.deadline<>NEW.deadline OR OLD.task_status<>NEW.task_status OR OLD.estimated_hours<>NEW.estimated_hours
        OR NOT (OLD.deleted_at<=>NEW.deleted_at) OR OLD.title<>NEW.title OR NOT (OLD.description<=>NEW.description)
        OR OLD.priority<>NEW.priority OR OLD.task_type<>NEW.task_type THEN
        CALL tv4_notify(change_key,owner_id,NEW.group_id,'task_changed','Công việc thay đổi',
            CONCAT(OLD.title,' → ',NEW.title,' · ',OLD.task_status,' → ',NEW.task_status,' · Hạn ',OLD.deadline,' → ',NEW.deadline,
                ' · Giờ ',OLD.estimated_hours,' → ',NEW.estimated_hours),'task',NEW.task_id);
    END IF;
    IF OLD.task_status<>'completed' AND NEW.task_status='completed' THEN
        CALL tv4_notify(CONCAT('done:',change_key),tv4_leader(NEW.group_id),NEW.group_id,'task_completed',
            'Đã hoàn thành công việc',NEW.title,'task',NEW.task_id);
    END IF;
    IF NEW.parent_task_id IS NOT NULL AND OLD.deleted_at IS NULL AND OLD.task_status IN ('not_started','in_progress')
        AND (NEW.deleted_at IS NOT NULL OR NEW.task_status IN ('completed','cancelled'))
        AND EXISTS(SELECT 1 FROM tasks WHERE task_id=NEW.parent_task_id AND task_status='in_progress' AND deleted_at IS NULL)
        AND EXISTS(SELECT 1 FROM tasks WHERE parent_task_id=NEW.parent_task_id AND deleted_at IS NULL AND task_status='completed')
        AND NOT EXISTS(SELECT 1 FROM tasks WHERE parent_task_id=NEW.parent_task_id AND deleted_at IS NULL AND task_status IN ('not_started','in_progress')) THEN
        SELECT MAX(assignee_user_id) INTO parent_owner FROM task_assignments WHERE current_task_key=NEW.parent_task_id;
        CALL tv4_notify(CONCAT('parent:',change_key),parent_owner,NEW.group_id,'parent_ready','Việc cha chờ đóng',
            (SELECT title FROM tasks WHERE task_id=NEW.parent_task_id),'task',NEW.parent_task_id);
    END IF;
END//

CREATE TRIGGER tv4_membership AFTER UPDATE ON memberships FOR EACH ROW
BEGIN
    IF OLD.membership_status<>NEW.membership_status OR OLD.member_role<>NEW.member_role THEN
        CALL tv4_notify(UUID(),NEW.user_id,NEW.group_id,'membership','Tư cách thành viên thay đổi',
            CONCAT(NEW.membership_status,' · ',NEW.member_role),'group',NEW.group_id);
        IF NEW.membership_status='left' AND NOT (tv4_leader(NEW.group_id)<=>NEW.user_id) THEN
            CALL tv4_notify(UUID(),tv4_leader(NEW.group_id),NEW.group_id,'membership','Thành viên rời nhóm',
                (SELECT full_name FROM users WHERE user_id=NEW.user_id),'group',NEW.group_id);
        END IF;
    END IF;
END//

CREATE TRIGGER tv4_disband BEFORE UPDATE ON student_groups FOR EACH ROW
BEGIN
    DECLARE finished BOOL DEFAULT FALSE;
    DECLARE uid BIGINT UNSIGNED;
    DECLARE recipients CURSOR FOR SELECT user_id FROM memberships WHERE group_id=OLD.group_id AND membership_status='active';
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET finished=TRUE;
    IF OLD.group_status='active' AND NEW.group_status='disbanded' THEN
        OPEN recipients;
        send_loop: LOOP
            FETCH recipients INTO uid;
            IF finished THEN LEAVE send_loop; END IF;
            CALL tv4_notify(CONCAT('disband:',OLD.group_id),uid,OLD.group_id,'group_disbanded','Nhóm đã giải tán',OLD.name,'group',OLD.group_id);
        END LOOP;
        CLOSE recipients;
    END IF;
END//

CREATE TRIGGER tv4_invite AFTER INSERT ON group_invitations FOR EACH ROW
CALL tv4_notify(CONCAT('invite:',NEW.invitation_id),NEW.invited_user_id,NEW.group_id,'invitation','Lời mời vào nhóm',
    (SELECT name FROM student_groups WHERE group_id=NEW.group_id),'invitation',NEW.invitation_id)//
CREATE TRIGGER tv4_invite_response AFTER UPDATE ON group_invitations FOR EACH ROW
BEGIN
    IF NEW.invitation_status<>OLD.invitation_status THEN
        CALL tv4_notify(CONCAT('invite:',NEW.invitation_id,':',NEW.invitation_status),tv4_leader(NEW.group_id),NEW.group_id,
            'invitation','Trạng thái lời mời',NEW.invitation_status,'invitation',NEW.invitation_id);
    END IF;
END//
CREATE TRIGGER tv4_join_request AFTER INSERT ON group_join_requests FOR EACH ROW
CALL tv4_notify(CONCAT('join:',NEW.request_id),tv4_leader(NEW.group_id),NEW.group_id,'join_request','Yêu cầu gia nhập nhóm',
    (SELECT full_name FROM users WHERE user_id=NEW.user_id),'join_request',NEW.request_id)//
CREATE TRIGGER tv4_join_response AFTER UPDATE ON group_join_requests FOR EACH ROW
BEGIN
    IF NEW.request_status<>OLD.request_status THEN
        CALL tv4_notify(CONCAT('join:',NEW.request_id,':',NEW.request_status),NEW.user_id,NEW.group_id,'join_request',
            'Kết quả gia nhập nhóm',NEW.request_status,'join_request',NEW.request_id);
    END IF;
END//

CREATE TRIGGER tv4_handover AFTER INSERT ON handover_requests FOR EACH ROW
BEGIN
    DECLARE gid BIGINT UNSIGNED DEFAULT (SELECT group_id FROM tasks WHERE task_id=NEW.task_id);
    CALL tv4_notify(CONCAT('handover:',NEW.request_id),tv4_leader(gid),gid,'handover','Yêu cầu bàn giao',NEW.reason,'task',NEW.task_id);
END//
CREATE TRIGGER tv4_handover_response AFTER UPDATE ON handover_requests FOR EACH ROW
BEGIN
    IF NEW.status<>OLD.status THEN
        CALL tv4_notify(CONCAT('handover:',NEW.request_id,':',NEW.status),NEW.requested_by,
            (SELECT group_id FROM tasks WHERE task_id=NEW.task_id),'handover','Kết quả bàn giao',NEW.status,'task',NEW.task_id);
    END IF;
END//

CREATE TRIGGER tv4_evidence AFTER INSERT ON evidence_submissions FOR EACH ROW
BEGIN
    DECLARE gid BIGINT UNSIGNED DEFAULT (SELECT group_id FROM tasks WHERE task_id=NEW.task_id);
    CALL tv4_notify(CONCAT('submit:',NEW.submission_id),tv4_leader(gid),gid,'evidence_submitted','Minh chứng chờ chấm',
        (SELECT title FROM tasks WHERE task_id=NEW.task_id),'task',NEW.task_id);
END//
CREATE TRIGGER tv4_review AFTER UPDATE ON evidence_submissions FOR EACH ROW
BEGIN
    DECLARE finished BOOL DEFAULT FALSE;
    DECLARE uid BIGINT UNSIGNED;
    DECLARE gid BIGINT UNSIGNED DEFAULT (SELECT group_id FROM tasks WHERE task_id=NEW.task_id);
    DECLARE recipients CURSOR FOR SELECT user_id FROM memberships WHERE group_id=gid AND membership_status='active'
        AND user_id<>NEW.submitted_by;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET finished=TRUE;
    IF OLD.review_status<>NEW.review_status AND NEW.review_status IN ('approved','returned') THEN
        IF NEW.self_review=1 THEN
            OPEN recipients;
            send_loop: LOOP
                FETCH recipients INTO uid;
                IF finished THEN LEAVE send_loop; END IF;
                CALL tv4_notify(CONCAT('review:',NEW.submission_id),uid,gid,'self_review','Tự duyệt minh chứng',
                    NEW.review_status,'task',NEW.task_id);
            END LOOP;
            CLOSE recipients;
        ELSE
            CALL tv4_notify(CONCAT('review:',NEW.submission_id),NEW.submitted_by,gid,'evidence_review','Kết quả chấm minh chứng',
                CONCAT_WS(' · ',NEW.review_status,NEW.review_reason),'task',NEW.task_id);
        END IF;
    END IF;
END//

CREATE PROCEDURE tv4_comment_notice(IN cid BIGINT UNSIGNED, IN uid BIGINT UNSIGNED)
BEGIN
    DECLARE gid,tid,author BIGINT UNSIGNED;
    SELECT t.group_id,t.task_id,c.user_id INTO gid,tid,author FROM task_comments c JOIN tasks t USING(task_id)
        WHERE c.comment_id=cid AND c.hidden_at IS NULL AND t.deleted_at IS NULL;
    IF uid<>author AND EXISTS(SELECT 1 FROM memberships WHERE group_id=gid AND active_user_key=uid) THEN
        CALL tv4_notify(CONCAT('comment:',cid),uid,gid,'comment','Bình luận mới',
            (SELECT title FROM tasks WHERE task_id=tid),'task',tid);
    END IF;
END//
CREATE TRIGGER tv4_comment AFTER INSERT ON task_comments FOR EACH ROW
BEGIN
    CALL tv4_comment_notice(NEW.comment_id,(SELECT tv4_leader(group_id) FROM tasks WHERE task_id=NEW.task_id));
    CALL tv4_comment_notice(NEW.comment_id,(SELECT assignee_user_id FROM task_assignments WHERE current_task_key=NEW.task_id));
END//
CREATE TRIGGER tv4_mention AFTER INSERT ON comment_mentions FOR EACH ROW
CALL tv4_comment_notice(NEW.comment_id,NEW.user_id)//

CREATE TRIGGER tv4_chat AFTER INSERT ON chat_messages FOR EACH ROW
BEGIN
    DECLARE finished BOOL DEFAULT FALSE;
    DECLARE uid,nid BIGINT UNSIGNED;
    DECLARE recipients CURSOR FOR SELECT user_id FROM memberships WHERE group_id=NEW.group_id
        AND membership_status='active' AND user_id<>NEW.sender_user_id ORDER BY user_id;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET finished=TRUE;
    IF NEW.conversation_type='private' THEN
        CALL tv4_notify(CONCAT('dm:',NEW.message_id),NEW.recipient_user_id,NEW.group_id,'private_chat','Tin nhắn riêng',
            NEW.body,'message',NEW.message_id);
    ELSE
        OPEN recipients;
        send_loop: LOOP
            FETCH recipients INTO uid;
            IF finished THEN LEAVE send_loop; END IF;
            CALL tv4_notify(CONCAT('chat:',NEW.message_id),uid,NEW.group_id,'group_chat','Tin nhắn nhóm',
                (SELECT name FROM student_groups WHERE group_id=NEW.group_id),'group',NEW.group_id);
            SELECT notification_id INTO nid FROM notifications WHERE user_id=uid AND unread_group_chat=NEW.group_id FOR UPDATE;
            INSERT INTO group_chat_receipts(user_id,message_id,notification_id) VALUES(uid,NEW.message_id,nid);
            UPDATE notifications SET chat_count=chat_count+1,last_message_id=GREATEST(COALESCE(last_message_id,0),NEW.message_id)
                WHERE notification_id=nid;
        END LOOP;
        CLOSE recipients;
    END IF;
END//
DELIMITER ;
