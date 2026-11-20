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
