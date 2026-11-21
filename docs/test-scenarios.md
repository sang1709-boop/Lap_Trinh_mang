# Mốc TV1 tuần 1
1. Chạy ServerMain theo README. Giữ server mở.
2. Chạy hai ClientMain ở hai terminal. Mỗi client phải in PONG với requestId riêng rồi thoát.
3. Chạy lại client sau khi client kia đóng. Server tiếp tục nhận kết nối.

# Mốc tiếp theo
TV1 triển khai đăng nhập MSSV và ghi nhận phiên theo kết nối trước khi mở các lệnh cần quyền. Phối hợp TV2 chuẩn bị hai tài khoản có vai trò trong cùng nhóm. Đối chiếu PH1 và PH7 trong Excel V13 cho đăng ký, OTP, đăng nhập, hồ sơ, thu hồi phiên và phạm vi xem/xuất nhật ký. TV4 review phần TV1.

# PH2 · TV2

1. Khởi tạo database bằng `ktd_task_hub.sql`, sau đó chạy `seed_demo.sql`.
2. Chạy server với `--login`, rồi mở hai client bằng cấu hình **TV2 - Quan ly nhom**. Đăng nhập `20KTD001` và `20KTD003`, mật khẩu `password`.
3. Client trưởng nhóm tạo một nhóm mới; kiểm tra tên 2 ký tự và mô tả quá 255 ký tự bị từ chối.
4. Client còn lại nhập mã nhóm. Với nhóm bật duyệt, yêu cầu phải xuất hiện ở tab Yêu cầu; duyệt hai lần chỉ tạo một membership.
5. Mời `20KTD003`, thử gửi lại khi lời mời còn hạn, chấp nhận và kiểm tra người dùng xuất hiện đúng một lần.
6. Chuyển quyền trưởng nhóm và xác nhận database vẫn có đúng một membership `leader` đang hoạt động.
7. Xóa thành viên không có việc mở, kiểm tra membership chuyển `removed` và `group_blocks` có bản ghi. Tài khoản đó không thể vào bằng mã cho đến khi gỡ chặn.
8. Với thành viên còn task mở, thao tác xóa/rời phải trả `OPEN_TASKS_EXIST`; giải tán nhóm còn task mở cũng bị từ chối.
9. Gửi yêu cầu bàn giao. Duyệt yêu cầu trả `DEPENDENCY_UNAVAILABLE` khi TV3 chưa tích hợp và yêu cầu vẫn `pending`.
10. Khởi động lại server, đăng nhập lại và xác nhận nhóm, thành viên, lời mời và yêu cầu vẫn còn trong MySQL.
