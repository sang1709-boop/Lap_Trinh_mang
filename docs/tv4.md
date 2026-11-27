# Chạy và nghiệm thu TV4

## Chuẩn bị trong MySQL Workbench

1. Dùng một CSDL thử MySQL 8 đã nạp `database/ktd_task_hub.sql`. Nếu đã có bảng V13, giữ dữ liệu hiện tại.
2. Chạy `database/migrations/015_tv4_notifications.sql` **một lần** bằng tài khoản quản trị có quyền tạo routine, function và trigger. Trigger lưu thông báo cùng giao dịch nghiệp vụ. Sau commit, server đọc hàng đợi để cập nhật chuông trên các socket đang đăng ký. Tài khoản chạy server cần quyền đọc/ghi các bảng nghiệp vụ và EXECUTE routine.
3. Dùng hai tài khoản thử đang hoạt động trong cùng nhóm. Nếu TV2 chưa tạo nhóm, đặt hai biến bên dưới trong Workbench. Sau đó chạy nội dung `database/seed_demo.sql` **trên cùng kết nối**. Script trả về mã nhóm và hai mã người dùng. Thay giá trị minh họa bằng MSSV đã đăng ký qua TV1.

```sql
SET @tv4_leader='TV40001', @tv4_member='TV40002';
```

## Chạy hai client trên cùng máy

1. Dùng JDK 17+ và chạy `mvn package` tại thư mục gốc. Trong VS Code chọn **TV1 - Login Server**. Nhập mật khẩu MySQL ở terminal. Server có xác thực lắng nghe trên loopback. Với CSDL thử ở cổng khác, dùng đối số `--login <server.properties>`. Có thể cấp mật khẩu qua biến môi trường `KTD_DB_PASSWORD` cho tiến trình server.
2. Chạy **TV4 - Cong viec nhom** hai lần. Đăng nhập trưởng nhóm trên A, thành viên trên B. Chọn **Công việc nhóm**. Số trong ngoặc là tổng chưa đọc của tài khoản trên mọi nhóm.
3. B mở **Hộp thông báo**. Trên A, chọn nhóm và kỳ có hạn dự kiến. Điền tên việc, số giờ, hạn theo yyyy-MM-dd. Bấm **Gợi ý người nhận**, chọn dòng của B rồi **Chọn người nhận**. A thấy số giờ và phần trăm tải trước/sau. Lúc này B vẫn giữ nguyên số chưa đọc. **Bỏ bản nháp** hủy toàn bộ lựa chọn.
4. Bấm **Xác nhận giao việc** trên A. B nhận một mục **Được giao công việc**. A không nhận mục này. B mở mục để xem tên việc, người phụ trách, hạn và trạng thái. Mở lại cùng mục, sau đó đăng xuất và đăng nhập lại. Mục vẫn nằm trong bộ lọc **Đã đọc**, số chưa đọc chỉ giảm một lần. Thử đóng B trước khi giao một việc mới rồi đăng nhập lại để xem trường hợp ngoại tuyến.
5. Trong **Tải và phân công**, B chỉ thấy tải của mình và có thể lưu quỹ giờ cho tuần/tháng đang chọn. A thấy số tổng hợp của các thành viên. Tải lấy giờ của việc lá mở trên tất cả các nhóm có hạn trước cuối kỳ, kể cả tồn quá hạn. Quỹ mặc định là 20 giờ/tuần, quỹ tháng quy đổi theo số ngày. Thẻ **Cân bằng** có đề xuất khi tải nguồn đồng thời trên 100% và trên 125% trung bình, tải người nhận dưới 70% và dưới 50% trung bình. Chọn một việc chưa bắt đầu để mô phỏng và xác nhận chuyển.
6. Trong **Tiến độ và báo cáo**, B xem việc lá của mình. A bấm **Tiến độ nhóm**, nhấp đúp tên thành viên để xem chi tiết. Hai tỷ lệ lần lượt dùng số việc và giờ ước tính. Hủy, ẩn, hậu duệ của việc hủy/ẩn, cha chờ đóng và cha đang chờ chia được loại khỏi mẫu số. Kiểm tra riêng ba màu, chờ chấm/đạt và danh sách cha chờ đóng. A chọn khoảng ngày, có thể lọc mã thành viên rồi xem biểu đồ hoặc xuất PDF/Word.
7. Server quét hạn ngay khi khởi động và mỗi 30 phút. Trên dữ liệu thử, đặt hạn trong 24–72 giờ, dưới 24 giờ hoặc đã qua hạn. Quét lại cùng mốc chỉ giữ một bản ghi. Gia hạn hoặc mở vòng mới cho phép nhắc lại. Việc chờ chấm/đạt không gửi nhắc nộp. Trên Windows PDF dùng Arial, trên Linux dùng DejaVu Sans. Có thể cấu hình đường dẫn font TTF qua `-Dktd.report.font=...`.

