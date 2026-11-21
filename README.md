# KTD Task Hub

Ứng dụng desktop quản lý công việc nhóm sinh viên. Kho hiện có khung Maven với hai điểm khởi chạy độc lập.

| Thư mục | Trách nhiệm |
| --- | --- |
| `shared/` | Giao thức và DTO dùng chung giữa client và server |
| `client/` | Giao diện, điều hướng, phiên đăng nhập và kết nối mạng |
| `server/` | Xử lý lệnh, kiểm tra quyền, nghiệp vụ và truy cập MySQL |
| `database/` | Schema trong `ktd_task_hub.sql` và vị trí dành cho dữ liệu mẫu |
| `docs/` | [Giao thức](docs/protocol.md) và [kịch bản kiểm thử](docs/test-scenarios.md) |
| `giao_dien_demo/` | Giao diện mẫu của dự án |

Đặc tả nghiệp vụ nằm trong [bảng phân hệ](Bao_cao/KTD_Phan_he_V13.xlsx), [sơ đồ luồng dữ liệu](DataFlow/KTD_Dataflow_V13.drawio) và [sơ đồ quy trình](WorkFlow/KTD_Workflow_V13.drawio).

## Chạy khung dự án

Dùng JDK 17 trở lên và Maven 3.6.3 trở lên. Chạy tại thư mục chứa POM gốc trên Windows.

```powershell
mvn package dependency:copy-dependencies
java -cp "server/target/classes;shared/target/classes;shared/target/dependency/*" vn.ktd.taskhub.server.ServerMain
java -cp "client/target/classes;shared/target/classes;shared/target/dependency/*" vn.ktd.taskhub.client.ClientMain
```

Khởi tạo dữ liệu demo PH2 bằng cách chạy `database/ktd_task_hub.sql`, sau đó `database/seed_demo.sql` trong MySQL Workbench. Ba tài khoản mẫu là `20KTD001`, `20KTD002`, `20KTD003`, cùng mật khẩu `password`; mã nhóm mẫu là `KTD2026DEMO1`.

Để mở giao diện Swing quản lý nhóm, chạy server với cấu hình **TV1 - Login Server**, sau đó chạy **TV2 - Quan ly nhom** trong VS Code. Client giữ nguyên kết nối sau đăng nhập và gửi các lệnh PH2 bằng `requestId`.

Trong VS Code có Extension Pack for Java, mở thư mục gốc rồi chọn **ServerMain** hoặc **ClientMain** trong **Run and Debug**. Giữ ServerMain chạy và mở hai ClientMain. Mỗi client gửi PING rồi in phản hồi PONG có requestId riêng. Bước này chưa cần MySQL. Server đọc server.properties.example hoặc nhận đường dẫn cấu hình ở đối số đầu. Client mặc định dùng client.properties và nhận hai đối số tùy chọn host port.

## Thêm nghiệp vụ

Ví dụ với chức năng giao việc, đặt giao diện trong `client/.../feature/task/{view,controller,model}`, xử lý và lưu trữ trong `server/.../feature/task/{handler,service,dao}`, và DTO trong `shared/.../dto/task`. Tạo các package này khi thêm lớp đầu tiên.
