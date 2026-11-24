# Hướng dẫn cài đặt, vận hành và kiểm thử

Tài liệu dành cho người phát triển và người quản trị máy chủ. Gồm: dựng môi trường và chạy chương trình từ mã nguồn, đóng gói bộ cài, cài đặt và nâng cấp trên máy chủ Windows, sao lưu, khôi phục, đặt lại mật khẩu Super Admin, các bộ kiểm thử và các lỗi thường gặp.

Tài liệu này thay thế các file hướng dẫn chạy thử, kiểm tra giao diện, kiểm chứng M0 và sao lưu khôi phục trước đây. Hướng dẫn thao tác trên giao diện nằm ở [USER-GUIDE.md](USER-GUIDE.md). Thiết kế nằm ở [ARCHITECTURE.md](ARCHITECTURE.md), yêu cầu ở [PRD.md](PRD.md), kế hoạch ở [PLAN.md](PLAN.md).

## Mục lục

1. [Tổng quan](#1-tổng-quan)
2. [Chuẩn bị máy phát triển](#2-chuẩn-bị-máy-phát-triển)
3. [Chạy chương trình từ mã nguồn](#3-chạy-chương-trình-từ-mã-nguồn)
4. [Thử bằng trình duyệt và API](#4-thử-bằng-trình-duyệt-và-api)
5. [Đóng gói bộ cài từ mã nguồn](#5-đóng-gói-bộ-cài-từ-mã-nguồn)
6. [Cài đặt trên máy chủ Windows](#6-cài-đặt-trên-máy-chủ-windows)
7. [Nâng cấp và gỡ cài đặt](#7-nâng-cấp-và-gỡ-cài-đặt)
8. [Sao lưu](#8-sao-lưu)
9. [Khôi phục](#9-khôi-phục)
10. [Đặt lại mật khẩu Super Admin](#10-đặt-lại-mật-khẩu-super-admin)
11. [Kiểm thử](#11-kiểm-thử)
12. [Lỗi thường gặp](#12-lỗi-thường-gặp)
13. [Phụ lục](#13-phụ-lục)

---

## 1. Tổng quan

| Thành phần | Công nghệ |
|---|---|
| Backend | Spring Boot 3.5.16, Java 25, SQLite (một file), Flyway, `JdbcTemplate` |
| Frontend | HTML, CSS, JS thuần (ES modules). Không npm, không CDN, không font ngoài |
| Đóng gói | JAR + JRE gọn (`jlink`) + WinSW, chạy như Windows Service |
| Mạng | HTTP trong LAN, không cần Internet khi chạy |

Ba cách chạy chương trình:

| Cách | Dùng khi | Quyền Administrator |
|---|---|---|
| Từ mã nguồn (`mvnw spring-boot:run`) | Phát triển, thử nhanh | Không |
| Chạy file JAR (`java -jar`) | Thử bản đã build | Không |
| Dịch vụ Windows (bộ cài) | Triển khai thật | Cần khi cài, gỡ |

Vị trí mặc định khi chạy dịch vụ:

| Thứ | Đường dẫn |
|---|---|
| Chương trình (JAR, JRE, WinSW) | `C:\Program Files\TrainingStats\` |
| File dữ liệu | `C:\ProgramData\TrainingStats\data\trainingstats.db` (kèm `-wal`, `-shm` khi đang chạy) |
| Cấu hình | `C:\ProgramData\TrainingStats\config\application.properties` |
| Log | `C:\ProgramData\TrainingStats\logs\` |
| Sao lưu tự động, thủ công | `C:\ProgramData\TrainingStats\backups\` |
| Sao lưu trước nâng cấp | `C:\ProgramData\TrainingStats\backups\upgrade\` |

Dữ liệu, cấu hình, log và sao lưu **không bị xóa** khi nâng cấp hoặc gỡ cài đặt.

## 2. Chuẩn bị máy phát triển

| Cần | Ghi chú |
|---|---|
| Windows 10 hoặc 11, PowerShell 5.1 trở lên | Mọi lệnh trong tài liệu chạy bằng PowerShell |
| JDK 25 (Temurin hoặc tương đương) | Đặt `JAVA_HOME` trỏ tới thư mục JDK (thư mục có `bin\java.exe` và `bin\jlink.exe`) |
| Maven | **Không cần cài.** Dùng `backend\mvnw.cmd` (Maven 3.9.16 qua Maven Wrapper) |
| Internet lần đầu | Tải thư viện Maven và WinSW; sau đó build lại được khi không có mạng nếu đã có bộ nhớ đệm Maven |
| Edge hoặc Chrome | Chỉ khi chạy `tools\ui-check.ps1` |

Kiểm tra nhanh:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.5.10002-hotspot'   # đổi theo máy
& "$env:JAVA_HOME\bin\java.exe" -version            # phải là 25.x
cd D:\Training-Statistics\backend
.\mvnw.cmd -v                                        # in phiên bản Maven và Java
```

Cấu trúc thư mục chính:

| Thư mục | Nội dung |
|---|---|
| `backend/` | Mã Java, `pom.xml`, `mvnw.cmd`. Dữ liệu khi phát triển nằm ở `backend/data/` |
| `frontend/` | HTML, CSS, JS thuần. Maven chép vào `static/` trong JAR khi build, **không sửa ở `static/`** |
| `installer/windows/` | `build.ps1`, `test-upgrade.ps1`, `setup/` (cài đặt, gỡ, kiểm tra, đặt lại mật khẩu), `service/` (cấu hình WinSW) |
| `tools/` | `ui-check.ps1` kiểm tra giao diện tự động |
| `docs/` | Tài liệu |

## 3. Chạy chương trình từ mã nguồn

Mở PowerShell, lần lượt chạy.

### 3.1 Tạo Super Admin (một lần, khi DB còn trống)

DB mới chưa có tài khoản nào. Chọn một trong hai cách.

**Cách 1: công cụ đặt lại** (tạo Super Admin `admin` nếu chưa có):

```powershell
cd D:\Training-Statistics\backend
$env:TS_RESET_PASSWORD = 'MatKhau@123'
.\mvnw.cmd -q spring-boot:run "-Dspring-boot.run.arguments=--reset-superadmin"
Remove-Item Env:TS_RESET_PASSWORD
```

Dòng cảnh báo "Chua co Super Admin..." là bình thường; dòng cuối phải là `Da tao Super Admin 'admin'.`

**Cách 2: giống bộ cài.** Ứng dụng đọc `config/first-run.properties` ở lần chạy kế tiếp, tạo tài khoản rồi **xóa file**:

```powershell
New-Item -ItemType Directory -Force config | Out-Null
Set-Content config\first-run.properties 'app.first-run.admin-password=MatKhau@123' -Encoding ASCII
```

Thư mục `config/` (cạnh `data/`) và file này đã nằm trong `.gitignore`. Tên tài khoản mặc định là `admin`, đổi bằng `app.first-run.admin-username` trong cùng file.

### 3.2 Chạy máy chủ

```powershell
cd D:\Training-Statistics\backend
.\mvnw.cmd spring-boot:run        # cổng 8080, dữ liệu ở backend\data\. Ctrl+C để dừng
```

Mở `http://localhost:8080/`; trình duyệt tự chuyển tới trang đăng nhập.

Làm lại từ đầu: dừng máy chủ, xóa thư mục `backend\data\` (và `backend\config\` nếu có), rồi làm lại 3.1.

### 3.3 Chạy bản đã build, đổi cổng và thư mục dữ liệu

```powershell
.\mvnw.cmd package -DskipTests
& "$env:JAVA_HOME\bin\java.exe" --enable-native-access=ALL-UNNAMED -jar target\trainingstats.jar
```

Cấu hình qua biến môi trường hoặc tham số:

| Mục | Biến môi trường | Tham số dòng lệnh | Mặc định |
|---|---|---|---|
| Cổng | `APP_PORT` | `--server.port=` | 8080 |
| Thư mục dữ liệu | `APP_DATA_DIR` | `--app.data-dir=` | `./data` |
| Thư mục cấu hình | `APP_CONFIG_DIR` | `--app.config-dir=` | `<data>/../config` |

Chạy **máy chủ thử riêng** trên cổng khác với dữ liệu tạm (không đụng DB đang dùng; cách này cũng dùng cho `ui-check`, mục 11.3):

```powershell
$t = Join-Path $env:TEMP 'ts-thu'
New-Item -ItemType Directory -Force "$t\config" | Out-Null
Set-Content "$t\config\first-run.properties" 'app.first-run.admin-password=MatKhau@123' -Encoding ASCII
cd D:\Training-Statistics\backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=18095 --app.data-dir=$t\data --app.config-dir=$t\config"
```

Cả `-Dspring-boot.run.arguments=...` phải nằm **trong một cặp nháy kép**, nếu không Maven báo `Unrecognized option --app.data-dir` (xem mục 12).

## 4. Thử bằng trình duyệt và API

### 4.1 Thử bằng trình duyệt

Mở `http://localhost:8080/` (hoặc `http://<ip-máy-chủ>:<cổng>/` từ máy khác, điện thoại cùng Wi-Fi). Bảng dưới là bộ thử nhanh theo chức năng; kết quả đúng là cột bên phải.

| Thử | Kết quả mong đợi |
|---|---|
| Đăng nhập sai mật khẩu | "Tài khoản hoặc mật khẩu không đúng." ngay trên form |
| Đăng nhập đúng bằng `admin` | Vào trang chủ có thẻ số liệu và biểu đồ |
| Thu nhỏ cửa sổ dưới 600 px, hoặc mở trên điện thoại | Thanh tab ở đáy, bảng thành danh sách thẻ, menu "Thêm" chứa Đổi mật khẩu và Đăng xuất |
| Đổi mật khẩu: để trống rồi bấm; nhập đúng | Mỗi ô báo lỗi riêng; đúng thì "Đã đổi mật khẩu." |
| Menu Nhật ký (Super Admin) | Danh sách thao tác, lọc theo người, hành động, ngày; "Xem" mở chi tiết |
| Người dùng → "Thêm người dùng" | Form hai cột (một màn từ 1024 px); chọn đơn vị trong phạm vi, chuyên ngành dạng ô đánh dấu, đối tượng; để trống mã thì tự cấp `ND000001` |
| Ô tìm kiếm, gõ không dấu | "nguyen van" tìm ra "Nguyễn Văn ..." |
| Trang chi tiết người dùng | Nút Sửa, In, "Thao tác khác" (chuyển đơn vị, đổi quyền, cấp tài khoản, đặt lại mật khẩu, khóa, xóa) |
| Đơn vị, Danh mục (Super Admin) | Cây 2 cấp thêm đơn vị con (mã tự đánh số), đổi tên, khóa; danh mục thêm, sửa, khóa |
| Đợt kiểm tra (Super Admin) | Thêm, đóng, mở lại; chỉ xóa được đợt chưa có nội dung |
| Nội dung kiểm tra → "Thêm" (Super Admin) | Hộp thoại ngay trên trang; cần ít nhất một đợt đang mở; ô Giám khảo gõ tên, mã hoặc tài khoản rồi chọn trong gợi ý |
| Chi tiết nội dung: Thêm người, Thêm đơn vị | Tìm không dấu toàn Lữ đoàn; dòng mới "Chưa kiểm tra" |
| Chi tiết nội dung: Lý do vắng, Gỡ | "Không kiểm tra"; gỡ xong người đó sang bộ lọc "Đã gỡ" (chỉ Super Admin) |
| Đăng nhập Admin, mở một nội dung đang mở | Chỉ thấy người trong nhánh mình; có nút đề nghị thêm, gỡ; không có nút thêm trực tiếp |
| Admin gửi đề nghị | Giám khảo và Super Admin thấy số trên chuông; bấm thông báo sang trang Đề nghị, Duyệt hoặc Từ chối (lý do bắt buộc) |
| Menu Đề nghị | Đề nghị đang chờ gom theo nội dung; "Chọn tất cả" của một nội dung rồi Duyệt hoặc Từ chối ở thanh dưới |
| Menu Phân công | Chọn đợt; bảng người × nội dung; Super Admin và giám khảo "Áp dụng" (người vào ngay), Admin "Gửi đề nghị" |
| Chấm điểm (Super Admin, giám khảo) | Gõ điểm (nhận dấu phẩy) thì hiện xếp loại, Enter sang người kế; "Lưu điểm" mở bảng xác nhận; `6.55` báo lỗi, không lưu |
| Giám khảo là người học của chính nội dung đó | Báo không tự chấm được |
| Sửa điểm (Super Admin) | Bắt buộc lý do; điểm mới giống cũ thì báo lỗi; dòng hiện "Đã sửa", xem được lịch sử |
| Menu Hồ sơ | Thông tin của mình và các nội dung đã tham gia kèm điểm; không có nội dung đã bị gỡ, không có điểm đơn vị |
| Nhập Excel (người dùng, nội dung, điểm, phân công, chấm cả đợt) | Tải file mẫu, điền, "Kiểm tra file": lỗi hiện theo dòng, cột, có nút tải CSV lỗi, không có nút ghi; không lỗi thì xem trước rồi "Ghi dữ liệu" |
| Nhập lại cùng file sau khi sửa vài ô | Dòng có mã đã có thành "Cập nhật", hiện họ tên người được khớp |
| Nhập điểm Excel: đổi điểm của người đã có điểm | Báo lỗi, không ghi gì |
| Thống kê (Admin, Super Admin) | Lọc đợt, nội dung, đơn vị (chọn nhiều), ngày; biểu đồ kèm bảng số liệu và dòng tổng |
| In kết quả, in thông tin cá nhân | Khổ A4, đen trắng, có dòng "Bộ lọc" khi đang lọc; nút "In tất cả" |
| Hệ thống (Super Admin) | Tình trạng, cảnh báo, danh sách sao lưu, "Tạo bản sao lưu ngay", cài đặt sao lưu |
| Đăng xuất | Về trang đăng nhập; gọi API sau đó trả 401 |

Thử phân quyền theo nhánh:

1. Đăng nhập Super Admin, tạo một Admin ở "Phòng Tham mưu" (0002) và một Admin ở "Ban Quân huấn" (0002.0001), mỗi người có tài khoản và mật khẩu.
2. Đăng nhập Admin 0002.0001: danh sách chỉ có người của Ban Quân huấn.
3. Dùng Admin 0002.0001 chuyển một Admin khác của Ban Quân huấn sang "Phòng Chính trị": hộp thoại cảnh báo, sau khi chuyển người đó bị hạ xuống Người dùng và biến khỏi danh sách.
4. Khóa một người đang đăng nhập ở trình duyệt khác: lần bấm tiếp theo của họ bị đưa về trang đăng nhập.

### 4.2 Thử API bằng PowerShell

Mở cửa sổ PowerShell khác (dùng được cho cả chạy từ mã nguồn lẫn dịch vụ). Danh sách API đầy đủ ở [API.md](API.md).

```powershell
$base = 'http://localhost:8080'
$s = New-Object Microsoft.PowerShell.Commands.WebRequestSession

# Đăng nhập (cần token CSRF trước)
$csrf = Invoke-RestMethod "$base/api/v1/auth/csrf" -WebSession $s
Invoke-RestMethod "$base/api/v1/auth/login" -Method Post -WebSession $s -ContentType 'application/json' `
  -Headers @{ 'X-XSRF-TOKEN' = $csrf.token } -Body '{"username":"admin","password":"MatKhau@123"}'

# Thông tin của mình, nhật ký thao tác (chỉ Super Admin)
Invoke-RestMethod "$base/api/v1/me" -WebSession $s
(Invoke-RestMethod "$base/api/v1/audit-logs?size=10" -WebSession $s).items | Format-Table at, action, actorUsername, ip

# Request ghi: token CSRF đổi sau đăng nhập, nên lấy lại trước mỗi lần ghi
$csrf = Invoke-RestMethod "$base/api/v1/auth/csrf" -WebSession $s
Invoke-RestMethod "$base/api/v1/me/password" -Method Put -WebSession $s -ContentType 'application/json' `
  -Headers @{ 'X-XSRF-TOKEN' = $csrf.token } -Body '{"currentPassword":"MatKhau@123","newPassword":"MatKhau@456"}'
```

Hàm gửi request ghi gọn, dùng cho các thử nghiệm sau (giữ chữ có dấu bằng cách gửi byte UTF-8):

```powershell
function Send($method, $path, $body) {
  $p = @{ Uri = "$base/api/v1$path"; Method = $method; WebSession = $s
          Headers = @{ 'X-XSRF-TOKEN' = (Invoke-RestMethod "$base/api/v1/auth/csrf" -WebSession $s).token } }
  if ($body) {
    $p.ContentType = 'application/json; charset=utf-8'
    $p.Body = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json))
  }
  Invoke-RestMethod @p
}
$dot = Send Post '/periods' @{ name = 'Kiểm tra quý IV'; startDate = '2026-12-01'; endDate = '2026-12-20' }
$lop = Send Post '/classes' @{ periodId = $dot.id; name = 'Bắn súng K54'; examDate = '2026-12-05' }
Send Put "/classes/$($lop.id)/teachers" @{ userIds = @(<id giám khảo>) }      # người có tài khoản
Send Post "/classes/$($lop.id)/participants" @{ unitId = <id đơn vị> }        # thêm cả đơn vị
```

Muốn xem mã lỗi khi gọi sai, bọc lệnh trong `try { ... } catch { $_.Exception.Response.StatusCode.value__ }`.

Kết quả mong đợi:

| Thao tác | Kết quả |
|---|---|
| Đăng nhập đúng | `username: admin`, `role: SUPER_ADMIN` |
| Đăng nhập sai mật khẩu hoặc tài khoản không tồn tại | 401 `INVALID_CREDENTIALS`, cùng một câu thông báo |
| Request ghi thiếu header CSRF | 403 `CSRF_INVALID` |
| `/me` khi chưa đăng nhập hoặc sau khi đăng xuất | 401 `UNAUTHENTICATED` |
| Đổi mật khẩu sai mật khẩu hiện tại, hoặc mật khẩu mới dưới 8 ký tự | 400, `fieldErrors` chỉ rõ trường |
| Nhật ký | Có `LOGIN_SUCCESS`, `LOGIN_FAILED`, `PASSWORD_CHANGED`, `LOGOUT` kèm IP |
| Admin tạo đề nghị cho người ngoài nhánh | 404 |
| Đóng nội dung (`PATCH /classes/{id}/status` `CLOSED`) rồi thêm người | 409 `CLASS_CLOSED` |
| Giám khảo là user thường gọi `GET /classes` | Chỉ nội dung mình dạy và mình học |

## 5. Đóng gói bộ cài từ mã nguồn

### 5.1 Chỉ tạo file JAR

```powershell
cd D:\Training-Statistics\backend
.\mvnw.cmd clean package -DskipTests
```

Kết quả: `backend\target\trainingstats.jar` (khoảng 60 MB). **Luôn có `clean`:** file migration đã xóa mà còn trong `target\classes` sẽ vào JAR và Flyway báo `Found more than one migration with version 1`. Maven tự chép `frontend\` vào JAR; không cần bước build frontend.

### 5.2 Tạo bộ cài Windows

```powershell
cd D:\Training-Statistics
installer\windows\build.ps1               # build, chạy toàn bộ test, tạo JRE gọn, đóng gói
installer\windows\build.ps1 -SkipTests    # bỏ qua test (khi chỉ cần đóng gói)
installer\windows\build.ps1 -JavaHome 'C:\...\jdk-25...'   # chỉ định JDK nếu JAVA_HOME chưa đặt
```

Các bước script thực hiện: kiểm JDK (cần `jlink.exe`) → build bằng Maven (có `clean`) → tạo JRE gọn bằng `jlink` với các module cần thiết → chép JAR → tải WinSW và **kiểm SHA256** → chép script cài đặt → nén zip.

| Kết quả | Vị trí |
|---|---|
| Thư mục bộ cài | `dist\TrainingStats\` (gồm `app\`, `runtime\`, `service\`, `install.ps1`, `uninstall.ps1`, `check.ps1`, `reset-superadmin.ps1`, `install.cmd`, `uninstall.cmd`, `VERSION.txt`) |
| File phân phối | `dist\TrainingStats-<phiên bản>.zip` (khoảng 105 MB) |

Ghi chú kỹ thuật của gói:

- JRE gọn khoảng 62 MB (21 module), JAR khoảng 59 MB. Nếu chạy báo `NoClassDefFoundError` thì thêm module thiếu vào biến `$JdkModules` trong `build.ps1`.
- **WinSW 2.12.0**, tải từ GitHub; không có chữ ký số và GitHub không công bố checksum. SHA256 ghim trong `build.ps1` là giá trị tính từ file tải lần đầu (đối chiếu kích thước 18.243.033 byte). Đổi phiên bản WinSW thì phải tính lại SHA256 và sửa ở `build.ps1`. Máy build cần Internet ở lần đầu.
- Máy đích **không cần cài Java**: dùng JRE trong gói.
- Cờ `--enable-native-access=ALL-UNNAMED` đã đặt sẵn trong cấu hình dịch vụ (JDK 25 cảnh báo khi `sqlite-jdbc` dùng mã native).

## 6. Cài đặt trên máy chủ Windows

### 6.1 Yêu cầu

Windows 10, 11 hoặc Windows Server, quyền Administrator, một cổng trống (mặc định 8080). Không cần Java, không cần Internet.

### 6.2 Các bước

1. Chép `TrainingStats-<phiên bản>.zip` sang máy chủ và giải nén.
2. Mở PowerShell **bằng Run as administrator**, vào thư mục vừa giải nén. (Hoặc bấm đúp `install.cmd`, nó tự xin quyền Administrator.)
3. Chạy:

   ```powershell
   .\install.ps1
   ```

4. Cài mới: script hỏi **cổng** (Enter để lấy 8080) và **mật khẩu Super Admin** (tối thiểu 8 ký tự, nhập hai lần). Tài khoản là `admin`.
5. Chạy kiểm tra:

   ```powershell
   & 'C:\Program Files\TrainingStats\check.ps1'
   ```

   Kết quả phải là `KET QUA: tat ca muc kiem tra deu dat`.

   Ở bước "Khoi dong dich vu", script in dấu chấm trong lúc chờ ứng dụng trả lời (tối đa 3 phút; lần đầu hoặc khi nâng cấp dữ liệu có thể mất vài chục giây). Ứng dụng lỗi khi khởi động thì script dừng ngay và in các dòng cuối của log (xem mục 12.2).
6. Từ máy khác hoặc điện thoại cùng mạng, mở địa chỉ `http://<ip-máy-chủ>:<cổng>/` mà `check.ps1` in ra. Phải thử từ thiết bị khác: máy chủ tự gọi IP của chính nó luôn được, không chứng minh gì về tường lửa.

Tham số của `install.ps1`:

| Tham số | Ý nghĩa |
|---|---|
| `-Port <số>` | Cổng lắng nghe. Bỏ trống: hỏi (cài mới) hoặc giữ nguyên (nâng cấp) |
| `-AdminPassword <SecureString>` | Mật khẩu Super Admin ban đầu, chỉ dùng khi cài mới. Bỏ trống: hỏi |
| `-InstallDir`, `-DataRoot` | Thư mục chương trình (mặc định `C:\Program Files\TrainingStats`), thư mục dữ liệu (`C:\ProgramData\TrainingStats`) |
| `-AllowPublicNetwork` | Mở luật tường lửa cho cả mạng Public (mặc định chỉ Domain và Private) |
| `-ServiceAccount LocalService \| LocalSystem` | Tài khoản chạy dịch vụ. Mặc định `LocalService` (ít quyền); `LocalSystem` chỉ khi cần quay lại cách cũ |
| `-SkipService` | Chỉ chép file và tạo cấu hình, không cài dịch vụ, không mở tường lửa. Dùng để kiểm thử, không cần Administrator |

Script làm: chép chương trình vào `-InstallDir`; tạo thư mục dữ liệu và cấp quyền cho SYSTEM, Administrators, LocalService; ghi `config\application.properties` (`server.port`, `app.data-dir`, `logging.file.name`); ghi `config\first-run.properties` chứa mật khẩu Super Admin (quyền chỉ SYSTEM và Administrators); cài dịch vụ `TrainingStats` (tự khởi động cùng máy, tự khởi động lại khi tiến trình sập: sau 10 giây, rồi 30 giây); mở luật tường lửa `TrainingStats HTTP`. Ở lần chạy đầu, ứng dụng đọc `first-run.properties`, tạo Super Admin, băm BCrypt rồi **xóa file ngay**.

### 6.3 Mạng và tường lửa

Luật tường lửa mặc định chỉ mở cho mạng **Domain** và **Private**. Nếu Windows phân loại mạng Wi-Fi hoặc LAN của đơn vị là **Public**, máy khác **không vào được**, và `check.ps1` báo điều này. Chọn một cách:

1. **Đổi loại mạng sang Private** (khuyến nghị cho máy chủ đặt cố định): Settings → Network & internet → Wi-Fi (hoặc Ethernet) → chọn mạng → Network profile type → Private network.
2. Chạy lại `install.ps1 -AllowPublicNetwork` bằng quyền Administrator (nâng cấp tại chỗ, giữ dữ liệu, tạo lại luật cho mọi profile). Chỉ nên dùng khi máy chủ không rời mạng tin cậy, vì cổng sẽ mở cả khi cắm vào Wi-Fi công cộng.

Hệ thống chạy HTTP (không HTTPS) và không khóa tài khoản khi nhập sai: nên đặt máy chủ ở mạng nội bộ riêng, chọn mật khẩu Super Admin mạnh lúc cài (xem ARCHITECTURE R4).

### 6.4 Đổi cổng hoặc thư mục sau khi cài

Sửa `C:\ProgramData\TrainingStats\config\application.properties` (các dòng `server.port`, `app.data-dir`, `logging.file.name`), rồi khởi động lại dịch vụ:

```powershell
Restart-Service TrainingStats
```

Đổi cổng thì cũng chạy lại `install.ps1 -Port <số mới>` để luật tường lửa theo cổng mới.

### 6.5 Nghiệm thu trên máy Windows sạch

Máy sạch là máy chưa cài Java (`check.ps1` in "May nay KHONG co Java" để xác nhận). Làm lần lượt trên máy đó:

1. Cài như mục 6.2, chạy `check.ps1`; mục nào `[LOI]` thì làm theo gợi ý ngay dòng đó (script không sửa gì trên máy).
2. Mở trang từ điện thoại hoặc máy khác cùng mạng.
3. **Khởi động lại máy**, đăng nhập Windows, không mở gì thêm, chạy lại `check.ps1`: mục "Tien trinh bat dau sau khi khoi dong may ... dich vu da tu chay" phải đạt.
4. Gỡ rồi cài lại để kiểm dữ liệu còn nguyên (mục 7.2).

`check.ps1` kiểm: dịch vụ chạy và kiểu khởi động Automatic, tiến trình, cổng đang lắng nghe, HTTP trả 200, luật tường lửa so với loại mạng, quyền thư mục dữ liệu, `first-run.properties` đã bị xóa, tiến trình bắt đầu sau lần khởi động máy gần nhất. Script chỉ đọc.

## 7. Nâng cấp và gỡ cài đặt

### 7.1 Nâng cấp

1. Build bộ cài mới (mục 5.2) hoặc nhận file zip mới, giải nén.
2. Mở PowerShell bằng Administrator, chạy `.\install.ps1`. Script **tự nhận ra bản cài cũ** (có `application.properties`) và nâng cấp tại chỗ: chỉ thay `app\`, `runtime\`, `service\`; **giữ nguyên** dữ liệu, cấu hình, cổng, log và sao lưu; không tạo lại `first-run.properties`; cập nhật tài khoản chạy dịch vụ.
3. Chạy `check.ps1`.

Nếu bản mới có migration cơ sở dữ liệu mới, khi khởi động ứng dụng **tự sao lưu** DB vào `backups\upgrade\trainingstats-upgrade-<thời điểm>-v<phiên bản dữ liệu cũ>.db` rồi mới chạy Flyway. Sao lưu lỗi thì không nâng cấp. Migration lỗi thì ứng dụng không khởi động, dữ liệu giữ phiên bản cũ và bản sao lưu còn đó (xem mục 9.3).

Kiểm logic nâng cấp mà không cần quyền Administrator: `installer\windows\test-upgrade.ps1` (mục 11.2).

> **Quy tắc migration.** `V1__init.sql` là schema nền; không sửa nội dung file này kể cả chú thích vì Flyway tính checksum (ARCHITECTURE S13). Thay đổi schema thêm `V2__*.sql`. DB đã chạy `V1` trước khi chú thích của nó được chỉnh (checksum cũ `-669641724`) được ứng dụng **tự cập nhật** sang checksum mới `2136348535` ở lần khởi động đầu của bản mới; không cần làm gì. Mọi chênh lệch checksum khác vẫn làm ứng dụng dừng (xem mục 12.1).

### 7.2 Gỡ cài đặt

```powershell
.\uninstall.ps1                 # gỡ dịch vụ, tường lửa, chương trình; GIỮ dữ liệu
.\uninstall.ps1 -RemoveData     # xóa luôn dữ liệu, cấu hình, log, sao lưu (hỏi xác nhận, không khôi phục được)
```

Gỡ rồi cài lại bằng `install.ps1` sẽ dùng tiếp đúng cấu hình và dữ liệu cũ (kiểm file `trainingstats.db` còn nguyên kích thước).

## 8. Sao lưu

Toàn bộ dữ liệu nằm trong một file SQLite nên mất file là mất hết. Sao lưu là bắt buộc trong vận hành.

### 8.1 Các loại bản sao lưu

| Loại | Khi nào | Giữ bao lâu |
|---|---|---|
| **Tự động** | Mỗi ngày một bản, sau giờ cài đặt (mặc định 02:00, giờ Việt Nam). Máy tắt vào giờ đó thì sao lưu ngay khi bật lại trong ngày | Chỉ giữ số bản gần nhất theo cài đặt (mặc định 5, đổi được 1 đến 30); bản cũ hơn bị xóa |
| **Thủ công** | Super Admin bấm "Tạo bản sao lưu ngay" ở trang Hệ thống | Không bị xóa tự động |
| **Trước nâng cấp** | Khi cài bản mới có thay đổi cấu trúc dữ liệu, ngay trước khi nâng cấp | Không bị xóa tự động, nằm ở `backups\upgrade\` |

Tên file: `trainingstats-<loại>-<năm tháng ngày>-<giờ phút giây>.db`, theo giờ Việt Nam. Cách tạo: `VACUUM INTO` trên kết nối chỉ đọc (không giữ khóa ghi của cả hệ thống), ghi ra `.part`, mở lại kiểm `PRAGMA integrity_check` rồi mới đổi tên. Bản lỗi bị bỏ, không giữ.

### 8.2 Trang Hệ thống (Super Admin)

Menu Quản trị → **Hệ thống**: xem tình trạng (phiên bản, dung lượng DB, ổ đĩa), danh sách bản sao lưu có nút tải, "Tạo bản sao lưu ngay", cài đặt giờ sao lưu, số bản giữ và thư mục. Trang cảnh báo khi: chưa có hoặc quá 2 ngày chưa có bản tự động, thư mục sao lưu cùng ổ đĩa với dữ liệu, ổ sắp đầy.

### 8.3 Nên làm

- Đặt thư mục sao lưu **ở ổ đĩa khác hoặc ổ mạng**: hỏng ổ chứa dữ liệu thì bản sao lưu cùng ổ cũng mất. Tài khoản chạy dịch vụ (mặc định `LocalService`) phải có quyền ghi vào thư mục đó; trang Hệ thống thử ghi ngay khi lưu cài đặt.
- Định kỳ chép các bản sao lưu ra thiết bị ngoài.
- Thử khôi phục một bản trên máy khác ít nhất một lần để chắc quy trình dùng được.

## 9. Khôi phục

### 9.1 Khôi phục từ một bản sao lưu

Làm trên máy chủ, bằng tài khoản Administrator. Khôi phục là quay **toàn bộ** dữ liệu về thời điểm sao lưu; mọi thay đổi sau đó (điểm, người học, tài khoản) phải nhập lại.

1. Chọn bản cần khôi phục: tải về ở trang Hệ thống, hoặc lấy trực tiếp trong thư mục sao lưu.
2. Dừng dịch vụ (PowerShell Administrator): `Stop-Service TrainingStats` (hoặc `services.msc`).
3. Giữ lại dữ liệu hiện tại: đổi tên `trainingstats.db` thành `trainingstats.db.truoc-khoi-phuc`; làm tương tự với `trainingstats.db-wal` và `trainingstats.db-shm` nếu có. **Không xóa** các file này.
4. Chép bản sao lưu vào `C:\ProgramData\TrainingStats\data\` và đổi tên thành `trainingstats.db`.
5. Khởi động: `Start-Service TrainingStats`. Mở trang web, đăng nhập, kiểm tra dữ liệu.
6. Nếu bản sao lưu cũ hơn phiên bản chương trình đang cài, lần khởi động sẽ tự nâng cấp dữ liệu, và trước đó tạo thêm một bản trong `backups\upgrade\`.

### 9.2 Khôi phục trên máy khác

Cài bộ cài trên máy mới (mục 6), dừng dịch vụ, thay `trainingstats.db` bằng bản sao lưu như 9.1 (bước 4), khởi động lại. Tài khoản và mật khẩu lấy từ chính bản sao lưu, nên mật khẩu Super Admin nhập lúc cài mới không còn tác dụng; cần thì đặt lại (mục 10).

### 9.3 Khi nâng cấp bị lỗi

Nếu sau khi cài bản mới mà dịch vụ không khởi động:

1. Xem log mới nhất trong `C:\ProgramData\TrainingStats\logs\`. Dòng "Nang cap du lieu that bai. Ban sao luu truoc nang cap: ..." chỉ ra bản sao lưu cần dùng.
2. Dữ liệu **chưa bị đổi** khi nâng cấp lỗi: SQLite hủy cả lần nâng cấp. Có thể cài lại bản cũ để chạy tiếp, rồi báo người phát triển.
3. Nếu cần quay về hẳn, cài lại bản cũ và khôi phục bản trong `backups\upgrade\` theo 9.1.

## 10. Đặt lại mật khẩu Super Admin

Dùng khi quên mật khẩu, tài khoản Super Admin bị khóa, hoặc nâng cấp từ bản chưa có Super Admin. Công cụ **chỉ chạy được trên máy chủ**, không có đường qua mạng; nó tạo mới nếu tài khoản chưa tồn tại và không nâng quyền được cho tài khoản khác. Dịch vụ không cần dừng.

**Máy chủ đã cài:** mở PowerShell bằng Administrator và chạy

```powershell
& 'C:\Program Files\TrainingStats\reset-superadmin.ps1'              # tài khoản admin
& 'C:\Program Files\TrainingStats\reset-superadmin.ps1' -Username quantri   # tên tài khoản khác
```

Nhập mật khẩu mới hai lần (tối thiểu 8 ký tự). Mật khẩu truyền cho Java qua biến môi trường của riêng tiến trình, không qua tham số dòng lệnh.

**Khi phát triển:** chạy lại bước 1 của mục 3.1 với mật khẩu mới.

**Tài khoản khác (Admin, người dùng thường):** không dùng công cụ này; Super Admin hoặc Admin trong nhánh đặt lại ở trang chi tiết người dùng ("Thao tác khác" → Đặt lại mật khẩu).

## 11. Kiểm thử

### 11.1 Test backend

```powershell
cd D:\Training-Statistics\backend
.\mvnw.cmd test                                   # toàn bộ test, 1 đến 2 phút
.\mvnw.cmd test -Dtest=SqliteSpikeTest            # một lớp test
.\mvnw.cmd test '-Dtest=SeedDataTest,SchemaConstraintsTest'   # nhiều lớp
.\mvnw.cmd test -Dtest=LoadTest -Dload=true       # kiểm thử tải, mất vài phút
```

Kết quả mong đợi: **187 test đạt, 0 lỗi**, 1 bỏ qua (`LoadTest`, chỉ chạy khi có `-Dload=true`). Báo cáo ở `backend\target\surefire-reports\`.

Các dòng `ERROR ... Application run failed` hoặc "Migration of schema ... failed" trong log là của test **cố ý** thử nhánh lỗi (công cụ đặt lại, migration lỗi trong `UpgradeBackupTest`), không phải lỗi thật. Tiêu chí là dòng tổng kết `Failures: 0, Errors: 0`.

| Nhóm test | Lớp tiêu biểu | Kiểm |
|---|---|---|
| Nền tảng DB | `SqliteSpikeTest`, `SchemaConstraintsTest`, `SeedDataTest`, `DataAccessRulesTest` | SQLite, Flyway, ràng buộc CHECK và trigger, dữ liệu seed, quy tắc truy cập dữ liệu |
| Xác thực, phân quyền | `AuthApiTest`, `ScopeAndGuardsTest`, `EndpointAccessScanTest`, `HttpHeadersTest`, `AuditAndBootstrapTest` | Đăng nhập, CSRF, phạm vi nhánh, quét mọi endpoint, header bảo mật, nhật ký, khởi tạo Super Admin |
| Nghiệp vụ | `UserApiTest`, `UnitCatalogApiTest`, `ClassApiTest`, `RequestApiTest`, `AssignmentApiTest`, `ResultApiTest`, `StatsApiTest` | Người dùng, đơn vị, nội dung, đề nghị, phân công, điểm và xếp loại biên, thống kê |
| Excel | `ExcelImportApiTest`, `AssignmentExcelApiTest`, `PeriodResultImportApiTest` | Nhập, xuất Excel (tạo file bằng POI trong test) |
| Vận hành | `BackupApiTest`, `UpgradeBackupTest`, `LoadTest` | Sao lưu và vòng xoay, nâng cấp, tải 2.000 người và 50 người truy cập |

Quy ước: test chạm DB dùng `DbTestBase` (không web) hoặc `WebTestBase` (server thật, có Super Admin `admin` tạo từ first-run); dữ liệu phải commit, **không dùng `@Transactional`** ở lớp test. Thư mục tạm `trainingstats-*` tự dọn sau 1 giờ ở lần chạy sau. `csrf()` của spring-security-test thay kho token trong chuỗi filter thật nên test HTTP thật (`HttpHeadersTest`) chạy trong context riêng. Khi thêm logic phân quyền, xếp loại, điểm một lần, chuyển đơn vị, nhập Excel thì viết test cùng lúc; các điểm biên xếp loại: 4.9, 5.0, 6.5, 6.6, 7.9, 8.0.

Kết quả `LoadTest` đã đo (máy phát triển): 150 lần mở trang chủ do 50 người ảo, p50 493 ms, p95 866 ms, chậm nhất 991 ms, 0 lỗi (tiêu chí PRD: dưới 2 giây); 6 giám khảo cùng chấm 60 người, mỗi người đúng một điểm, còn lại 409.

### 11.2 Kiểm tra nâng cấp bộ cài

```powershell
installer\windows\test-upgrade.ps1
```

Không cần Administrator. Script tạo gói giả lập, cài vào thư mục tạm bằng `-SkipService`, ghi dữ liệu và đổi cấu hình, cài lại gói phiên bản mới rồi kiểm: chương trình được thay; dữ liệu (DB, `-wal`, backups) giữ nguyên từng byte; cấu hình giữ nguyên; không tạo lại `first-run.properties`. Không kiểm dịch vụ Windows và tường lửa (cần máy thật).

### 11.3 Kiểm tra giao diện tự động: `tools\ui-check.ps1`

Script điều khiển Edge hoặc Chrome chạy headless qua giao thức DevTools; không cần cài thêm gì. **Chỉ chạy trên máy phát triển hoặc DB thử, không chạy trên máy chủ thật:** nó đổi mật khẩu tạm của tài khoản đang dùng rồi đổi lại, và tạo dữ liệu mẫu (người dùng, đợt, nội dung, Admin mẫu, file Excel thử).

Cách chạy khuyến nghị là dùng máy chủ thử riêng (mục 3.3, cổng 18095):

```powershell
# Cửa sổ 1: máy chủ thử (mục 3.3), giữ mở
# Cửa sổ 2:
cd D:\Training-Statistics
tools\ui-check.ps1 -BaseUrl http://localhost:18095 -Username admin -Password 'MatKhau@123' -OutDir ui-check-output
```

| Tham số | Mặc định | Ghi chú |
|---|---|---|
| `-BaseUrl` | `http://localhost:8080` | Địa chỉ máy chủ thử |
| `-Username`, `-Password` | `admin`, bắt buộc | Tài khoản Super Admin |
| `-Widths` | 360, 390, 768, 1024, 1366, 1920 | Độ rộng kiểm; dưới 600 px giả lập điện thoại |
| `-Height` | 900 | |
| `-OutDir` | `ui-check-output` | Chứa ảnh `<trang>-<độ rộng>.png`, `report.txt`, các file PDF in |
| `-Browser` | tự tìm Edge, Chrome | Đường dẫn trình duyệt nếu ở chỗ khác |
| `-DebugPort` | 9333 | Cổng DevTools |
| `-IgnoreHosts` | `*.kaspersky-labs.com` | Host bỏ qua (phần mềm diệt virus chèn vào trang) |

Script thoát với mã 1 nếu có lỗi. Kết quả gần nhất: **382/382 đạt**. Muốn dừng máy chủ thử, tắt đúng tiến trình đang nghe cổng 18095; **không tắt hàng loạt tiến trình** (ví dụ mọi `msedge`) và không chạy ui-check vào dịch vụ cổng 8080.

**Mã hóa file:** `tools/ui-check.ps1` chứa chữ tiếng Việt trong các đoạn JS nên **phải lưu UTF-8 có BOM** (và CRLF). Windows PowerShell 5.1 đọc file không BOM theo ANSI và làm vỡ chữ, khiến các phép so sánh chữ luôn sai. Các script khác trong `installer\` chỉ dùng ASCII.

Mọi lệnh gọi API trong script chạy bằng `fetch` trong trình duyệt, không dùng `Invoke-WebRequest` (phần mềm diệt virus trên máy phát triển chặn cả script khi có đăng nhập bằng `Invoke-WebRequest` kèm mật khẩu).

**Đo ở mỗi trang, mỗi độ rộng:**

| Phép đo | Điều kiện đạt |
|---|---|
| Cuộn ngang cấp trang | `scrollWidth` không vượt bề rộng cửa sổ; phần tử rộng tự cuộn trong vùng của nó (bảng trong `.table-scroll`) |
| Nội dung tràn | Kích thước nội dung trình duyệt báo (`Page.getLayoutMetrics`, cũng là kích thước ảnh chụp) không vượt bề rộng; bắt được chữ tràn khỏi phần tử flex bị co khi `scrollWidth` không tăng |
| Vùng chạm | Mọi nút, liên kết, ô nhập, ô chọn hiển thị tối thiểu 44×44 px (checkbox tính theo nhãn bao quanh) |
| Cỡ chữ ô nhập | Tối thiểu 16 px (nhỏ hơn thì Safari iOS tự phóng to khi chạm) |
| Thanh trên | Các phần tử không đè lên nhau |
| Hộp thoại | Sau khi chuyển trang không còn hộp thoại cũ; không bao giờ có hai hộp thoại chồng nhau |
| Trang lỗi | Không hiển thị "Không tải được trang" |
| Chữ lạc | Không có "null", "undefined", "NaN", "[object Object]" |
| Lỗi | Không có lỗi JavaScript, `console.error`, vi phạm CSP |
| Tài nguyên ngoài | Không có request nào ra khỏi máy chủ (CDN, font, ảnh) |

Trước khi kiểm từng trang, script nạp thử **mọi module** trong `frontend/js` (trừ `app.js`, `login.js`) để bắt lỗi cú pháp ở cả trang chưa mở.

**Các nhóm kịch bản chức năng** (chạy ở 1366 px, một số ở 360 px; tên kịch bản dùng để tìm trong `ui-check.ps1` và `report.txt`):

| Nhóm | Kịch bản tiêu biểu | Kiểm |
|---|---|---|
| Phiên, nền tảng | `sai-mat-khau-bao-loi`, `doi-mat-khau`, `dang-xuat`, `sau-dang-xuat-bi-chan`, `nap-moi-module`, `bieu-tuong-svg-hop-le`, `thu-gon-thanh-ben`, `xem-nhanh-thong-bao`, `menu-chi-danh-dau-muc-dung` | Đăng nhập, đổi mật khẩu, đăng xuất, module nạp được, `icons.svg` hợp lệ và đủ biểu tượng, thanh bên, chuông, menu |
| Thành phần chung | `phan-trang-nhat-ky`, `loc-theo-hanh-dong`, `bang-sap-xep`, `hop-thoai-xac-nhan`, `thanh-phan-select-nhieu-lua-chon` | Phân trang, lọc, sắp xếp (`aria-sort`), hộp thoại xác nhận (Esc), chọn nhiều |
| Người dùng | `tao-nguoi-dung-qua-form`, `tim-kiem-khong-dau`, `nguoi-dung-loc-nhieu-lua-chon`, `khoa-mo-khoa-tren-dong`, `thao-tac-khac-tren-dong-nguoi-dung`, `cap-tai-khoan-o-chi-tiet`, `chuyen-don-vi-chon-radio`, `dat-lai-mat-khau-qua-giao-dien` | Form vừa màn 1366×900, mã tự cấp `ND...`, tìm không dấu, bộ lọc hai hàng, thao tác trên dòng, cấp tài khoản, chuyển đơn vị |
| Nội dung, người học, đề nghị | `them-noi-dung-qua-hop-thoai-kem-giam-khao`, `them-nguoi-vao-lop-qua-giao-dien`, `them-don-vi-vao-lop-qua-giao-dien`, `go-nguoi-va-loc-da-go`, `ghi-ly-do-vang-qua-giao-dien`, `nguoi-hoc-tim-nhanh-loc-nhieu-don-vi`, `nguoi-hoc-loc-tinh-trang-xep-loai`, `noi-dung-chua-cham-xong-noi-bat`, `thao-tac-tren-dong-lop`, `duyet-de-nghi-qua-giao-dien`, `duyet-hang-loat-theo-noi-dung`, `bam-thong-bao-mo-de-nghi`, `chuong-co-so-chua-doc`, `admin-de-nghi-go-qua-giao-dien`, `admin-tao-de-nghi-them-don-vi` | Hộp thoại thêm nội dung, thêm, gỡ người, lọc, tiến độ chấm, duyệt hàng loạt, thông báo, Admin chỉ thấy nhánh mình |
| Phân công | `phan-cong-tung-don-vi-va-loc`, `phan-cong-khong-co-ket-qua`, `phan-cong-super-admin-them-truc-tiep`, `admin-phan-cong-chi-thay-nhanh-va-gui-de-nghi`, `phan-cong-bang-excel-tai-va-kiem` | Bảng phân công vừa khít màn hình, Trước/Sau, khối "không có kết quả", thêm trực tiếp, gửi đề nghị, Excel |
| Chấm điểm | `cham-diem-gom-don-vi-va-loc`, `cham-diem-luu-nhieu-nguoi-mot-lan`, `nhap-sai-diem-bao-loi`, `xep-loai-xem-truoc-khop-may-chu`, `sua-diem-va-lich-su`, `lich-su-diem-o-trang-nguoi-dung`, `lich-su-diem-cot-thang-hang` | Gom theo đơn vị, lưu nhiều người một lần, `6.55` báo lỗi, xếp loại xem trước khớp máy chủ ở 0, 4.9, 5.0, 6.5, 6.6, 7.9, 8.0, 10 (ARCHITECTURE D6), sửa điểm, lịch sử điểm lọc theo ngày và cột thẳng hàng |
| Excel | `nhap-nguoi-dung-excel-bao-loi`, `nhap-nguoi-dung-excel-ghi`, `nhap-lop-excel`, `nhap-diem-excel`, `xuat-diem-excel`, `cham-diem-ca-dot-tai-va-kiem`, `dot-kiem-tra-co-lien-ket-cham-ca-dot` | Tải mẫu, báo lỗi theo dòng, ghi, xuất `.xlsx` (kiểu nội dung, chữ ký PK) |
| Thống kê, dashboard | `thong-ke-loc-nhieu-don-vi`, `thong-ke-bo-chon-don-vi`, `thong-ke-loc-theo-noi-dung`, `thong-ke-lop-khop-du-lieu`, `thong-ke-bang-khop-tong`, `thong-ke-chua-kiem-tra`, `dashboard-co-bieu-do` | Số trên giao diện khớp dữ liệu lấy từ API khác; 6 thẻ biểu đồ ở trang chủ |
| In ấn | `in-ket-qua-dong-bo-excel`, `in-ket-qua-theo-bo-loc`, `in-thong-tin-ca-nhan-theo-bo-loc`, `ban-in-den-trang`, `print-class-results`, `print-user` | Trang in cùng bố cục Excel, in theo bộ lọc, giả lập chế độ in đen trắng, lưu PDF `print-*.pdf` để mở xem tiếng Việt |
| Hệ thống | `he-thong-sao-luu`, `font-inter-hien-thi-tieng-viet` | Trang Hệ thống, tạo và tải sao lưu; font Inter đã tải, đã áp dụng, không tải từ ngoài |

**Khi thêm trang mới:**

1. Thêm trang vào danh sách `$pages` trong `tools/ui-check.ps1`.
2. Nếu trang có trạng thái mở (hộp thoại, menu, form báo lỗi), thêm một khối `Check` riêng như `audit-modal`. Nếu khối đó mở **cùng địa chỉ** với khối ngay trước thì mở một trang khác trước: mở lại đúng địa chỉ đang đứng không sinh `hashchange` nên hộp thoại cũ không đóng.
3. Chạy script, xem ảnh chụp ở 360 px và 1366 px (mục 11.4). **Một trang chỉ được tích `[x]` trong PLAN khi đã qua phần tự động và phần nhìn bằng mắt.**

**Phần mềm diệt virus:** Kaspersky tự chèn script vào mọi trang (`*.kaspersky-labs.com`), kể cả trình duyệt headless, còn sửa header CSP và chèn style nội tuyến nên console có lỗi "Applying inline style violates ... kaspersky-labs.com". Script bỏ qua request và lỗi console có tên host trong `-IgnoreHosts` và in số lượng đã bỏ qua. Ứng dụng không đặt style nội tuyến nào, nên trên máy không có phần mềm này mọi vi phạm CSP vẫn bị báo. Máy có phần mềm tương tự thì thêm host vào `-IgnoreHosts`.

### 11.4 Kiểm tra bằng mắt

Đo tự động không bắt được mọi lỗi. Các lỗi từng chỉ thấy được khi xem ảnh (sau đó mới thêm phép đo nếu đo được): nút "Đăng xuất" đè nhãn vai trò trên điện thoại; hộp thoại còn mở sau khi chuyển trang; chữ "null" lạc trên form; mã đơn vị rơi xuống hàng riêng trong thẻ; chuông thông báo làm nhãn "SUPER ADMIN" tràn khỏi thanh trên ở 360 px (ảnh rộng 369 px); hộp thoại của lần kiểm trước nằm dưới hộp thoại mới (lỗi của bộ kiểm tra khi mở lại cùng địa chỉ).

Với mỗi trang, xem ảnh ở **360 px** và **1366 px**, đánh dấu:

- [ ] Chữ tiếng Việt đủ dấu, không bị cắt mất dấu phía trên (chữ hoa có dấu như "Ố", "Ỗ")
- [ ] Nội dung chính không bị thanh tab đáy che (cuối trang có khoảng trống đủ)
- [ ] Bảng ở 360 px là danh sách thẻ, nhãn bên trái, giá trị bên phải
- [ ] Nút chính nổi bật (nền xanh hải quân), nút phụ có viền
- [ ] Trạng thái rỗng, đang tải, lỗi có câu thông báo rõ ràng
- [ ] Xếp loại, trạng thái có chữ, không chỉ dùng màu
- [ ] Bản in (Ctrl+P) đen trắng, không có thanh điều hướng

Lưu ý: ảnh chụp cả trang (dài hơn 900 px) vẽ thanh tab đáy ở vị trí của khung nhìn 900 px; trên máy thật thanh này luôn dính đáy màn hình.

### 11.5 Việc chưa kiểm tra được trên máy phát triển

| Việc | Cần |
|---|---|
| Cài bản cuối trên máy Windows sạch và chạy `check.ps1` | Máy sạch (chưa có Java) |
| Khởi động lại máy rồi xem dịch vụ có tự chạy | Máy thật, quyền Administrator |
| Truy cập từ điện thoại Android, iOS, Firefox | Thiết bị thật |
| Hiển thị tiếng Việt, chấm điểm bằng cảm ứng trên Android, iOS | Thiết bị thật (mở trang đăng nhập, trang chủ, chụp màn hình, đối chiếu mục 11.4) |
| Thử file Excel do người dùng lập trên Excel và LibreOffice | Phần mềm tương ứng (test dựng file bằng POI; đã kiểm bằng Excel 2016) |
| Một lần sao lưu tự động chạy đúng 02:00 trên dịch vụ thật | Chờ qua đêm (lịch đã kiểm bằng test logic) |

## 12. Lỗi thường gặp

### 12.1 Build và chạy từ mã nguồn

| Triệu chứng | Nguyên nhân | Cách xử lý |
|---|---|---|
| `Khong tim thay jlink. Dat JAVA_HOME ...` | `JAVA_HOME` chưa đặt hoặc trỏ tới JRE không có `jlink.exe` | Đặt `JAVA_HOME` tới JDK 25 đầy đủ, hoặc `build.ps1 -JavaHome '...'` |
| `mvnw.cmd is not recognized` | Đang đứng sai thư mục, hoặc gọi trong Git Bash | `cd backend`, dùng `.\mvnw.cmd` trong PowerShell (Git Bash dùng `./mvnw`) |
| Maven báo `invalid target release: 25` hoặc lỗi phiên bản class | JDK đang dùng cũ hơn 25 | Kiểm `java -version` và `mvnw.cmd -v`, chỉnh `JAVA_HOME` |
| `Unrecognized option: --app.data-dir` (hoặc `--server.port`) khi `spring-boot:run` | Tham số `-Dspring-boot.run.arguments=...` bị tách, hoặc truyền vào `Start-Process` không có nháy bao | Đặt **cả** `"-Dspring-boot.run.arguments=--a=1 --b=2"` trong một cặp nháy kép; trong `Start-Process -ArgumentList` cũng bọc nguyên cả đối số |
| `Could not find or load main class .encoding=UTF-8` | PowerShell 5.1 tách `-Dfile.encoding=UTF-8` tại dấu chấm khi gọi chương trình ngoài | Đặt tham số `-Dx.y=z` **trong nháy**: `'-Dfile.encoding=UTF-8'` (các script bộ cài đã làm; bản script cũ phải build, cài lại) |
| `Found more than one migration with version 1` | File migration đã xóa còn trong `target\classes` | `mvnw.cmd clean package ...` (luôn có `clean`) |
| `Migration checksum mismatch for migration version 1` | Nội dung `V1__init.sql` trong bản cài khác với lúc DB được tạo (file migration bị sửa). Riêng lần đổi chú thích đã ghi nhận (`-669641724` → `2136348535`) thì ứng dụng tự cập nhật, không báo lỗi này | Không sửa file migration đã có. Bản cài cũ hơn bản có `UpgradeBackup.alignV1Checksum` (bộ cài build trước ngày 2026-11-24 lúc sửa lỗi này): build lại bằng mã hiện tại rồi chạy lại `install.ps1` |
| Test lỗi, surefire không nạp được lớp test khi dự án và `TEMP` khác ổ đĩa | Giới hạn của Maven surefire trên Windows | Đã xử lý bằng `-Djdk.net.URLClassPath.disableClassPathURLCheck=true` trong `pom.xml`; không cần làm gì |
| `Port 8080 was already in use` | Dịch vụ thật hoặc tiến trình khác đang dùng cổng | Chạy bản thử ở cổng khác (`--server.port=18095`, mục 3.3). **Đừng dừng dịch vụ thật** chỉ để chạy thử |
| Máy chủ lên nhưng đăng nhập báo sai, DB mới | Chưa tạo Super Admin | Mục 3.1 |
| Chữ lạ trong PowerShell khi xem JSON | Máy chủ cũ không gửi `charset=UTF-8` | Bản hiện tại gửi `charset=UTF-8` ở mọi response; cập nhật máy chủ |
| `403 CSRF_INVALID` khi gọi API ghi | Thiếu hoặc sai header `X-XSRF-TOKEN` | Lấy `GET /auth/csrf` ngay trước mỗi request ghi (token đổi sau đăng nhập) |
| `400 FILE_TOO_LARGE` / `TOO_MANY_ROWS` | File Excel quá 5 MB hoặc 5.000 dòng | Chia thành nhiều file |

### 12.2 Cài đặt và dịch vụ

| Triệu chứng | Nguyên nhân | Cách xử lý |
|---|---|---|
| `Can chay bang quyen Administrator.` | Mở PowerShell thường | Chuột phải PowerShell → Run as administrator, hoặc dùng `install.cmd` |
| Script bị chặn bởi Execution Policy | Chính sách PowerShell | Chạy `powershell -NoProfile -ExecutionPolicy Bypass -File .\install.ps1`, hoặc `install.cmd` |
| Máy khác, điện thoại không vào được dù `localhost` được | Mạng đang là **Public**, luật tường lửa chỉ mở Domain và Private | Mục 6.3: đổi mạng sang Private, hoặc `install.ps1 -AllowPublicNetwork` |
| `check.ps1` báo `first-run.properties van con` | Bản cài rất cũ đặt sai quyền file nên ứng dụng không xóa được; hoặc dịch vụ chưa khởi động lần nào | Cài lại bằng gói mới (`install.ps1`) để sửa quyền; dịch vụ khởi động lại sẽ tự xóa. Muốn xóa ngay: `Remove-Item C:\ProgramData\TrainingStats\config\first-run.properties` (PowerShell Administrator) |
| Nâng cấp từ bản chưa có Super Admin, đăng nhập không được | Bộ cài chỉ tạo Super Admin khi cài mới | Chạy `reset-superadmin.ps1` (mục 10) |
| `install.ps1` dừng ở bước "Khoi dong dich vu", hoặc dịch vụ không khởi động sau nâng cấp | Ứng dụng lỗi khi khởi động (migration lỗi, checksum Flyway lệch, cổng bị chiếm, thiếu quyền thư mục) nên tiến trình thoát và dịch vụ chuyển Stopped | `install.ps1` in dấu chấm trong lúc chờ (tối đa 3 phút), tự dừng khi dịch vụ đã dừng hẳn và in 25 dòng cuối của log ứng dụng và log WinSW. Đọc các dòng đó; xem thêm mục 9.3, mục 7.1; đổi cổng ở mục 6.4 |
| `Stop-Service` / `Start-Service` báo không tìm thấy | Tên dịch vụ là `TrainingStats` | `Get-Service TrainingStats`; chưa cài thì chạy `install.ps1` |
| Trang Hệ thống cảnh báo sao lưu cùng ổ đĩa, hoặc lưu cài đặt báo "Không tạo hoặc ghi được vào thư mục này" | Thư mục sao lưu mới không có quyền ghi cho tài khoản dịch vụ (`LocalService`) | Cấp quyền ghi cho `NT AUTHORITY\LocalService` trên thư mục đó; ổ mạng cần quyền tương ứng |
| Cổng hoặc dữ liệu đã đổi nhưng ứng dụng vẫn dùng giá trị cũ | Chưa khởi động lại dịch vụ | `Restart-Service TrainingStats` |
| `uninstall.ps1` xóa nhầm dữ liệu | Dùng `-RemoveData` | Không khôi phục được; khôi phục từ sao lưu đã chép ra ngoài (mục 9.2) |

### 12.3 Dữ liệu và sử dụng

| Triệu chứng | Nguyên nhân | Cách xử lý |
|---|---|---|
| `409 ALREADY_GRADED` | Người học đã có điểm (hoặc hai giám khảo lưu cùng lúc) | Đúng thiết kế: chỉ Super Admin sửa điểm, kèm lý do |
| `409 CLASS_CLOSED` / `PERIOD_CLOSED` | Nội dung hoặc đợt đã đóng | Mở lại đợt hoặc nội dung; khóa đợt thì mọi nội dung của đợt đóng theo |
| Admin không thấy một người hoặc nội dung chi tiết | Ngoài phạm vi nhánh (trả 404, không phải 403) | Đúng thiết kế; kiểm đơn vị hiện tại của người đó |
| Sau chuyển đơn vị, Admin cũ mất quyền với người đó | Đúng thiết kế; nếu đích ngoài phạm vi thì người đó là `ADMIN` bị hạ về `USER` | Admin ở đơn vị đích cấp lại quyền |
| Nhập Excel báo "File không đọc được" | File không phải `.xlsx` (Excel 2007 trở lên), hoặc hỏng | Lưu lại thành Excel Workbook từ file mẫu |
| Nhập Excel: ô cấp bậc, mã định danh bị đổi thành ngày hoặc số khoa học | Excel tự đổi định dạng khi gõ tay | Dùng đúng file mẫu (cột định dạng Text, ô chọn từ danh sách) |
| Quên mật khẩu Super Admin | | Mục 10 |
| Thông báo trên chuông không cập nhật | Tab ẩn hoặc không có thao tác trong thời gian hết phiên | Đúng thiết kế (R26); thao tác lại hoặc tải lại trang |
| `SQLITE_BUSY` / `database is locked` trong log | Có nơi ghi dữ liệu ngoài giao dịch ghi, hoặc tiến trình khác giữ file DB | Mọi lệnh ghi phải nằm trong giao dịch ghi (ARCHITECTURE 4.2); không mở file `.db` đang chạy bằng công cụ khác ở chế độ ghi |
| `Bad value for type Long` khi đọc cột | Đọc cột có thể NULL bằng `rs.getObject(col, Long.class)` | Dùng `JdbcValues.nullableLong/nullableInt` |

### 12.4 Kiểm tra giao diện

| Triệu chứng | Nguyên nhân | Cách xử lý |
|---|---|---|
| Phép so sánh chữ tiếng Việt trong `ui-check` luôn sai | File lưu không có BOM, PowerShell 5.1 đọc ANSI | Lưu `tools/ui-check.ps1` là UTF-8 **có BOM** |
| Script bị diệt virus chặn "contains malicious content" | Có đoạn đăng nhập bằng `Invoke-WebRequest` | Gọi API bằng `fetch` trong trình duyệt (cách hiện tại) |
| Lỗi console CSP, style nội tuyến chỉ có trên máy này | Phần mềm diệt virus (Kaspersky) chèn vào | `-IgnoreHosts` |
| Ảnh chụp có hộp thoại của lần kiểm trước | Mở lại cùng địa chỉ không sinh `hashchange` | Mở một trang khác trước khi mở khối kiểm tiếp theo |
| `Khong tim thay Edge hoac Chrome` | Trình duyệt ở đường dẫn lạ | Truyền `-Browser '...\msedge.exe'` |
| Cổng 9333 bận | Còn trình duyệt headless từ lần trước | Đổi `-DebugPort`, hoặc kết thúc đúng tiến trình đó (không tắt hàng loạt trình duyệt) |

## 13. Phụ lục

### Phụ lục A. Kết quả kiểm chứng kỹ thuật (M0)

**Phiên bản đã chốt**

| Thành phần | Phiên bản | Ghi chú |
|---|---|---|
| JDK | Temurin 25 (LTS) | Đổi từ Java 21 vì máy phát triển có JDK 25; Spring Boot 3.5 chạy tốt |
| Spring Boot | 3.5.16 | |
| SQLite | 3.54.1 | Đóng gói trong `sqlite-jdbc` 3.54.1.0 |
| Flyway | 11.7.2 | Hỗ trợ SQLite sẵn trong `flyway-core`, không cần module riêng |
| Apache POI | 5.4.1 | Nhập, xuất Excel |
| Maven | 3.9.16 qua Maven Wrapper | Không cần cài |
| WinSW | 2.12.0 (`WinSW-x64.exe`) | Chọn 2.12.0 thay 2.13.0 vì bản 2.13.0 mới phát hành, chưa được cộng đồng soi |

**SQLite và Flyway (R17): 10/10 đạt** (bộ test `SqliteSpikeTest`, migration mẫu `backend/src/test/resources/db/spike/V1__spike.sql`)

| # | Kiểm chứng | Kết quả |
|---|---|---|
| 1 | Flyway chạy migration có `CREATE TRIGGER ... BEGIN ... END`, cột tính tự động, partial index | Đạt |
| 2 | `PRAGMA foreign_keys` = 1 trên cả 4 kết nối trong pool, vi phạm khóa ngoại bị chặn | Đạt |
| 3 | `journal_mode=wal`, `busy_timeout=5000`, `synchronous=1` áp dụng được qua tham số URL | Đạt |
| 4 | Cột tính `grade` cho điểm 0, 49, 50, 65, 66, 79, 80, 100 ra đúng; CHECK chặn 101 và -1 | Đạt |
| 5 | Partial unique index `WHERE removed_at IS NULL`: chặn trùng, cho thêm lại sau khi gỡ | Đạt |
| 6 | Trigger `RAISE(ABORT)` chặn `DELETE` cứng và `UPDATE`/`DELETE` trên bảng nhật ký | Đạt |
| 7 | `VACUUM INTO` khi luồng khác đang ghi: bản sao mở lại được, `integrity_check` = ok | Đạt |
| 8 | 8 luồng ghi đồng thời (400 lần), không lỗi BUSY | Đạt |
| 9 | 8 luồng "đọc rồi ghi" trong giao dịch, không lỗi BUSY | Đạt, **chỉ khi có `transaction_mode=IMMEDIATE`** |
| 10 | 8 luồng cùng chấm một người: đúng 1 thành công, 7 bị UNIQUE chặn | Đạt |

**Phát hiện ảnh hưởng đến thiết kế**

| # | Nội dung |
|---|---|
| F1 | **`transaction_mode=IMMEDIATE` là bắt buộc** (đặt ở `SqliteConfig`). Không có nó, giao dịch đọc rồi ghi ở chế độ WAL bị nâng cấp khóa muộn và không tuân theo `busy_timeout` nên lỗi `SQLITE_BUSY`. Hệ quả: mọi giao dịch kể cả chỉ đọc đều lấy khóa ghi; chấp nhận được với dưới 50 người cùng lúc |
| F2 | Spring không dịch được lỗi SQLite: mọi lỗi ràng buộc đều thành `UncategorizedSQLException`. Đã xử lý bằng `SqliteExceptionTranslator` (UNIQUE, PRIMARYKEY → `DuplicateKeyException`; FOREIGNKEY, CHECK, NOTNULL → `DataIntegrityViolationException`; BUSY → `CannotAcquireLockException`) |
| F3 | JDK 25 cần cờ `--enable-native-access=ALL-UNNAMED` cho `sqlite-jdbc`; đã thêm vào cấu hình dịch vụ WinSW và các lệnh chạy JAR |
| F4 | Surefire trên Windows không nạp được lớp test khi dự án và `TEMP` khác ổ; đã xử lý trong `pom.xml` |
| F5 | Kích thước và tài nguyên (đo ở M0): JRE gọn 62,4 MB (21 module), JAR 59,4 MB, gói zip 104,6 MB; khởi động đến HTTP 200 khoảng 2 giây; RAM ứng dụng trống khoảng 226 MB |

**Quyết định và rủi ro phát sinh từ đóng gói**

| # | Nội dung |
|---|---|
| D1 | Java 25 thay cho Java 21 |
| D2 | WinSW 2.12.0 thay vì 2.13.0 |
| D3 | WinSW chưa có chữ ký số và GitHub không công bố checksum 2.12.0; SHA256 ghim trong `build.ps1` là giá trị tính từ file tải lần đầu ("tin lần đầu"). Cần chắc chắn hơn thì đối chiếu nguồn thứ hai hoặc tự build WinSW từ mã nguồn |
| D4 | Mật khẩu Super Admin ban đầu nằm ở `config\first-run.properties` dạng văn bản thường, quyền chỉ SYSTEM và Administrators; ứng dụng đọc, băm BCrypt rồi xóa file ngay (có test). `Restrict-Acl` dựng ACL mới chỉ gồm SYSTEM và Administrators, vì `icacls /grant:r` không xóa quyền thừa |
| D5 | Dịch vụ chạy bằng `NT AUTHORITY\LocalService` (đổi từ `LocalSystem` mặc định của WinSW) |
| D6 | Luật tường lửa mặc định chỉ mở cho Domain và Private; mạng Public cần `-AllowPublicNetwork` hoặc đổi loại mạng (mục 6.3) |
| D7 | Mặc định chặn mọi request chưa đăng nhập (đã thay `permitAll` tạm thời của M0) |
| D8 | Script PowerShell trong `installer\` dùng tiếng Việt **không dấu** để tránh lỗi mã hóa của Windows PowerShell 5.1 khi đọc UTF-8 không BOM; riêng `tools/ui-check.ps1` có BOM |
| D9 | Thư mục dịch vụ: `C:\ProgramData\TrainingStats\{config,data,logs,backups}`; DB là `data\trainingstats.db` |

Đã kiểm thật trong quá trình đóng gói: chạy ứng dụng bằng JRE đóng kèm khi không có `java` trong `PATH` và `JAVA_HOME`; cài mới, nâng cấp (giữ cấu hình và dữ liệu), đổi cổng khi nâng cấp; gỡ giữ dữ liệu và cài lại dùng tiếp; dịch vụ Running, Automatic, HTTP 200 qua `localhost` và IP của máy; thư mục cấu hình bị từ chối với tài khoản thường.

### Phụ lục B. Độ tương phản màu (WCAG)

Màu Hải quân, kiểu dashboard. Ngưỡng AA: 4.5:1 cho chữ thường, 3:1 cho chữ lớn và thành phần giao diện (viền, vạch, biểu tượng). Các giá trị nằm trong `frontend/css/tokens.css`; đổi màu thì đo lại và cập nhật bảng này.

| Cặp màu | Tỷ lệ | Kết quả | Dùng cho |
|---|---|---|---|
| `#0E1A2B` trên `#F4F6FA` | 16.16 | Đạt | Chữ trên nền trang |
| `#0E1A2B` trên `#FFFFFF` | 17.48 | Đạt | Chữ trên nền thẻ, bảng |
| `#4A5868` trên `#F4F6FA` | 6.72 | Đạt | Chữ phụ |
| `#4A5868` trên `#F7F9FC` | 6.90 | Đạt | Tiêu đề cột bảng |
| `#4A5868` trên `#EEF2F7` | 6.47 | Đạt | Nút chưa chọn trong bộ lọc dạng tab |
| `#1F4E8C` trên `#E8EFF8` | 7.18 | Đạt | Mục đang chọn ở thanh tab đáy, menu "Thêm", nhãn vai trò, nút phụ khi rê |
| `#0F2A4A` trên `#E8EFF8` | 12.50 | Đạt | Nhãn trạng thái mặc định, khối thông tin |
| `#F2F5FA` trên `#0F2A4A` | 13.25 | Đạt | Chữ trên thanh trên (điện thoại) |
| `#C3CEDC` trên `#0A1A30` | 10.95 | Đạt | Chữ menu trên thanh bên |
| `#E8C766` trên `#173A66` | 6.98 | Đạt | Mục đang chọn ở thanh bên |
| `#FFFFFF` trên `#A8261C` | 7.10 | Đạt | Số thông báo, nút nguy hiểm |
| `#7A889A` trên `#FFFFFF` | 3.61 | Đạt (3:1) | Viền ô nhập |
| `#FFFFFF` trên `#1F4E8C` | 8.31 | Đạt | Nút chính; tiêu đề trên nền xanh ở chỗ sáng nhất |
| `#1F4E8C` trên `#FFFFFF` | 8.31 | Đạt | Liên kết, nút phụ |
| `#C3CEDC` trên `#1F4E8C` | 5.22 | Đạt | Chữ phụ trên nền xanh |
| `#E8C766` trên `#1F4E8C` | 5.06 | Đạt | Vàng sáng trên nền xanh |
| `#8FA0B6` trên `#0A1A30` | 6.54 | Đạt | Tiêu đề nhóm menu trên thanh bên |
| `#0A1A30` trên `#E8C766` | 10.63 | Đạt | Chữ trong ảnh đại diện, số thông báo trên thanh trên xanh |
| `#A8261C` trên `#FFFFFF` | 7.10 | Đạt | Chữ lỗi |
| `#1F6B3A` trên `#FFFFFF` | 6.52 | Đạt | Chữ thành công |
| `#8A5300` trên `#FFFFFF` | 6.33 | Đạt | Chữ cảnh báo |
| `#E8C766` trên `#0F2A4A` | 8.82 | Đạt | Chữ nhấn trên nền tối |
| `#C9A227` trên `#0F2A4A` | 5.99 | Đạt | Viền vàng kim, chữ trên nền tối |
| `#C9A227` trên `#FFFFFF` | 2.42 | **Không đạt** | **Không dùng vàng kim trên nền sáng** |
| `#8A6A10` trên `#FFFFFF` | 5.06 | Đạt (3:1) | Viền focus trên nền sáng |
| `#8A6A10` trên `#F4F6FA` | 4.68 | Đạt (3:1) | Viền focus trên nền trang |

Viền thẻ, bảng `#E1E7EF` chỉ để trang trí (thẻ đã tách khỏi nền bằng màu nền và bóng đổ) nên không cần đạt 3:1. Viền ô nhập và nút phụ là viền nhận biết thành phần nên phải đạt.

### Phụ lục C. Font

Font chính là **Inter** bản biến thiên (400 đến 700, giấy phép OFL), lưu sẵn ở `frontend/assets/fonts` với ba tập latin, latin-ext, vietnamese; cỡ chữ nền 15 px. Không tải font từ ngoài, nên mọi máy hiển thị giống nhau và chạy được khi không có Internet. Chữ mã (mã định danh, mã đơn vị) dùng font đơn cách `Consolas, "Courier New", monospace`.

| Nền tảng | Font | Dấu tiếng Việt | Đã kiểm |
|---|---|---|---|
| Windows 10, 11 | Inter (lưu sẵn) | Đủ dấu ("Quản lý huấn luyện", "Đổi mật khẩu", "Hoàng Thị Điểm") | Có, qua ảnh chụp Edge và kịch bản `font-inter-hien-thi-tieng-viet` |
| Android | Inter (lưu sẵn) | Tập `vietnamese` của Inter | **Chưa**, cần máy thật |
| iOS | Inter (lưu sẵn) | Tập `vietnamese` của Inter | **Chưa**, cần máy thật |

### Phụ lục D. Lệnh nhanh

| Việc | Lệnh |
|---|---|
| Chạy từ mã nguồn | `cd backend; .\mvnw.cmd spring-boot:run` |
| Tạo Super Admin khi phát triển | `$env:TS_RESET_PASSWORD='...'; .\mvnw.cmd -q spring-boot:run "-Dspring-boot.run.arguments=--reset-superadmin"` |
| Test toàn bộ | `cd backend; .\mvnw.cmd test` |
| Một lớp test | `.\mvnw.cmd test -Dtest=TênLớp` |
| Tạo JAR | `cd backend; .\mvnw.cmd clean package -DskipTests` |
| Tạo bộ cài | `installer\windows\build.ps1 [-SkipTests]` |
| Cài, nâng cấp | `install.ps1` (Administrator) |
| Kiểm tra sau cài | `& 'C:\Program Files\TrainingStats\check.ps1'` |
| Gỡ | `uninstall.ps1 [-RemoveData]` |
| Đặt lại mật khẩu Super Admin | `& 'C:\Program Files\TrainingStats\reset-superadmin.ps1'` |
| Dừng, chạy, khởi động lại dịch vụ | `Stop-Service TrainingStats`, `Start-Service TrainingStats`, `Restart-Service TrainingStats` |
| Kiểm nâng cấp bộ cài | `installer\windows\test-upgrade.ps1` |
| Kiểm giao diện | `tools\ui-check.ps1 -BaseUrl http://localhost:18095 -Password '...'` |
