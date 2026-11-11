-- Khởi tạo database KTD Task Hub trong MySQL Workbench.
CREATE DATABASE IF NOT EXISTS ktd_task_hub CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE ktd_task_hub;
SET NAMES utf8mb4;

DELIMITER //
DROP PROCEDURE IF EXISTS ktd_task_hub.khoi_tao_ktd//
CREATE PROCEDURE ktd_task_hub.khoi_tao_ktd()
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema='ktd_task_hub') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Database ktd_task_hub da co bang. Script khoi tao can database trong.';
  END IF;

  CREATE TABLE `audit_logs` (
    `audit_id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `occurred_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `group_id` bigint unsigned DEFAULT NULL,
    `user_id` bigint unsigned DEFAULT NULL,
    `action` varchar(80) NOT NULL,
    `target_type` varchar(50) DEFAULT NULL,
    `target_id` varchar(100) DEFAULT NULL,
    `old_value` json DEFAULT NULL,
    `new_value` json DEFAULT NULL,
    `ip_address` varchar(45) DEFAULT NULL,
    `socket_id` varchar(128) DEFAULT NULL,
    `result` enum('success','failed') NOT NULL,
    `reason` varchar(500) DEFAULT NULL,
    `event_key` varchar(100) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    PRIMARY KEY (`audit_id`),
    UNIQUE KEY `uq_audit_event` (`event_key`),
    KEY `idx_audit_group_time` (`group_id`,`occurred_at`),
    KEY `idx_audit_actor_time` (`user_id`,`occurred_at`),
    KEY `idx_audit_target` (`target_type`,`target_id`,`occurred_at`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `auth_sessions` (
    `session_id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `user_id` bigint unsigned NOT NULL,
    `token_hash` varchar(255) DEFAULT NULL,
    `socket_id` varchar(128) NOT NULL,
    `ip_address` varchar(45) DEFAULT NULL,
    `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `last_heartbeat_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `expires_at` datetime(3) DEFAULT NULL,
    `closed_at` datetime(3) DEFAULT NULL,
    `last_activity_at` datetime(3) DEFAULT NULL,
    `close_reason` varchar(40) DEFAULT NULL,
    `active_token_hash` varbinary(255) GENERATED ALWAYS AS (if((`closed_at` is null),`token_hash`,NULL)) STORED,
    PRIMARY KEY (`session_id`),
    UNIQUE KEY `socket_id` (`socket_id`),
    UNIQUE KEY `uq_session_token` (`active_token_hash`),
    KEY `idx_sessions_user` (`user_id`,`closed_at`),
    CONSTRAINT `auth_sessions_chk_1` CHECK (((`closed_at` is not null) or ((`token_hash` is not null) and (`expires_at` is not null) and (`last_activity_at` is not null))))
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `availability_slots` (
    `slot_id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `group_id` bigint unsigned NOT NULL,
    `user_id` bigint unsigned NOT NULL,
    `weekday` tinyint unsigned NOT NULL,
    `start_minute` smallint unsigned NOT NULL,
    `end_minute` smallint unsigned NOT NULL,
    PRIMARY KEY (`slot_id`),
    KEY `fk_availability_user` (`user_id`),
    KEY `idx_availability_user` (`group_id`,`user_id`,`weekday`),
    CONSTRAINT `chk_slot_end` CHECK (((`end_minute` between 1 and 1440) and (`end_minute` > `start_minute`))),
    CONSTRAINT `chk_slot_start` CHECK ((`start_minute` <= 1439)),
    CONSTRAINT `chk_weekday` CHECK ((`weekday` between 0 and 6))
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `chat_messages` (
    `message_id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `group_id` bigint unsigned NOT NULL,
    `sender_user_id` bigint unsigned NOT NULL,
    `recipient_user_id` bigint unsigned DEFAULT NULL,
    `conversation_type` enum('group','private') NOT NULL,
    `body` varchar(1000) NOT NULL,
    `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`message_id`),
    KEY `fk_chat_recipient` (`recipient_user_id`),
    KEY `idx_group_chat` (`group_id`,`conversation_type`,`created_at`),
    KEY `idx_private_chat` (`group_id`,`sender_user_id`,`recipient_user_id`,`created_at`),
    KEY `idx_sender_rate` (`sender_user_id`,`created_at`),
    CONSTRAINT `chk_chat_body` CHECK ((char_length(trim(`body`)) between 1 and 1000)),
    CONSTRAINT `chk_chat_recipient` CHECK ((((`conversation_type` = _utf8mb4'group') and (`recipient_user_id` is null)) or ((`conversation_type` = _utf8mb4'private') and (`recipient_user_id` is not null) and (`recipient_user_id` <> `sender_user_id`))))
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `chat_read_cursors` (
    `user_id` bigint unsigned NOT NULL,
    `group_id` bigint unsigned NOT NULL,
    `last_seen_message_id` bigint unsigned NOT NULL,
    PRIMARY KEY (`user_id`,`group_id`),
    KEY `group_id` (`group_id`),
    KEY `last_seen_message_id` (`last_seen_message_id`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `comment_mentions` (
    `comment_id` bigint unsigned NOT NULL,
    `user_id` bigint unsigned NOT NULL,
    PRIMARY KEY (`comment_id`,`user_id`),
    KEY `user_id` (`user_id`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `deadline_alerts` (
    `alert_id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `task_id` bigint unsigned NOT NULL,
    `user_id` bigint unsigned NOT NULL,
    `alert_level` enum('safe','warning','danger') NOT NULL,
    `threshold_key` varchar(32) NOT NULL,
    `sent_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `round_no` int unsigned NOT NULL DEFAULT '1',
    `deadline_version` int unsigned NOT NULL DEFAULT '1',
    `alert_scope` enum('leaf','parent_close') NOT NULL DEFAULT 'leaf',
    PRIMARY KEY (`alert_id`),
    UNIQUE KEY `uq_deadline_alert` (`task_id`,`user_id`,`round_no`,`deadline_version`,`alert_scope`,`threshold_key`),
    KEY `fk_alert_user` (`user_id`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `event_outbox` (
    `event_key` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `channel` enum('audit','notify','email') NOT NULL,
    `payload` json NOT NULL,
    `created_at` datetime(3) NOT NULL,
    `delivered_at` datetime(3) DEFAULT NULL,
    `attempts` int unsigned NOT NULL DEFAULT '0',
    PRIMARY KEY (`event_key`,`channel`),
    KEY `channel` (`channel`,`delivered_at`,`created_at`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `evidence_files` (
    `submission_id` bigint unsigned NOT NULL,
    `slot_no` tinyint unsigned NOT NULL,
    `file_name` varchar(255) NOT NULL,
    `content_type` varchar(127) NOT NULL,
    `file_size_bytes` int unsigned NOT NULL,
    `storage_path` varchar(1024) NOT NULL,
    `sha256` binary(32) DEFAULT NULL,
    PRIMARY KEY (`submission_id`,`slot_no`),
    CONSTRAINT `evidence_files_chk_1` CHECK ((`slot_no` between 1 and 5)),
    CONSTRAINT `evidence_files_chk_2` CHECK ((`file_size_bytes` between 1 and 20971520))
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `evidence_submissions` (
    `submission_id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `task_id` bigint unsigned NOT NULL,
    `assignment_id` bigint unsigned NOT NULL,
    `round_no` int unsigned NOT NULL,
    `version_no` int unsigned NOT NULL,
    `submitted_by` bigint unsigned NOT NULL,
    `submitted_at` datetime(3) NOT NULL,
    `review_status` enum('pending','approved','returned','superseded') NOT NULL DEFAULT 'pending',
    `reviewed_by` bigint unsigned DEFAULT NULL,
    `reviewed_at` datetime(3) DEFAULT NULL,
    `review_reason` varchar(500) DEFAULT NULL,
    `self_review` tinyint(1) NOT NULL DEFAULT '0',
    `superseded_at` datetime(3) DEFAULT NULL,
    PRIMARY KEY (`submission_id`),
    UNIQUE KEY `assignment_id` (`assignment_id`,`round_no`,`version_no`),
    UNIQUE KEY `submission_id` (`submission_id`,`task_id`),
    KEY `assignment_id_2` (`assignment_id`,`task_id`,`submitted_by`),
    KEY `submitted_by` (`submitted_by`),
    KEY `reviewed_by` (`reviewed_by`),
    CONSTRAINT `evidence_submissions_chk_1` CHECK (((`round_no` > 0) and (`version_no` > 0))),
    CONSTRAINT `evidence_submissions_chk_2` CHECK (((`review_status` not in (_utf8mb4'approved',_utf8mb4'returned')) or ((`reviewed_by` is not null) and (`reviewed_at` is not null)))),
    CONSTRAINT `evidence_submissions_chk_3` CHECK (((`review_status` <> _utf8mb4'returned') or ((`review_reason` is not null) and (char_length(trim(`review_reason`)) > 0)))),
    CONSTRAINT `evidence_submissions_chk_4` CHECK (((`review_status` not in (_utf8mb4'approved',_utf8mb4'returned')) or (`self_review` = (`reviewed_by` = `submitted_by`))))
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `faculty_directory` (
    `student_id` varchar(12) NOT NULL,
    `school_email` varchar(254) NOT NULL,
    `verified_source` varchar(255) NOT NULL,
    `imported_at` datetime(3) NOT NULL,
    PRIMARY KEY (`student_id`),
    UNIQUE KEY `school_email` (`school_email`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `group_blocks` (
    `group_id` bigint unsigned NOT NULL,
    `user_id` bigint unsigned NOT NULL,
    `blocked_by` bigint unsigned NOT NULL,
    `blocked_at` datetime(3) NOT NULL,
    PRIMARY KEY (`group_id`,`user_id`),
    KEY `user_id` (`user_id`),
    KEY `blocked_by` (`blocked_by`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `group_chat_receipts` (
    `user_id` bigint unsigned NOT NULL,
    `message_id` bigint unsigned NOT NULL,
    `notification_id` bigint unsigned NOT NULL,
    PRIMARY KEY (`user_id`,`message_id`),
    KEY `message_id` (`message_id`),
    KEY `notification_id` (`notification_id`,`message_id`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `group_invitations` (
    `invitation_id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `group_id` bigint unsigned NOT NULL,
    `invited_user_id` bigint unsigned NOT NULL,
    `invited_by` bigint unsigned NOT NULL,
    `invitation_status` enum('pending','accepted','declined','expired','revoked') NOT NULL DEFAULT 'pending',
    `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `responded_at` datetime(3) DEFAULT NULL,
    `expires_at` datetime(3) NOT NULL,
    `pending_user_key` bigint unsigned GENERATED ALWAYS AS ((case when (`invitation_status` = _utf8mb4'pending') then `invited_user_id` else NULL end)) STORED,
    `close_reason` varchar(64) DEFAULT NULL,
    PRIMARY KEY (`invitation_id`),
    UNIQUE KEY `uq_pending_invite` (`group_id`,`pending_user_key`),
    KEY `fk_invite_user` (`invited_user_id`),
    KEY `fk_invite_actor` (`invited_by`),
    KEY `invitation_status` (`invitation_status`,`expires_at`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `group_join_requests` (
    `request_id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `group_id` bigint unsigned NOT NULL,
    `user_id` bigint unsigned NOT NULL,
    `request_status` enum('pending','approved','rejected','cancelled') NOT NULL DEFAULT 'pending',
    `requested_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `decided_by` bigint unsigned DEFAULT NULL,
    `decided_at` datetime(3) DEFAULT NULL,
    `pending_user_key` bigint unsigned GENERATED ALWAYS AS ((case when (`request_status` = _utf8mb4'pending') then `user_id` else NULL end)) STORED,
    `close_reason` varchar(64) DEFAULT NULL,
    PRIMARY KEY (`request_id`),
    UNIQUE KEY `uq_pending_join` (`group_id`,`pending_user_key`),
    KEY `fk_join_user` (`user_id`),
    KEY `fk_join_decider` (`decided_by`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `handover_requests` (
    `request_id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `task_id` bigint unsigned NOT NULL,
    `assignment_id` bigint unsigned NOT NULL,
    `requested_by` bigint unsigned NOT NULL,
    `suggested_user_id` bigint unsigned DEFAULT NULL,
    `reason` varchar(500) NOT NULL,
    `status` enum('pending','approved','rejected','superseded') NOT NULL DEFAULT 'pending',
    `requested_at` datetime(3) NOT NULL,
    `decided_by` bigint unsigned DEFAULT NULL,
    `decided_at` datetime(3) DEFAULT NULL,
    `decision_reason` varchar(500) DEFAULT NULL,
    `pending_assignment` bigint unsigned GENERATED ALWAYS AS (if((`status` = _utf8mb4'pending'),`assignment_id`,NULL)) STORED,
    PRIMARY KEY (`request_id`),
    UNIQUE KEY `pending_assignment` (`pending_assignment`),
    KEY `assignment_id` (`assignment_id`,`task_id`,`requested_by`),
    KEY `requested_by` (`requested_by`),
    KEY `suggested_user_id` (`suggested_user_id`),
    KEY `decided_by` (`decided_by`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `identity_reconciliation` (
    `ticket_id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `student_id` varchar(12) NOT NULL,
    `claimed_email` varchar(254) NOT NULL,
    `requested_at` datetime(3) NOT NULL,
    `resolved_at` datetime(3) DEFAULT NULL,
    PRIMARY KEY (`ticket_id`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `login_sources` (
    `subject_key` varchar(128) NOT NULL,
    `source_key` varchar(128) NOT NULL,
    `consecutive_failures` int unsigned NOT NULL DEFAULT '0',
    `locked_until` datetime(3) DEFAULT NULL,
    PRIMARY KEY (`subject_key`,`source_key`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `memberships` (
    `membership_id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `group_id` bigint unsigned NOT NULL,
    `user_id` bigint unsigned NOT NULL,
    `member_role` enum('leader','member') NOT NULL DEFAULT 'member',
    `membership_status` enum('active','removed','left') NOT NULL DEFAULT 'active',
    `joined_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `ended_at` datetime(3) DEFAULT NULL,
    `active_user_key` bigint unsigned GENERATED ALWAYS AS ((case when (`membership_status` = _utf8mb4'active') then `user_id` else NULL end)) STORED,
    `active_leader_key` tinyint GENERATED ALWAYS AS ((case when ((`member_role` = _utf8mb4'leader') and (`membership_status` = _utf8mb4'active')) then 1 else NULL end)) STORED,
    PRIMARY KEY (`membership_id`),
    UNIQUE KEY `uq_active_member` (`group_id`,`active_user_key`),
    UNIQUE KEY `uq_active_leader` (`group_id`,`active_leader_key`),
    KEY `idx_membership_user` (`user_id`,`membership_status`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `notifications` (
    `notification_id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `user_id` bigint unsigned NOT NULL,
    `group_id` bigint unsigned DEFAULT NULL,
    `notification_type` varchar(50) NOT NULL,
    `title` varchar(200) NOT NULL,
    `body` text NOT NULL,
    `target_type` varchar(50) DEFAULT NULL,
    `target_id` bigint unsigned DEFAULT NULL,
    `is_read` tinyint(1) NOT NULL DEFAULT '0',
    `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `read_at` datetime(3) DEFAULT NULL,
    `event_key` varchar(100) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    `chat_count` int unsigned NOT NULL DEFAULT '0',
    `last_message_id` bigint unsigned DEFAULT NULL,
    `unread_group_chat` bigint unsigned GENERATED ALWAYS AS (if(((`notification_type` = _utf8mb4'group_chat') and (`is_read` = 0) and (`event_key` is not null)),`group_id`,NULL)) STORED,
    PRIMARY KEY (`notification_id`),
    UNIQUE KEY `uq_notify_event` (`user_id`,`event_key`),
    UNIQUE KEY `uq_unread_chat` (`user_id`,`unread_group_chat`),
    KEY `fk_notification_group` (`group_id`),
    KEY `idx_notifications_inbox` (`user_id`,`is_read`,`created_at`),
    KEY `last_message_id` (`last_message_id`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `otp_challenges` (
    `challenge_id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `purpose` enum('register','reset_password','change_email') NOT NULL,
    `registration_id` bigint unsigned DEFAULT NULL,
    `user_id` bigint unsigned DEFAULT NULL,
    `recipient` varchar(254) NOT NULL,
    `code_digest` varbinary(128) NOT NULL,
    `issued_at` datetime(3) NOT NULL,
    `expires_at` datetime(3) NOT NULL,
    `failed_attempts` tinyint unsigned NOT NULL DEFAULT '0',
    `consumed_at` datetime(3) DEFAULT NULL,
    `revoked_at` datetime(3) DEFAULT NULL,
    PRIMARY KEY (`challenge_id`),
    KEY `registration_id` (`registration_id`,`purpose`,`issued_at`),
    KEY `user_id` (`user_id`,`purpose`,`issued_at`),
    CONSTRAINT `otp_challenges_chk_1` CHECK ((((`purpose` = _utf8mb4'register') and (`registration_id` is not null) and (`user_id` is null)) or ((`purpose` <> _utf8mb4'register') and (`registration_id` is null) and (`user_id` is not null)))),
    CONSTRAINT `otp_challenges_chk_2` CHECK ((`failed_attempts` <= 3)),
    CONSTRAINT `otp_challenges_chk_3` CHECK (((`expires_at` > `issued_at`) and (`expires_at` <= (`issued_at` + interval 5 minute))))
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `password_reset_requests` (
    `reset_id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `user_id` bigint unsigned NOT NULL,
    `code_hash` varchar(255) NOT NULL,
    `expires_at` datetime(3) NOT NULL,
    `failed_attempts` tinyint unsigned NOT NULL DEFAULT '0',
    `requested_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `used_at` datetime(3) DEFAULT NULL,
    PRIMARY KEY (`reset_id`),
    KEY `idx_reset_user` (`user_id`,`requested_at`),
    CONSTRAINT `chk_reset_attempts` CHECK ((`failed_attempts` <= 3))
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `personal_capacity` (
    `user_id` bigint unsigned NOT NULL,
    `period_type` enum('week','month') NOT NULL,
    `period_start` date NOT NULL,
    `available_hours` decimal(8,2) NOT NULL,
    `updated_at` datetime(3) NOT NULL,
    PRIMARY KEY (`user_id`,`period_type`,`period_start`),
    CONSTRAINT `personal_capacity_chk_1` CHECK ((`available_hours` > 0)),
    CONSTRAINT `personal_capacity_chk_2` CHECK ((((`period_type` = _utf8mb4'week') and (weekday(`period_start`) = 0)) or ((`period_type` = _utf8mb4'month') and (dayofmonth(`period_start`) = 1))))
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `registration_requests` (
    `request_id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `student_id` varchar(12) NOT NULL,
    `school_email` varchar(254) NOT NULL,
    `full_name` varchar(100) NOT NULL,
    `birth_date` date NOT NULL,
    `password_hash` varchar(255) NOT NULL,
    `created_at` datetime(3) NOT NULL,
    `expires_at` datetime(3) NOT NULL,
    `closed_at` datetime(3) DEFAULT NULL,
    `close_reason` varchar(40) DEFAULT NULL,
    PRIMARY KEY (`request_id`),
    KEY `student_id` (`student_id`,`school_email`,`closed_at`),
    KEY `expires_at` (`expires_at`,`closed_at`),
    CONSTRAINT `registration_requests_chk_1` CHECK (((`expires_at` > `created_at`) and (`expires_at` <= (`created_at` + interval 24 hour))))
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `schema_info` (
    `version` int unsigned NOT NULL,
    `applied_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `description` varchar(255) NOT NULL,
    PRIMARY KEY (`version`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `security_events` (
    `event_id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `subject_key` varchar(128) NOT NULL,
    `ip_address` varbinary(16) NOT NULL,
    `kind` enum('otp_issued','otp_failed','login_failed','lock_email','join_code_failed') NOT NULL,
    `occurred_at` datetime(3) NOT NULL,
    PRIMARY KEY (`event_id`),
    KEY `subject_key` (`subject_key`,`kind`,`occurred_at`),
    KEY `ip_address` (`ip_address`,`kind`,`occurred_at`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `student_groups` (
    `group_id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `name` varchar(50) NOT NULL,
    `description` varchar(255) DEFAULT NULL,
    `invite_code` varchar(32) DEFAULT NULL,
    `approval_enabled` tinyint(1) NOT NULL DEFAULT '1',
    `group_status` enum('active','disbanded') NOT NULL DEFAULT 'active',
    `created_by` bigint unsigned NOT NULL,
    `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `disbanded_at` datetime(3) DEFAULT NULL,
    `code_revoked_at` datetime(3) DEFAULT NULL,
    PRIMARY KEY (`group_id`),
    UNIQUE KEY `invite_code` (`invite_code`),
    KEY `fk_groups_creator` (`created_by`),
    CONSTRAINT `chk_group_name` CHECK ((char_length(trim(`name`)) between 3 and 50)),
    CONSTRAINT `student_groups_chk_1` CHECK (((`invite_code` is null) or regexp_like(`invite_code`,_utf8mb4'^[A-Za-z0-9]{12}$',_utf8mb4'c')))
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `subtasks` (
    `subtask_id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `task_id` bigint unsigned NOT NULL,
    `title` varchar(100) NOT NULL,
    `deadline` datetime(3) NOT NULL,
    `estimated_hours` decimal(7,2) DEFAULT NULL,
    `assignee_user_id` bigint unsigned DEFAULT NULL,
    `subtask_status` enum('not_started','in_progress','completed') NOT NULL DEFAULT 'not_started',
    `sort_order` int NOT NULL DEFAULT '0',
    `created_by` bigint unsigned NOT NULL,
    `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `completed_at` datetime(3) DEFAULT NULL,
    PRIMARY KEY (`subtask_id`),
    KEY `fk_subtask_assignee` (`assignee_user_id`),
    KEY `fk_subtask_creator` (`created_by`),
    KEY `idx_subtasks_task_order` (`task_id`,`sort_order`),
    CONSTRAINT `chk_subtask_hours` CHECK (((`estimated_hours` is null) or (`estimated_hours` > 0))),
    CONSTRAINT `chk_subtask_title` CHECK ((char_length(trim(`title`)) between 1 and 100))
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `task_assignments` (
    `assignment_id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `task_id` bigint unsigned NOT NULL,
    `assignee_user_id` bigint unsigned NOT NULL,
    `assigned_by` bigint unsigned DEFAULT NULL,
    `assigned_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `unassigned_at` datetime(3) DEFAULT NULL,
    `is_current` tinyint(1) NOT NULL DEFAULT '1',
    `current_task_key` bigint unsigned GENERATED ALWAYS AS ((case when (`is_current` = true) then `task_id` else NULL end)) STORED,
    `round_no` int unsigned NOT NULL DEFAULT '1',
    `change_kind` enum('legacy','initial','reassign','handover','reopen') NOT NULL DEFAULT 'initial',
    `change_reason` varchar(500) DEFAULT NULL,
    PRIMARY KEY (`assignment_id`),
    UNIQUE KEY `uq_assignment_task` (`assignment_id`,`task_id`),
    UNIQUE KEY `uq_assignment_owner` (`assignment_id`,`task_id`,`assignee_user_id`),
    UNIQUE KEY `uq_current_assignment` (`current_task_key`),
    KEY `fk_assignment_task` (`task_id`),
    KEY `fk_assignment_actor` (`assigned_by`),
    KEY `idx_assignments_user_current` (`assignee_user_id`,`is_current`),
    CONSTRAINT `task_assignments_chk_1` CHECK (((`change_kind` = _utf8mb4'legacy') or (`assigned_by` is not null)))
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `task_comments` (
    `comment_id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `task_id` bigint unsigned NOT NULL,
    `user_id` bigint unsigned NOT NULL,
    `body` varchar(500) NOT NULL,
    `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `hidden_at` datetime(3) DEFAULT NULL,
    `hidden_by` bigint unsigned DEFAULT NULL,
    PRIMARY KEY (`comment_id`),
    KEY `fk_comment_moderator` (`hidden_by`),
    KEY `idx_task_comments` (`task_id`,`created_at`),
    KEY `idx_comment_rate` (`user_id`,`created_at`),
    CONSTRAINT `chk_comment_body` CHECK ((char_length(trim(`body`)) between 1 and 500))
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `task_evidence` (
    `evidence_id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `task_id` bigint unsigned NOT NULL,
    `subtask_id` bigint unsigned DEFAULT NULL,
    `uploaded_by` bigint unsigned NOT NULL,
    `file_name` varchar(255) NOT NULL,
    `content_type` varchar(127) NOT NULL,
    `file_size_bytes` bigint unsigned NOT NULL,
    `storage_path` varchar(1024) NOT NULL,
    `sha256` char(64) DEFAULT NULL,
    `version` int unsigned NOT NULL DEFAULT '1',
    `note` text,
    `uploaded_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`evidence_id`),
    KEY `fk_evidence_subtask` (`subtask_id`),
    KEY `fk_evidence_user` (`uploaded_by`),
    KEY `idx_evidence_task` (`task_id`,`uploaded_at`),
    CONSTRAINT `chk_evidence_size` CHECK ((`file_size_bytes` between 1 and 20971520))
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `task_revisions` (
    `revision_id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `task_id` bigint unsigned NOT NULL,
    `effective_at` datetime(3) NOT NULL,
    `reason` varchar(500) NOT NULL,
    `actor_id` bigint unsigned DEFAULT NULL,
    `parent_task_id` bigint unsigned DEFAULT NULL,
    `assignment_id` bigint unsigned NOT NULL,
    `round_no` int unsigned NOT NULL,
    `task_type` enum('simple','multi_stage') NOT NULL,
    `task_status` enum('not_started','in_progress','completed','cancelled') NOT NULL,
    `hidden` tinyint(1) NOT NULL,
    `estimated_hours` decimal(7,2) NOT NULL,
    `deadline` datetime(3) NOT NULL,
    `deadline_version` int unsigned NOT NULL,
    `submission_id` bigint unsigned DEFAULT NULL,
    `review_state` enum('pending','approved','returned','superseded') DEFAULT NULL,
    `self_review` tinyint(1) NOT NULL DEFAULT '0',
    `submitted_at` datetime(3) DEFAULT NULL,
    `closed_at` datetime(3) DEFAULT NULL,
    `late_at_close` tinyint(1) DEFAULT NULL,
    PRIMARY KEY (`revision_id`),
    KEY `parent_task_id` (`parent_task_id`),
    KEY `assignment_id` (`assignment_id`,`task_id`),
    KEY `submission_id` (`submission_id`,`task_id`),
    KEY `actor_id` (`actor_id`),
    KEY `task_id` (`task_id`,`effective_at`,`revision_id`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `task_status_history` (
    `history_id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `task_id` bigint unsigned NOT NULL,
    `changed_by` bigint unsigned NOT NULL,
    `old_status` enum('not_started','in_progress','completed','cancelled') DEFAULT NULL,
    `new_status` enum('not_started','in_progress','completed','cancelled') NOT NULL,
    `changed_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`history_id`),
    KEY `fk_status_actor` (`changed_by`),
    KEY `idx_task_status_history` (`task_id`,`changed_at`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `tasks` (
    `task_id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `group_id` bigint unsigned NOT NULL,
    `title` varchar(100) NOT NULL,
    `description` text,
    `task_type` enum('simple','multi_stage') NOT NULL DEFAULT 'simple',
    `priority` enum('high','medium','low') NOT NULL DEFAULT 'medium',
    `estimated_hours` decimal(7,2) NOT NULL,
    `deadline` datetime(3) NOT NULL,
    `task_status` enum('not_started','in_progress','completed','cancelled') NOT NULL DEFAULT 'not_started',
    `created_by` bigint unsigned NOT NULL,
    `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `completed_at` datetime(3) DEFAULT NULL,
    `deleted_at` datetime(3) DEFAULT NULL,
    `parent_task_id` bigint unsigned DEFAULT NULL,
    `legacy_subtask_id` bigint unsigned DEFAULT NULL,
    `sort_order` int NOT NULL DEFAULT '0',
    `round_no` int unsigned NOT NULL DEFAULT '1',
    `deadline_version` int unsigned NOT NULL DEFAULT '1',
    `cancelled_at` datetime(3) DEFAULT NULL,
    `cancel_reason` varchar(500) DEFAULT NULL,
    PRIMARY KEY (`task_id`),
    UNIQUE KEY `uq_task_group` (`task_id`,`group_id`),
    UNIQUE KEY `uq_legacy_subtask` (`legacy_subtask_id`),
    KEY `fk_task_creator` (`created_by`),
    KEY `idx_tasks_group_status_deadline` (`group_id`,`task_status`,`deadline`),
    KEY `idx_tasks_group_priority_deadline` (`group_id`,`priority`,`deadline`),
    KEY `group_id` (`group_id`,`parent_task_id`,`sort_order`),
    KEY `parent_task_id` (`parent_task_id`,`group_id`),
    CONSTRAINT `chk_task_hours` CHECK ((`estimated_hours` > 0)),
    CONSTRAINT `tasks_chk_1` CHECK (((`round_no` > 0) and (`deadline_version` > 0))),
    CONSTRAINT `tasks_chk_2` CHECK ((char_length(trim(`title`)) between (case when (`parent_task_id` is null) then 3 else 1 end) and 100))
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `users` (
    `user_id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `student_id` varchar(12) NOT NULL,
    `full_name` varchar(100) NOT NULL,
    `email` varchar(254) NOT NULL,
    `birth_date` date DEFAULT NULL,
    `password_hash` varchar(255) NOT NULL,
    `account_status` enum('active','locked','disabled') NOT NULL DEFAULT 'active',
    `failed_login_count` smallint unsigned NOT NULL DEFAULT '0',
    `locked_until` datetime(3) DEFAULT NULL,
    `created_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`user_id`),
    UNIQUE KEY `student_id` (`student_id`),
    UNIQUE KEY `email` (`email`),
    CONSTRAINT `chk_student_id` CHECK (regexp_like(`student_id`,_utf8mb4'^[A-Za-z0-9]{6,12}$'))
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  CREATE TABLE `workload_settings` (
    `group_id` bigint unsigned NOT NULL,
    `available_hours_per_week` decimal(7,2) NOT NULL DEFAULT '20.00',
    `updated_by` bigint unsigned DEFAULT NULL,
    `updated_at` timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`group_id`),
    KEY `fk_capacity_actor` (`updated_by`),
    CONSTRAINT `chk_capacity` CHECK ((`available_hours_per_week` > 0))
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

  ALTER TABLE `audit_logs`
    ADD CONSTRAINT `fk_audit_group` FOREIGN KEY (`group_id`) REFERENCES `student_groups` (`group_id`),
    ADD CONSTRAINT `fk_audit_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`);

  ALTER TABLE `auth_sessions`
    ADD CONSTRAINT `fk_sessions_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`);

  ALTER TABLE `availability_slots`
    ADD CONSTRAINT `fk_availability_group` FOREIGN KEY (`group_id`) REFERENCES `student_groups` (`group_id`),
    ADD CONSTRAINT `fk_availability_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`);

  ALTER TABLE `chat_messages`
    ADD CONSTRAINT `fk_chat_group` FOREIGN KEY (`group_id`) REFERENCES `student_groups` (`group_id`),
    ADD CONSTRAINT `fk_chat_recipient` FOREIGN KEY (`recipient_user_id`) REFERENCES `users` (`user_id`),
    ADD CONSTRAINT `fk_chat_sender` FOREIGN KEY (`sender_user_id`) REFERENCES `users` (`user_id`);

  ALTER TABLE `chat_read_cursors`
    ADD CONSTRAINT `chat_read_cursors_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`),
    ADD CONSTRAINT `chat_read_cursors_ibfk_2` FOREIGN KEY (`group_id`) REFERENCES `student_groups` (`group_id`),
    ADD CONSTRAINT `chat_read_cursors_ibfk_3` FOREIGN KEY (`last_seen_message_id`) REFERENCES `chat_messages` (`message_id`);

  ALTER TABLE `comment_mentions`
    ADD CONSTRAINT `comment_mentions_ibfk_1` FOREIGN KEY (`comment_id`) REFERENCES `task_comments` (`comment_id`),
    ADD CONSTRAINT `comment_mentions_ibfk_2` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`);

  ALTER TABLE `deadline_alerts`
    ADD CONSTRAINT `fk_alert_task` FOREIGN KEY (`task_id`) REFERENCES `tasks` (`task_id`),
    ADD CONSTRAINT `fk_alert_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`);

  ALTER TABLE `evidence_files`
    ADD CONSTRAINT `evidence_files_ibfk_1` FOREIGN KEY (`submission_id`) REFERENCES `evidence_submissions` (`submission_id`);

  ALTER TABLE `evidence_submissions`
    ADD CONSTRAINT `evidence_submissions_ibfk_1` FOREIGN KEY (`assignment_id`, `task_id`, `submitted_by`) REFERENCES `task_assignments` (`assignment_id`, `task_id`, `assignee_user_id`),
    ADD CONSTRAINT `evidence_submissions_ibfk_2` FOREIGN KEY (`submitted_by`) REFERENCES `users` (`user_id`),
    ADD CONSTRAINT `evidence_submissions_ibfk_3` FOREIGN KEY (`reviewed_by`) REFERENCES `users` (`user_id`);

  ALTER TABLE `group_blocks`
    ADD CONSTRAINT `group_blocks_ibfk_1` FOREIGN KEY (`group_id`) REFERENCES `student_groups` (`group_id`),
    ADD CONSTRAINT `group_blocks_ibfk_2` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`),
    ADD CONSTRAINT `group_blocks_ibfk_3` FOREIGN KEY (`blocked_by`) REFERENCES `users` (`user_id`);

  ALTER TABLE `group_chat_receipts`
    ADD CONSTRAINT `group_chat_receipts_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`),
    ADD CONSTRAINT `group_chat_receipts_ibfk_2` FOREIGN KEY (`message_id`) REFERENCES `chat_messages` (`message_id`),
    ADD CONSTRAINT `group_chat_receipts_ibfk_3` FOREIGN KEY (`notification_id`) REFERENCES `notifications` (`notification_id`);

  ALTER TABLE `group_invitations`
    ADD CONSTRAINT `fk_invite_actor` FOREIGN KEY (`invited_by`) REFERENCES `users` (`user_id`),
    ADD CONSTRAINT `fk_invite_group` FOREIGN KEY (`group_id`) REFERENCES `student_groups` (`group_id`),
    ADD CONSTRAINT `fk_invite_user` FOREIGN KEY (`invited_user_id`) REFERENCES `users` (`user_id`);

  ALTER TABLE `group_join_requests`
    ADD CONSTRAINT `fk_join_decider` FOREIGN KEY (`decided_by`) REFERENCES `users` (`user_id`),
    ADD CONSTRAINT `fk_join_group` FOREIGN KEY (`group_id`) REFERENCES `student_groups` (`group_id`),
    ADD CONSTRAINT `fk_join_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`);

  ALTER TABLE `handover_requests`
    ADD CONSTRAINT `handover_requests_ibfk_1` FOREIGN KEY (`assignment_id`, `task_id`, `requested_by`) REFERENCES `task_assignments` (`assignment_id`, `task_id`, `assignee_user_id`),
    ADD CONSTRAINT `handover_requests_ibfk_2` FOREIGN KEY (`requested_by`) REFERENCES `users` (`user_id`),
    ADD CONSTRAINT `handover_requests_ibfk_3` FOREIGN KEY (`suggested_user_id`) REFERENCES `users` (`user_id`),
    ADD CONSTRAINT `handover_requests_ibfk_4` FOREIGN KEY (`decided_by`) REFERENCES `users` (`user_id`);

  ALTER TABLE `memberships`
    ADD CONSTRAINT `fk_membership_group` FOREIGN KEY (`group_id`) REFERENCES `student_groups` (`group_id`),
    ADD CONSTRAINT `fk_membership_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`);

  ALTER TABLE `notifications`
    ADD CONSTRAINT `fk_notification_group` FOREIGN KEY (`group_id`) REFERENCES `student_groups` (`group_id`),
    ADD CONSTRAINT `fk_notification_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`),
    ADD CONSTRAINT `notifications_ibfk_1` FOREIGN KEY (`last_message_id`) REFERENCES `chat_messages` (`message_id`);

  ALTER TABLE `otp_challenges`
    ADD CONSTRAINT `otp_challenges_ibfk_1` FOREIGN KEY (`registration_id`) REFERENCES `registration_requests` (`request_id`),
    ADD CONSTRAINT `otp_challenges_ibfk_2` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`);

  ALTER TABLE `password_reset_requests`
    ADD CONSTRAINT `fk_reset_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`);

  ALTER TABLE `personal_capacity`
    ADD CONSTRAINT `personal_capacity_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`);

  ALTER TABLE `student_groups`
    ADD CONSTRAINT `fk_groups_creator` FOREIGN KEY (`created_by`) REFERENCES `users` (`user_id`);

  ALTER TABLE `subtasks`
    ADD CONSTRAINT `fk_subtask_assignee` FOREIGN KEY (`assignee_user_id`) REFERENCES `users` (`user_id`),
    ADD CONSTRAINT `fk_subtask_creator` FOREIGN KEY (`created_by`) REFERENCES `users` (`user_id`),
    ADD CONSTRAINT `fk_subtask_task` FOREIGN KEY (`task_id`) REFERENCES `tasks` (`task_id`);

  ALTER TABLE `task_assignments`
    ADD CONSTRAINT `fk_assignment_actor` FOREIGN KEY (`assigned_by`) REFERENCES `users` (`user_id`),
    ADD CONSTRAINT `fk_assignment_task` FOREIGN KEY (`task_id`) REFERENCES `tasks` (`task_id`),
    ADD CONSTRAINT `fk_assignment_user` FOREIGN KEY (`assignee_user_id`) REFERENCES `users` (`user_id`);

  ALTER TABLE `task_comments`
    ADD CONSTRAINT `fk_comment_moderator` FOREIGN KEY (`hidden_by`) REFERENCES `users` (`user_id`),
    ADD CONSTRAINT `fk_comment_task` FOREIGN KEY (`task_id`) REFERENCES `tasks` (`task_id`),
    ADD CONSTRAINT `fk_comment_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`);

  ALTER TABLE `task_evidence`
    ADD CONSTRAINT `fk_evidence_subtask` FOREIGN KEY (`subtask_id`) REFERENCES `subtasks` (`subtask_id`),
    ADD CONSTRAINT `fk_evidence_task` FOREIGN KEY (`task_id`) REFERENCES `tasks` (`task_id`),
    ADD CONSTRAINT `fk_evidence_user` FOREIGN KEY (`uploaded_by`) REFERENCES `users` (`user_id`);

  ALTER TABLE `task_revisions`
    ADD CONSTRAINT `task_revisions_ibfk_1` FOREIGN KEY (`task_id`) REFERENCES `tasks` (`task_id`),
    ADD CONSTRAINT `task_revisions_ibfk_2` FOREIGN KEY (`parent_task_id`) REFERENCES `tasks` (`task_id`),
    ADD CONSTRAINT `task_revisions_ibfk_3` FOREIGN KEY (`assignment_id`, `task_id`) REFERENCES `task_assignments` (`assignment_id`, `task_id`),
    ADD CONSTRAINT `task_revisions_ibfk_4` FOREIGN KEY (`submission_id`, `task_id`) REFERENCES `evidence_submissions` (`submission_id`, `task_id`),
    ADD CONSTRAINT `task_revisions_ibfk_5` FOREIGN KEY (`actor_id`) REFERENCES `users` (`user_id`);

  ALTER TABLE `task_status_history`
    ADD CONSTRAINT `fk_status_actor` FOREIGN KEY (`changed_by`) REFERENCES `users` (`user_id`),
    ADD CONSTRAINT `fk_status_task` FOREIGN KEY (`task_id`) REFERENCES `tasks` (`task_id`);

  ALTER TABLE `tasks`
    ADD CONSTRAINT `fk_task_creator` FOREIGN KEY (`created_by`) REFERENCES `users` (`user_id`),
    ADD CONSTRAINT `fk_task_group` FOREIGN KEY (`group_id`) REFERENCES `student_groups` (`group_id`),
    ADD CONSTRAINT `tasks_ibfk_1` FOREIGN KEY (`legacy_subtask_id`) REFERENCES `subtasks` (`subtask_id`),
    ADD CONSTRAINT `tasks_ibfk_2` FOREIGN KEY (`parent_task_id`, `group_id`) REFERENCES `tasks` (`task_id`, `group_id`);

  ALTER TABLE `workload_settings`
    ADD CONSTRAINT `fk_capacity_actor` FOREIGN KEY (`updated_by`) REFERENCES `users` (`user_id`),
    ADD CONSTRAINT `fk_capacity_group` FOREIGN KEY (`group_id`) REFERENCES `student_groups` (`group_id`);
  INSERT INTO schema_info(version,description) VALUES (13,'KTD Task Hub');
  SELECT 'Da tao database ktd_task_hub' AS ket_qua;
END//
DELIMITER ;
CALL ktd_task_hub.khoi_tao_ktd();
DROP PROCEDURE IF EXISTS ktd_task_hub.khoi_tao_ktd;
