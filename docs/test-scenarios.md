# Mốc TV1 tuần 1
1. Chạy ServerMain theo README. Giữ server mở.
2. Chạy hai ClientMain ở hai terminal. Mỗi client phải in PONG với requestId riêng rồi thoát.
3. Chạy lại client sau khi client kia đóng. Server tiếp tục nhận kết nối.

# Mốc tiếp theo
TV1 triển khai đăng nhập MSSV và ghi nhận phiên theo kết nối trước khi mở các lệnh cần quyền. Phối hợp TV2 chuẩn bị hai tài khoản có vai trò trong cùng nhóm. Đối chiếu PH1 và PH7 trong Excel V13 cho đăng ký, OTP, đăng nhập, hồ sơ, thu hồi phiên và phạm vi xem/xuất nhật ký. TV4 review phần TV1.

## Một lượt thử TV1 với tài khoản demo
1. Khởi động lại TV1 - Login Server. Thử sai mật khẩu một lần rồi đăng nhập đúng bằng TV1 - Login Client. Kiểm tra LOGIN_FAIL và LOGIN trong audit_logs.
2. Mở client thứ hai, đăng nhập cùng MSSV. Sau 30 giây kiểm tra hai dòng auth_sessions có socket_id riêng và heartbeat mới. Nhấn Enter ở client thứ hai rồi đăng nhập lại. Chỉ phiên vừa thoát có lý do LOGOUT.
3. Tại client đầu, chọn 1. Thử mật khẩu cũ sai, xác nhận không khớp và mật khẩu yếu. Mỗi lần chọn lại 1. Kiểm tra PASSWORD_CHANGE_FAIL và mật khẩu cũ vẫn dùng được.
4. Đổi sang mật khẩu demo mới 8–32 ký tự có hoa, thường, số, ký tự đặc biệt, không có khoảng trắng hoặc MSSV. Kiểm tra PASSWORD_CHANGE. Mọi phiên trước đó của tài khoản phải có closed_at. Client còn lại phát hiện thu hồi chậm nhất ở heartbeat tiếp theo. Đăng nhập lại bằng mật khẩu mới.
5. Thử khóa nguồn sau cùng: đăng xuất rồi nhập sai năm lần liên tiếp từ cùng IP. Đăng nhập đúng ngay sau đó vẫn bị từ chối. Sau 15 phút, thử lại bằng mật khẩu mới.
