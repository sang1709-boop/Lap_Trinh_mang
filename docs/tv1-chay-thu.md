# Chạy thử TV1 từ đăng ký đến nhật ký

Mở thư mục Java KTD-TV1 trong VS Code và cho phép Java cập nhật Maven. Dùng MySQL 8 đã nạp database/ktd_task_hub.sql. Trên CSDL hiện có, chỉ chạy migration database/migrations/014_audit_append_only.sql một lần để chặn UPDATE/DELETE trên nhật ký. Tài khoản ứng dụng không cần quyền DDL.

## Chuẩn bị thư gửi OTP
Tạo file .env tại thư mục chứa pom.xml gốc. VS Code đọc file này khi chạy **TV1 - Login Server**. Điền thông số SMTP của nhóm, dùng cổng hỗ trợ STARTTLS (thường là 587). File .env đã nằm trong .gitignore.

```dotenv
KTD_SMTP_HOST=smtp.example.edu
KTD_SMTP_PORT=587
KTD_SMTP_USER=tai-khoan-smtp
KTD_SMTP_PASSWORD=mat-khau-ung-dung
KTD_MAIL_FROM=taskhub@example.edu
```

Để thử đăng ký, người quản trị nhập một bản ghi danh mục khoa vào CSDL thử. Thay địa chỉ ví dụ bằng hộp thư bạn có quyền nhận, dùng MSSV chưa có trong users. Trên hệ thống thật chỉ nhập danh mục đã được khoa xác minh.

```sql
INSERT INTO ktd_task_hub.faculty_directory
(student_id,school_email,verified_source,imported_at)
VALUES ('TV10001','sinhvien@example.edu','fixture thu nghiem',NOW(3));
```

## Thử trong một lượt
1. Dừng server cũ, chạy **TV1 - Login Server**, nhập mật khẩu MySQL và chờ KTD TCP 5050. Mở cấu hình **TV1 - Tai khoan va nhat ky**.
2. Chọn **Đăng ký**, nhập MSSV trong danh mục khoa, email tương ứng, họ tên, ngày sinh và mật khẩu mới. Lưu mã hồ sơ (requestId) để gửi lại mã. Nếu email không khớp, kết quả chứa mã đối soát. Nếu cần gửi lại OTP, đợi ít nhất 60 giây.
3. Mở email, chọn **Xác minh đăng ký**, nhập *mã yêu cầu trong email* (challengeId) và OTP. Mã yêu cầu trong email khác mã hồ sơ đăng ký. Đăng nhập và kiểm tra thông tin tài khoản, danh sách nhóm và số chưa đọc. Thử đăng nhập từ cửa sổ thứ hai.
4. Chọn **Sửa họ tên, ngày sinh** rồi **Xem hồ sơ**. Thử **Đổi email** với mật khẩu hiện tại. Địa chỉ cũ còn hiệu lực tới khi chọn **Xác minh email mới** và nhập mã gửi đến địa chỉ mới.
5. Thử **Đổi mật khẩu** và đăng nhập lại. Sau đó đăng xuất, chọn **Quên mật khẩu**, nhập MSSV và email đã xác minh. Chọn **Đặt lại mật khẩu**, nhập mã yêu cầu, OTP và mật khẩu mới. Thử lại OTP đã dùng và kiểm tra các phiên trước đó đã đóng.
6. Chọn **Tra nhật ký**, bỏ trống mã nhóm để xem lịch sử cá nhân. Dùng nextBeforeId để xem trang tiếp. Với dữ liệu nhóm do TV2 cung cấp, thử lọc theo thời gian, người, hành động hoặc đối tượng. Thành viên không được xuất CSV. Trưởng nhóm chọn mã nhóm, bộ lọc rồi **Xuất CSV của nhóm**, chọn tên file mới. Một lần xuất tối đa 1.000 dòng, nếu vượt giới hạn hãy thu hẹp kỳ.
7. Để bài thử khóa do sai mật khẩu ở cuối. Xem **Một lượt thử TV1 với tài khoản demo** trong test-scenarios.md để kiểm tra heartbeat, LOGOUT, bộ đếm theo nguồn và thời điểm mở khóa.

## Khi chưa có SMTP
Các tài khoản active đã có vẫn dùng được để đăng nhập, thử hồ sơ, đổi mật khẩu và nhật ký. Đăng ký/đổi email cần SMTP. Quên mật khẩu luôn hiển thị cùng thông báo dù tài khoản có tồn tại hay không. Khi thư chưa tới, xem terminal server để kiểm tra lỗi SMTP. Máy chủ không in OTP ra terminal.
