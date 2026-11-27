# TCP tuần 1
Mỗi frame bắt đầu bằng số nguyên 4 byte big-endian biểu thị số byte JSON UTF-8 theo sau. Giới hạn thử nghiệm là 1 MiB mỗi frame. Server đóng kết nối nếu khung sai độ dài, UTF-8 hoặc không giải mã được đối tượng JSON. Trên một kết nối, server đọc và trả lời tuần tự.

```json
{"type":"request","requestId":"a1","action":"PING","data":{}}
```
```json
{"type":"response","requestId":"a1","ok":true,"data":{"message":"PONG"}}
```

Server trả BAD_REQUEST khi thiếu trường bắt buộc và UNKNOWN_ACTION khi chưa đăng ký lệnh. Mã lỗi nằm trong trường error dạng chuỗi. TV2–TV4 thêm handler vào RequestRouter, giữ nguyên requestId khi trả lời. Các lệnh cần quyền phải đợi TV1 cấp danh tính từ phiên gắn với kết nối.

Mẫu thảo luận cho TV3 và TV4 (chưa có handler):
```json
{"type":"request","requestId":"a2","action":"TASK_ASSIGN","data":{"taskId":7,"assigneeId":2}}
```
```json
{"type":"event","action":"TASK_ASSIGNED","data":{"taskId":7}}
```
Khi tích hợp đăng nhập và sự kiện, client cần giữ kết nối, đọc ở luồng nền, chuyển event riêng và ghép response theo requestId. Nhóm cần thống nhất cách truyền tệp với TV3.

## Phiên đăng nhập trên một kết nối TCP
LOGIN thành công tạo một hàng auth_sessions và trả userId, fullName, sessionToken. Server lưu SHA-256 của token và gắn danh tính với socket đã đăng nhập. Kết nối lại cần LOGIN lại.
Client gửi HEARTBEAT mỗi 30 giây. HEARTBEAT và LOGOUT nhận data là {}. HEARTBEAT cập nhật last_heartbeat_at, giữ nguyên last_activity_at. LOGOUT thành công trả ok rồi đóng kết nối. Rời bất thường được ghi DISCONNECT khi server phát hiện. Phiên hết hạn dùng SESSION_TIMEOUT.

CHANGE_PASSWORD chỉ chạy trên socket đã đăng nhập. data gồm oldPassword, newPassword và confirmation. Thành công thu hồi toàn bộ phiên của tài khoản trong cùng giao dịch lưu mật khẩu. Các lỗi kiểm tra gồm OLD_PASSWORD_INCORRECT, CONFIRMATION_MISMATCH, PASSWORD_UNCHANGED và WEAK_PASSWORD.

## Các lệnh PH1 và PH7
REGISTER nhận studentId, email, fullName, birthDate (dd/MM/yyyy), password, confirmation. OTP_RESEND nhận requestId của hồ sơ và password. RESET_REQUEST nhận studentId, email và luôn trả thông điệp chung. VERIFY_OTP nhận challengeId trong email, code và purpose (register, reset_password hoặc change_email). Khi reset phải gửi thêm password, confirmation.
PROFILE_GET và PROFILE_UPDATE dùng phiên trên kết nối. PROFILE_UPDATE nhận fullName và birthDate. EMAIL_CHANGE nhận email mới và password hiện tại. AUDIT_LIST và AUDIT_EXPORT hỗ trợ groupId, from, to, actorId, action, targetId, beforeId. Phân trang tra cứu theo nextBeforeId. CSV trả về ở data.csv sau khi ghi log.
TV2–TV4 gọi AuditTrail.append ở phía server trong giao dịch cập nhật nghiệp vụ. Actor và groupId lấy từ phiên và đối tượng đã kiểm tra quyền. Chỉ truyền các trường được phép hiển thị trong Old/New, không đưa mật khẩu, OTP hoặc token vào log.
