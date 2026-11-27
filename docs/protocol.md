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

## Lệnh TV4

Mọi lệnh dùng phiên đã đăng nhập trên socket. Ngày dùng yyyy-MM-dd, kỳ dùng `week` hoặc `month`. Mã người trong các bộ lọc không thay đổi danh tính của phiên.

| Lệnh | Trường trong data | Kết quả |
| --- | --- | --- |
| WORKLOAD_VIEW | groupId, period, date | members, periodStart, endExclusive và suggestedMoves cho trưởng nhóm |
| CAPACITY_SAVE | groupId, period, date, hours | Quỹ cá nhân đã lưu |
| WORKLOAD_SUGGEST | groupId, period, date, deadline, hours | candidates theo điểm |
| WORKLOAD_SIMULATE | Như đề xuất, thêm assigneeId | simulation có usedHours, afterHours, loadPct, afterPct |
| TASK_ASSIGN_CONFIRM | groupId, assigneeId, title, hours, deadline, confirmationId (UUID duy nhất của lần xác nhận). Khi chuyển việc thêm taskId, fromId | taskId, assigneeId |
| PROGRESS_MINE / PROGRESS_GROUP | groupId, tùy chọn memberId cho trưởng nhóm | summary, alerts, members, tasks, parentsToClose, asOf |
| REPORT_VIEW / REPORT_EXPORT | groupId, from, to, tùy chọn memberId. Khi xuất thêm format bằng pdf hoặc docx | Bảng thống kê hoặc content dạng Base64 |
| NOTIFICATION_SUBSCRIBE / NOTIFICATION_COUNT | {} | Đăng ký sự kiện trên socket hoặc lấy unreadCount |
| NOTIFICATION_LIST | kind, status (all/read/unread), beforeId đều tùy chọn | notifications, unreadCount, nextBeforeId nếu còn trang |
| NOTIFICATION_OPEN | notificationId, tùy chọn seenMessageId cho chat nhóm | notification, unreadCount và task nếu còn quyền xem |

Sau khi đăng ký, client có thể nhận `{"type":"event","action":"INBOX_CHANGED"}` giữa hai phản hồi. Client dùng một luồng đọc để tách event và đối chiếu requestId. Event yêu cầu tải lại số chưa đọc hoặc hộp thư của phiên hiện tại. Client chưa đăng ký tiếp tục dùng giao thức hỏi/đáp tuần tự. [Thiết lập và nghiệm thu TV4](tv4.md).
