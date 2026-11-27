USE ktd_task_hub;
-- Chạy một lần sau khi khởi tạo schema V13, bằng tài khoản có quyền TRIGGER.
CREATE TRIGGER ktd_audit_no_update BEFORE UPDATE ON audit_logs
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Audit log is append-only';
CREATE TRIGGER ktd_audit_no_delete BEFORE DELETE ON audit_logs
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Audit log is append-only';
