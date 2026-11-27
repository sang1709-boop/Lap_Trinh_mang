-- Dữ liệu thử TV4. Trong cùng kết nối, đặt @tv4_leader và @tv4_member bằng
-- MSSV của hai tài khoản thử đã đăng ký, sau đó chạy script trong CSDL demo.
USE ktd_task_hub;
DROP PROCEDURE IF EXISTS seed_tv4_demo;
DELIMITER //
CREATE PROCEDURE seed_tv4_demo()
BEGIN
    DECLARE leader_id,member_id,gid BIGINT UNSIGNED;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
    START TRANSACTION;
    SELECT user_id INTO leader_id FROM users WHERE student_id=@tv4_leader AND account_status='active' FOR UPDATE;
    SELECT user_id INTO member_id FROM users WHERE student_id=@tv4_member AND account_status='active' FOR UPDATE;
    IF leader_id IS NULL OR member_id IS NULL OR leader_id=member_id THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Can hai MSSV thu dang hoat dong va khac nhau';
    END IF;
    SELECT MAX(group_id) INTO gid FROM student_groups WHERE created_by=leader_id AND name='TV4 - Demo' AND group_status='active';
    IF gid IS NULL THEN
        INSERT INTO student_groups(name,created_by) VALUES('TV4 - Demo',leader_id);
        SET gid=LAST_INSERT_ID();
        INSERT INTO memberships(group_id,user_id,member_role) VALUES(gid,leader_id,'leader'),(gid,member_id,'member');
    ELSEIF NOT EXISTS(SELECT 1 FROM memberships WHERE group_id=gid AND active_user_key=member_id)
        OR NOT EXISTS(SELECT 1 FROM memberships WHERE group_id=gid AND active_user_key=leader_id AND member_role='leader') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Nhom demo hien tai co thanh vien khac';
    END IF;
    COMMIT;
    SELECT gid AS groupId,leader_id AS leaderId,member_id AS memberId;
END//
DELIMITER ;
CALL seed_tv4_demo();
DROP PROCEDURE seed_tv4_demo;
