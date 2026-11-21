-- Dữ liệu trình diễn PH2. Chạy sau database/ktd_task_hub.sql.
-- Mật khẩu của ba tài khoản là: password
USE ktd_task_hub;

SET @demo_hash = '$2a$10$HrRn4yGg20wZ4WMQ/HdineBWHMjBjKBL19RWqNB0A0XELCode3h06';

INSERT INTO users(student_id,full_name,email,password_hash,account_status)
VALUES
  ('20KTD001','Nguyễn Minh Anh','20ktd001@ktd.example',@demo_hash,'active'),
  ('20KTD002','Trần Quốc Bảo','20ktd002@ktd.example',@demo_hash,'active'),
  ('20KTD003','Lê Thu Hà','20ktd003@ktd.example',@demo_hash,'active')
ON DUPLICATE KEY UPDATE full_name=VALUES(full_name),account_status='active';

SET @leader_id = (SELECT user_id FROM users WHERE student_id='20KTD001');
SET @member_id = (SELECT user_id FROM users WHERE student_id='20KTD002');
SET @outsider_id = (SELECT user_id FROM users WHERE student_id='20KTD003');

INSERT INTO student_groups(name,description,invite_code,approval_enabled,created_by)
VALUES('Nhóm demo KTD','Nhóm mẫu dùng kiểm thử phân hệ quản lý nhóm','KTD2026DEMO1',1,@leader_id)
ON DUPLICATE KEY UPDATE group_id=LAST_INSERT_ID(group_id),description=VALUES(description),
  invite_code=VALUES(invite_code),approval_enabled=1,group_status='active',disbanded_at=NULL;
SET @group_id = LAST_INSERT_ID();

INSERT INTO memberships(group_id,user_id,member_role,membership_status)
SELECT @group_id,@leader_id,'leader','active'
WHERE NOT EXISTS(SELECT 1 FROM memberships WHERE group_id=@group_id AND user_id=@leader_id AND membership_status='active');

INSERT INTO memberships(group_id,user_id,member_role,membership_status)
SELECT @group_id,@member_id,'member','active'
WHERE NOT EXISTS(SELECT 1 FROM memberships WHERE group_id=@group_id AND user_id=@member_id AND membership_status='active');

INSERT INTO group_invitations(group_id,invited_user_id,invited_by,expires_at)
SELECT @group_id,@outsider_id,@leader_id,DATE_ADD(NOW(3),INTERVAL 7 DAY)
WHERE NOT EXISTS(SELECT 1 FROM group_invitations WHERE group_id=@group_id
  AND invited_user_id=@outsider_id AND invitation_status='pending');

SELECT 'PH2 demo ready' AS result, @group_id AS group_id, 'KTD2026DEMO1' AS invite_code;