## Kiểm thử TCP tự động

`server/src/test/python/tv4_two_clients.py` dùng thư viện chuẩn Python 3, mở hai socket và đăng nhập thật. Chạy trên nhóm thử vì bài kiểm tra tạo hai việc và nhật ký. Đặt `TV4_GROUP`, `TV4_LEADER_ID`, `TV4_MEMBER_ID`, `TV4_LEADER_PASSWORD`, `TV4_MEMBER_PASSWORD` trong môi trường của tiến trình. Có thể đổi `TV4_HOST`, `TV4_PORT` (mặc định 127.0.0.1, 5050).

```powershell
python server/src/test/python/tv4_two_clients.py
```

Bài thử kiểm tra quyền, mô phỏng không ghi, xác nhận gửi lại, nhận sự kiện ở B, từ chối A mở thông báo của B, mở lại hai lần, đăng nhập lại, tiến độ và cả hai định dạng báo cáo.

## Phối hợp TV2 và TV3

- Bản nháp mặc định tạo việc đơn có hạn cuối ngày lúc 23:59:59 (UTC+7). Xếp hạng ưu tiên số giờ còn lại sau phân công, trừ 1 điểm mỗi việc có hạn cách ngày dự kiến tối đa một ngày và 0,25 điểm mỗi việc mở. Khi bằng điểm, xếp mã người tăng dần. Đề xuất chuyển việc dùng cùng bước xác nhận với việc đơn mới.
- Khi TV3 có biểu mẫu tạo việc đầy đủ, dùng kết quả mô phỏng để điền người nhận. Lệnh xác nhận hiện tại kiểm lại vai trò, thành viên, trạng thái, người cũ, hạn và số giờ. Gửi lại cùng `confirmationId` sẽ trả về phân công đã lưu.
- Khi cập nhật việc, TV3 ghi `task_revisions` (người nhận, hạn, vòng, trạng thái, minh chứng, thời điểm đóng và cờ muộn) trong cùng giao dịch. Báo cáo kỳ đã kết thúc dùng bản cuối trước 00:00 ngày kế tiếp UTC+7. Nếu thiếu lịch sử đầu kỳ cần chốt, server trả `HISTORY_INCOMPLETE`. Báo cáo đếm việc còn mở tại mốc chốt và việc đã hoàn thành trong khoảng chọn. Mốc nộp dùng bản mới nhất của đúng lượt phân công/vòng.
- Trigger 015 nhận việc giao/đổi, sửa việc, thay đổi tư cách, mời/gia nhập/bàn giao, giải tán, minh chứng, hoàn thành, bình luận, nhắc tên, chat nhóm và chat riêng từ các bảng V13. TV2 chuyển trạng thái nhóm sang giải tán **trước** khi kết thúc các dòng thành viên trong cùng giao dịch để chốt đúng danh sách nhận. TV3 kiểm quyền xem của người được nhắc trước khi ghi `comment_mentions`. Chỉ một nơi phát sinh thông báo cho mỗi sự kiện.
- Mở thông báo chat gửi `seenMessageId` mà client thực sự thấy. Nếu có tin mới đến trước lúc mở, server giữ phần mới trong một mục chưa đọc riêng. Với mục giao việc, server chỉ trả chi tiết việc khi người mở còn quyền trong nhóm.
