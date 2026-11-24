# Kế hoạch triển khai

Dựa trên [PRD.md](PRD.md) và [ARCHITECTURE.md](ARCHITECTURE.md). Các mốc M0–M7 lấy từ mục 9 của ARCHITECTURE.md.

> **Thuật ngữ.** Trên giao diện và trong thông báo, "lớp" gọi là **nội dung kiểm tra** và "giáo viên" gọi là **giám khảo**. Trong mã, API, bảng và các mục kỹ thuật vẫn dùng `class`/`exam_class`/`classes` và `teacher`/`class_teacher`, nên "lớp" và "giáo viên" ở các mục kỹ thuật dưới đây chính là hai khái niệm này. Hệ thống không có trường "Môn kiểm tra".

## Cách dùng

- Mỗi dòng là một việc nhỏ, làm xong trong khoảng nửa ngày trở xuống. Việc nào lớn hơn thì tách tiếp.
- Đánh dấu `[x]` **chỉ khi** việc đó xong thật: code chạy, có test (nếu là logic), đã thử trên giao diện (nếu là màn hình). Kế hoạch này chỉ ghi các việc đã hoàn thành và đã kiểm tra; việc chưa làm hoặc chưa kiểm tra được thì chưa đưa vào.
- Làm theo thứ tự mốc. Không bắt đầu mốc sau khi mốc trước chưa đạt dòng "Hoàn thành khi".
- Mã trong ngoặc như (R1), (Q3), (E9) là rủi ro, quyết định hoặc ghi chú kỹ thuật tương ứng trong ARCHITECTURE.md; "PRD 41" là quyết định số 41 trong PRD.md.
- Mỗi mốc có thể commit riêng. Nên commit sau mỗi nhóm việc.

## Tình trạng hiện tại

| Mốc | Nội dung | Tình trạng |
|---|---|---|
| M0 | Dự án mẫu, kiểm chứng SQLite và bộ cài Windows | Đạt |
| M1 | Nền tảng: schema, xác thực, phân quyền, audit, khung frontend, phong cách giao diện | Đạt |
| M2 | Quản lý user và đơn vị | Đạt |
| M3 | Đợt, nội dung kiểm tra, người học, đề nghị, thông báo, bảng phân công | Đạt |
| M4 | Chấm điểm và hồ sơ cá nhân | Đạt |
| M5 | Nhập, xuất Excel | Đạt |
| M6 | Thống kê, dashboard, in ấn | Đạt |
| M7 | Sao lưu, nâng cấp, quản trị, kiểm thử tổng thể, tài liệu bàn giao | Đạt |

Kết quả kiểm tra gần nhất: backend 187 test đạt (1 bỏ qua là `LoadTest`, chạy riêng với `-Dload=true` đạt); `tools/ui-check.ps1` 382/382 trên DB mới, máy chủ thử cổng riêng; `installer/windows/test-upgrade.ps1` đạt; rà soát mã bằng PMD, CPD và công cụ kiểm JS, CSS: hết đoạn trùng lặp (CPD 0), hết hàm quá phức tạp, không import thừa, không `export` thừa; PMD còn lại hai loại có chủ đích (tham số dài ở constructor tiêm phụ thuộc; đổi lỗi kỹ thuật thành lỗi 4xx nên không giữ stack trace).

---

## M0. Dự án mẫu và kiểm chứng kỹ thuật

Mục tiêu: loại bỏ sớm hai rủi ro kỹ thuật lớn nhất, là SQLite/Flyway (R17) và bộ cài Windows chạy dịch vụ (R12).

### M0.1 Khung dự án

- [x] Tạo thư mục `backend/`, `frontend/`, `installer/windows/` theo mục 4 của ARCHITECTURE.md
- [x] Tạo `backend/pom.xml` với Spring Boot 3.5, Java 25
- [x] Thêm các phụ thuộc backend: Spring Web, Spring Security, JDBC, Validation, sqlite-jdbc, Flyway, Apache POI
- [x] Tạo lớp khởi động `TrainingStatsApplication` và chạy được trống
- [x] Cấu hình Maven copy `frontend/` vào `static/` khi build
- [x] Tạo `.gitignore` (target, file DB, log, IDE)
- [x] Tạo `README.md` gốc: cách build và chạy ở máy phát triển

### M0.2 Kiểm chứng SQLite (R17)

- [x] Kết nối SQLite qua `sqlite-jdbc`, đặt `PRAGMA foreign_keys=ON` cho **mọi kết nối** trong pool
- [x] Bật WAL, đặt `busy_timeout`, `synchronous=NORMAL`
- [x] Chạy một migration Flyway mẫu thành công trên SQLite
- [x] Thử cột tính tự động (generated column) cho xếp loại, kiểm tra các điểm 49, 50, 65, 66, 79, 80
- [x] Thử partial unique index với điều kiện `removed_at IS NULL`
- [x] Thử trigger `RAISE(ABORT)` chặn `DELETE` và `UPDATE`
- [x] Thử `VACUUM INTO` khi ứng dụng đang chạy, mở lại bản sao thành DB nguyên vẹn
- [x] Thử hai luồng cùng ghi, xác nhận `busy_timeout` hoạt động và UNIQUE chặn được ghi trùng
- [x] Ghi kết quả kiểm chứng vào `docs/` ([OPERATIONS-GUIDE.md](OPERATIONS-GUIDE.md) phụ lục A: phiên bản sqlite-jdbc, Flyway, tính năng nào dùng được)

### M0.3 Kiểm chứng bộ cài Windows (R12)

- [x] Dùng `jlink` tạo JRE gọn chỉ chứa module cần thiết
- [x] Đóng gói JAR kèm JRE bằng script `installer/windows/build.ps1` (luôn chạy `clean`, S13)
- [x] Chạy ứng dụng như Windows Service bằng WinSW: dịch vụ `TrainingStats` Running, tự khởi động, HTTP 200 qua `localhost` và IP của máy
- [x] Dữ liệu, log, cấu hình nằm ở `C:\ProgramData\TrainingStats\`, không nằm trong thư mục cài đặt
- [x] Bộ cài mở luật tường lửa cho cổng cấu hình (luật chỉ cho mạng Domain/Private; mạng đang là Public thì phải đổi loại mạng, `check.ps1` báo điều này)
- [x] Bộ cài hỏi cổng và mật khẩu Super Admin, ghi vào `first-run.properties` với quyền chỉ SYSTEM và Administrators (`Restrict-Acl` dựng ACL mới; khi nâng cấp áp lại quyền cho thư mục dữ liệu)
- [x] Script `installer/windows/setup/check.ps1` kiểm tra sau cài đặt (dịch vụ, tiến trình, cổng, HTTP, luật tường lửa so với loại mạng, quyền thư mục dữ liệu), dùng cho nghiệm thu máy sạch và sau khi khởi động lại
- [x] Cài thử trên máy Windows **sạch** (chưa có Java), truy cập được từ máy khác trong LAN (chủ dự án xác nhận)
- [x] Truy cập thử từ điện thoại (Android và iOS) qua Wi-Fi cùng mạng LAN, mở được trang chủ trống (chủ dự án xác nhận)
- [x] Gỡ cài đặt xong giữ nguyên dữ liệu, cài lại bản mới ghi đè chương trình mà không mất dữ liệu (chủ dự án xác nhận)

**Hoàn thành khi:** có một bộ cài chạy dịch vụ trên máy sạch, trả về trang chủ trống qua LAN, và mọi mục kiểm chứng ở M0.2 đã có kết luận.

---

## M1. Nền tảng

### M1.1 Schema và dữ liệu khởi tạo

- [x] `V1`: bảng `unit`, `rank`, `position`, `specialty`, `absence_reason`
- [x] `V1`: bảng `app_user` với `kind`, `category` (đối tượng), các ràng buộc CHECK và chỉ mục duy nhất theo mục 2.3
- [x] `V1`: bảng `user_specialty`
- [x] `V1`: bảng `exam_period`, `exam_class`, `class_teacher`
- [x] `V1`: bảng `class_participant` với `unit_id_snapshot`, `removed_*` và partial unique index
- [x] `V1`: bảng `result` (UNIQUE `participant_id`, `score_x10`, cột `grade` tự tính) và `result_history`
- [x] `V1`: bảng `class_request`, `notification`, `audit_log`, `app_setting`
- [x] `V1`: các chỉ mục theo mục 2.6
- [x] `V1`: trigger chặn `DELETE` cứng trên bảng nghiệp vụ
- [x] `V1`: trigger chặn `UPDATE` và `DELETE` trên `audit_log`
- [x] `V1` (dữ liệu): seed 20 cấp bậc (tên và ký hiệu), 26 chức vụ, 24 chuyên ngành theo danh sách ban đầu của chủ dự án
- [x] `V1` (dữ liệu): seed lý do không kiểm tra (Ốm, Công tác, Nghỉ phép, Khác)
- [x] `V1` (dữ liệu): seed cây đơn vị theo danh sách ban đầu của chủ dự án, mã Cấp 1 là `[xxxx]`, Cấp 2 là `[mã cha].[xxxx]`; hai đơn vị "Tàu 412", "Tàu 414" seed với tên "Hải đội 412", "Hải đội 414" (S8: mã là mã tạm, cần đơn vị xác nhận trước khi triển khai thật)
- [x] `V1`: không còn bảng `subject`, cột `exam_class.subject_id` (test `khongConBangMonKiemTra`); schema là một file gộp `V1__init.sql` (S13)
- [x] Test: số lượng và nội dung dữ liệu seed khớp danh sách ban đầu (`SeedDataTest`, danh sách chép thẳng trong test, không đọc file Excel)
- [x] Test: các ràng buộc CHECK chặn dữ liệu sai (user `UNIT` có tài khoản, `PERSON` thiếu mã định danh, Super Admin có đơn vị...)

### M1.2 Hạ tầng backend

- [x] Cấu hình `application.yml`: cổng, đường dẫn DB, thư mục log, múi giờ
- [x] Cấu hình kết nối SQLite và tách pool: 1 kết nối ghi, 4 kết nối đọc chỉ đọc; rà chỗ dùng `@Transactional` để không giữ khóa ghi lâu
- [x] Định dạng lỗi thống nhất `{code, message, fieldErrors[]}` và bộ xử lý lỗi toàn cục
- [x] Mã trạng thái theo quy ước: 404 khi ngoài phạm vi, 409 khi trùng hoặc sai `version`
- [x] `SqliteExceptionTranslator`: `SQLITE_CONSTRAINT_UNIQUE`/`_PRIMARYKEY` thành `DuplicateKeyException`, `_FOREIGNKEY`/`_CHECK`/`_NOTNULL` thành `DataIntegrityViolationException`, `SQLITE_BUSY` thành `CannotAcquireLockException`, kèm test
- [x] Mẫu phân trang `{items, page, size, total}`
- [x] Tiện ích thời gian: lưu UTC ISO-8601, hiển thị `Asia/Ho_Chi_Minh`
- [x] Tiện ích bỏ dấu tiếng Việt để sinh `search_text` (R6), kèm test
- [x] Header bảo mật: CSP `default-src 'self'`, `X-Content-Type-Options`, `X-Frame-Options`

### M1.3 Xác thực và phiên

- [x] Mặc định chặn mọi request chưa đăng nhập
- [x] Đăng nhập `POST /auth/login`, session phía server, BCrypt
- [x] Cookie phiên `HttpOnly`, `SameSite=Lax`, không đặt `Secure` (Q8b)
- [x] Đăng xuất `POST /auth/logout`
- [x] `GET /me` trả thông tin, vai trò, đơn vị, các lớp đang làm giáo viên
- [x] `PUT /me/password` đổi mật khẩu (nhập mật khẩu cũ)
- [x] CSRF: cấp token, kiểm tra header ở mọi request ghi
- [x] Phiên hết hạn sau thời gian không hoạt động (cấu hình được)
- [x] Chặn đăng nhập với user `INACTIVE`, `DELETED`, user `UNIT`, user không có tài khoản
- [x] Ghi audit log mọi lần đăng nhập thành công và thất bại, kèm IP
- [x] Test: đăng nhập đúng, sai, tài khoản bị khóa, thiếu CSRF

### M1.4 Phân quyền và phạm vi

- [x] `UserPrincipal` mang `userId`, `role`, `unitCode`, `unitLevel`
- [x] `ScopeService`: tính phạm vi theo vai trò (Super Admin, Admin, User) theo mục 5.1
- [x] Điều kiện SQL phạm vi `code = U OR code LIKE U || '.%'` dùng chung, kèm test không khớp nhầm `00021`
- [x] Kiểm tra vai trò ở mức route (`SUPER_ADMIN`, `ADMIN`, đã đăng nhập)
- [x] Kiểm tra giáo viên của lớp (`class_teacher`)
- [x] Quy tắc chặn tự thao tác: không tự khóa, tự xóa, tự hạ quyền chính mình
- [x] Quy tắc luôn còn ít nhất một Super Admin `ACTIVE`
- [x] Test tự động: quét mọi endpoint bằng tài khoản nhánh khác, mong đợi 404 hoặc 403 (R5, `EndpointAccessScanTest`)

### M1.5 Audit log

- [x] Dịch vụ ghi audit log (người, hành động, đối tượng, trước, sau, IP, mã đơn vị)
- [x] `GET /audit-logs` cho Super Admin, lọc theo người, hành động, thời gian, có phân trang
- [x] Test: audit log không sửa, không xóa được

### M1.6 Khởi tạo hệ thống

- [x] Lần chạy đầu tạo tài khoản Super Admin: đọc `config/first-run.properties` do bộ cài tạo, băm BCrypt, **xóa file ngay**, kiểm tra file đã bị xóa
- [x] Lệnh dòng lệnh `--reset-superadmin` chỉ chạy được cục bộ (tham số `-Dfile.encoding=UTF-8` trong script PowerShell đặt trong nháy)
- [x] Seed `app_setting` mặc định (độ dài mật khẩu tối thiểu, thời gian hết phiên, giờ sao lưu, số bản giữ = 5, thư mục sao lưu)

### M1.7 Khung frontend

- [x] `index.html`, `login.html`, `css/base.css`, `css/components.css`
- [x] `core/api.js`: gọi API, gắn CSRF, xử lý 401 (về trang đăng nhập) và hiển thị lỗi
- [x] `core/router.js`: router theo hash, kiểm tra quyền theo vai trò
- [x] `core/auth.js`, `core/store.js`
- [x] `ui/escape.js`: bắt buộc dùng khi chèn dữ liệu vào DOM
- [x] `ui/table.js` có phân trang, sắp xếp, trạng thái đang tải và rỗng
- [x] `ui/form.js` kiểm tra dữ liệu và hiển thị lỗi theo từng trường
- [x] `ui/modal.js`, `ui/multi-select.js` (chọn nhiều có ô đánh dấu), `ui/pagination.js`, `ui/toast.js`
- [x] Trang đăng nhập, khung điều hướng theo vai trò, trang đổi mật khẩu
- [x] Kiểm tra giao diện không gọi tài nguyên ngoài (CDN, font, ảnh) khi chạy không có Internet

### M1.8 Phong cách giao diện và responsive nền tảng (R21, R23)

- [x] `css/tokens.css`: biến màu, cỡ chữ, khoảng cách, bo góc, viền theo mục 4.1 của ARCHITECTURE.md
- [x] Đo độ tương phản từng cặp màu chữ và nền bằng công cụ, đạt mức AA ([OPERATIONS-GUIDE.md](OPERATIONS-GUIDE.md) phụ lục B); vàng kim chỉ dùng trên nền tối
- [x] Font Inter bản biến thiên (400 đến 700, giấy phép OFL) lưu ở `frontend/assets/fonts` (ba tập latin, latin-ext, vietnamese), cỡ chữ nền 15 px, chữ số bằng độ rộng trong bảng, font đơn cách cho mã; kịch bản `font-inter-hien-thi-tieng-viet` của `ui-check` kiểm font đã tải, đã áp dụng và không tải từ ngoài
- [x] `css/base.css`: reset, kiểu chữ tiêu đề, font đơn cách cho mã
- [x] Phong cách Hải quân kiểu dashboard (AdminKit, Hope UI, Admindek, ARCHITECTURE 4.1): thanh bên xanh sẫm chia nhóm và thu gọn được, thanh trên có đường dẫn trang, khung xem nhanh thông báo, menu tài khoản có ảnh đại diện, bo góc, đổ bóng nhẹ, chữ thường, chân trang; trang đăng nhập nền xanh có sóng và sọc cổ áo
- [x] Đầu trang gọn (không banner lớn), bảng dòng thấp (khoảng 52 px, vẫn đủ vùng chạm 44 px), gộp cột phụ; nút biểu tượng thao tác ngay trên dòng (`ui/row-actions.js`) ở người dùng, lớp, đợt, đơn vị, danh mục, người học, nhật ký; trang chi tiết gom thao tác ít dùng vào nút thả xuống (`ui/action-menu.js`); điện thoại ẩn ô trống
- [x] Liên kết quay lại trang cha ở góc trên bên trái mọi trang con (`ui/back-link.js`)
- [x] `css/layout.css`: bố cục theo ba điểm ngắt (dưới 600, từ 600, từ 1024) và giới hạn bề rộng từ 1440 px
- [x] Thẻ viewport ở mọi trang HTML, không khóa phóng to
- [x] `ui/nav.js`: thanh bên ở máy tính, thanh tab đáy và menu "Thêm" ở điện thoại, hiển thị theo vai trò
- [x] `ui/table.js`: bảng tự chuyển thành danh sách thẻ dưới 600 px, đánh dấu cột ưu tiên để ẩn bớt ở máy tính bảng
- [x] `ui/modal.js`: toàn màn hình trên điện thoại, cửa sổ giữa màn hình từ 1024 px
- [x] `ui/form.js`: một cột trên điện thoại, hai cột từ 600 px (lưới 4 cột từ 1024 px cho form người dùng), ô nhập 16 px trở lên, `inputmode` và `type` phù hợp
- [x] Vùng chạm tối thiểu 44×44 px cho nút và liên kết; không có chức năng chỉ dùng được khi hover
- [x] Biểu tượng SVG tự vẽ (sao, khiên, chevron) trong `assets/`, không dùng quốc huy hay quân hiệu thật
- [x] Tôn trọng `prefers-reduced-motion`
- [x] Bộ kiểm tra giao diện `tools/ui-check.ps1` (Edge headless) kiểm mọi trang ở 360, 390, 768, 1024, 1366, 1920 px: không cuộn ngang, không hộp thoại chồng nhau, kích thước nội dung, vùng chạm; ghi cách chạy và bảng đo vào [OPERATIONS-GUIDE.md](OPERATIONS-GUIDE.md) mục 11.3

**Hoàn thành khi:** đăng nhập được bằng Super Admin vừa khởi tạo, xem được audit log, `ScopeService` có test phủ các trường hợp biên, schema cùng dữ liệu seed khớp danh sách ban đầu, và trang mẫu hiển thị đúng ở mọi độ rộng trong bộ kiểm tra với phong cách đã chốt.

---

## M2. Quản lý user và đơn vị

### M2.1 Đơn vị và danh mục

- [x] `GET /units/tree` trả toàn bộ cây (mã, tên) cho Admin trở lên
- [x] `POST`, `PUT`, `PATCH status` đơn vị (Super Admin), không cho đổi `code` và `parent_id`
- [x] Kiểm tra mã Cấp 2 bắt đầu bằng mã cha cộng dấu chấm
- [x] `GET /catalogs` trả cấp bậc, chức vụ, chuyên ngành, lý do vắng
- [x] CRUD danh mục cho Super Admin, dùng `active` thay vì xóa
- [x] Trang quản lý đơn vị (cây) và trang danh mục

### M2.2 Quản lý user (backend)

- [x] `GET /users` có tìm kiếm theo `search_text`, lọc nhiều giá trị ở mọi tiêu chí (đơn vị, đối tượng, cấp bậc, chức vụ, chuyên ngành, trạng thái, quyền, `kind`); mặc định chỉ `PERSON`; luôn trong phạm vi (test `locNhieuGiaTriMoiTieuChiVaGiuPhamVi`)
- [x] `GET /users/{id}` (trong phạm vi hoặc chính mình)
- [x] `POST /users`: bắt buộc họ đệm, tên, đơn vị trong phạm vi, đối tượng; sinh `search_text` (test `doiTuongBatBuocKhiTaoSuaVaLocDuoc`)
- [x] Mã định danh để trống thì hệ thống tự cấp `ND000001`..., không trùng; vẫn cho nhập, sửa số hiệu chính thức (A12)
- [x] Kiểm tra: tài khoản bắt buộc khi quyền là `ADMIN`, mã định danh và tài khoản không trùng; tài khoản mới nhận mật khẩu mặc định (A13)
- [x] `PUT /users/{id}` có khóa lạc quan `version`
- [x] `PUT /users/{id}/account`: cấp, đổi tên, thu hồi tài khoản (A13)
- [x] `PATCH /users/{id}/status` (`ACTIVE`, `INACTIVE`, `DELETED`), không tự đổi của mình
- [x] `PUT /users/{id}/role`: Admin cấp `ADMIN` trong nhánh, chỉ Super Admin cấp `SUPER_ADMIN` (Q2)
- [x] `POST /users/{id}/reset-password` (Admin trong phạm vi, trừ tài khoản Super Admin)
- [x] `POST /users/{id}/move`: chuyển sang bất kỳ đơn vị; nếu người chuyển không phải Super Admin và đích ngoài phạm vi thì tự hạ `ADMIN` về `USER` và ghi audit log (R1)
- [x] `GET /users/{id}/history` lịch sử điểm của một người (hoàn thiện ở M4.4)
- [x] `GET /users/{id}/print` dữ liệu in thông tin cá nhân với tên cấp bậc đầy đủ
- [x] Ghi audit log cho: đổi quyền, đổi đơn vị, đổi trạng thái, reset mật khẩu, đổi tài khoản

### M2.3 Quản lý user (giao diện)

- [x] Trang danh sách user: tìm kiếm, bộ lọc chọn nhiều ở mọi tiêu chí xếp hai hàng từ 1024 px, phân trang, cấp bậc bằng ký hiệu, đối tượng dưới cấp bậc
- [x] Form tạo, sửa user: chọn cấp bậc, chức vụ, đối tượng từ danh sách, chuyên ngành dạng ô đánh dấu (6 cột), chọn đơn vị bằng radio có tìm nhanh (`ui/radio-select.js`); vừa một màn từ 1024 px
- [x] Hộp thoại chuyển đơn vị kèm cảnh báo về việc mất quyền và hạ `ADMIN`
- [x] Khóa, mở khóa, xóa mềm, reset mật khẩu, đổi quyền; nút "Thao tác khác" trên dòng mở hộp thoại dùng chung `pages/users/actions.js` với trang chi tiết
- [x] Trang chi tiết user; cấp, đổi, thu hồi tài khoản ở trang chi tiết (form sửa không có ô tài khoản)
- [x] Trang in thông tin cá nhân với tên cấp bậc đầy đủ (M6.3)
- [x] Kiểm tra danh sách, form, chi tiết user ở 360, 768, 1280 px (bảng thành thẻ trên điện thoại, bộ lọc thu gọn được)

### M2.4 Kiểm thử phân quyền user

- [x] Test: admin nhánh A không thấy, không sửa user nhánh B hoặc cấp cao hơn (API trả 404)
- [x] Test: admin sửa được user trong nhánh mình và nhánh con
- [x] Test: sau khi chuyển user sang nhánh khác, admin cũ mất quyền ngay
- [x] Test (R1): Admin Cấp 2 chuyển một `ADMIN` sang đơn vị Cấp 1 thì người đó bị hạ về `USER`
- [x] Test: không tự khóa, tự xóa, tự hạ quyền chính mình; luôn còn một Super Admin
- [x] Test: Admin không cấp được `SUPER_ADMIN`, không reset được mật khẩu Super Admin
- [x] Test: xóa mềm không làm mất bản ghi, không hiện trong danh sách mặc định

**Hoàn thành khi:** Super Admin và admin các cấp tạo, sửa, chuyển, khóa user đúng phạm vi, và toàn bộ test ở M2.4 đạt.

---

## M3. Đợt kiểm tra, nội dung, người học, đề nghị, thông báo, phân công

### M3.1 Đợt và nội dung kiểm tra

- [x] CRUD đợt kiểm tra (Super Admin), có đóng, mở
- [x] Khóa đợt thì mọi nội dung của đợt đóng theo (trạng thái hiệu lực, không ghi đè; C1, PRD 35); không mở lại riêng nội dung khi đợt còn khóa (409 `PERIOD_CLOSED`); giao diện ẩn nút mở lại, ghi "Đợt đã khóa", xác nhận khi đóng, mở đợt (test `khoaDotThiMoiNoiDungCuaDotDeuDong`)
- [x] `POST`, `PUT` nội dung: đợt, tên, ngày kiểm tra, địa điểm, ghi chú, kèm `teacherIds` lưu cùng giao dịch (C15, test `themSuaNoiDungKemGiamKhaoTrongMotGiaoDich`)
- [x] `PATCH /classes/{id}/status` đóng, mở nội dung; nội dung đóng không nhận thêm người và đề nghị mới
- [x] Xóa mềm nội dung (C2)
- [x] `PUT /classes/{id}/teachers`: chỉ gán user có tài khoản và `ACTIVE`; mặc định Super Admin
- [x] `GET /classes` tự lọc theo vai trò: Super Admin và Admin tất cả (C4, PRD 33), giám khảo nội dung mình, user nội dung mình tham gia; trả `participantCount`, `gradedCount`, `pendingCount` và nhận `ungraded=true` (PRD 34)
- [x] `GET /classes/open` cho Admin chọn nội dung khi đề nghị
- [x] `GET /classes/{id}` kiểm tra quyền liên quan đến nội dung

### M3.2 Người học trong nội dung

- [x] `GET /classes/{id}/participants`: Admin chỉ thấy người học trong nhánh mình, giám khảo và Super Admin thấy cả nội dung
- [x] Sinh `unit_id_snapshot` khi thêm người vào nội dung
- [x] Tự tạo user `UNIT` khi lần đầu đưa một đơn vị vào nội dung (R19)
- [x] `GET /classes/{id}/candidates?q=`: tìm trên toàn Lữ đoàn, chỉ trả tên, cấp bậc, đơn vị, mã; tối thiểu 2 ký tự, tối đa khoảng 20 kết quả; chỉ giám khảo của nội dung còn mở và Super Admin (R20)
- [x] `POST /classes/{id}/participants`: thêm trực tiếp bởi Super Admin hoặc giám khảo, ghi audit log (Q3)
- [x] Chặn thêm trùng người trong cùng nội dung (partial unique index), cho phép thêm lại sau khi đã gỡ
- [x] `POST .../remove`: giám khảo và Super Admin gỡ người chưa có điểm
- [x] Gỡ người đã có điểm: chỉ Super Admin, bắt buộc `removed_reason`, điểm giữ lại (Q9)
- [x] `PUT .../absence` ghi hoặc xóa lý do không kiểm tra (chọn từ danh sách và ghi chú)
- [x] Quy tắc: không thể vừa có điểm vừa có lý do vắng

### M3.3 Đề nghị

- [x] `POST /classes/{id}/requests`: Admin đề nghị thêm hoặc xóa, đối tượng phải trong phạm vi của Admin
- [x] Chặn hai đề nghị `PENDING` giống hệt nhau
- [x] `GET /requests`: Admin xem của mình, giám khảo xem của nội dung mình, Super Admin xem tất cả, lọc theo trạng thái
- [x] `POST /requests/{id}/approve`: tạo người học hoặc gỡ mềm, ghi audit log
- [x] `POST /requests/{id}/reject` kèm lý do
- [x] `POST /requests/{id}/cancel` bởi chính người đề nghị khi còn `PENDING`

### M3.4 Thông báo

- [x] Tạo thông báo cho giám khảo và Super Admin khi có đề nghị mới; cho người đề nghị khi đề nghị được duyệt, từ chối
- [x] `GET /notifications`, `GET /notifications/unread-count`
- [x] `POST /notifications/{id}/read`, `POST /notifications/read-all`
- [x] Giao diện: chuông và số đếm trên thanh trên, gọi `unread-count` mỗi 30 giây, chỉ khi tab đang hiển thị và người dùng có thao tác (C13, R26)
- [x] Giao diện: danh sách thông báo; bấm thông báo đề nghị mới mở danh sách đề nghị đang chờ, đánh dấu và cuộn tới nhóm nội dung liên quan (C12)

### M3.5 Giao diện nội dung kiểm tra

- [x] Trang danh sách đợt, nội dung (theo vai trò); cột "Tiến độ chấm" và bộ lọc "Chưa chấm xong", dòng còn người chưa chấm được tô nổi bật
- [x] Thêm, sửa đợt và nội dung bằng hộp thoại; nội dung gán giám khảo ngay trong hộp thoại (gợi ý theo tên, mã định danh, tài khoản)
- [x] Trang chi tiết nội dung: danh sách người học, lý do vắng, trạng thái điểm; tìm nhanh và lọc nhiều lựa chọn theo đơn vị, tình trạng, xếp loại, đối tượng; bộ lọc "đã gỡ" cho Super Admin
- [x] Hộp thoại tìm ứng viên và thêm người, thêm đơn vị vào nội dung
- [x] Màn hình tạo đề nghị cho Admin (chọn nhiều đơn vị, mỗi đơn vị một đề nghị)
- [x] Trang Đề nghị gom theo nội dung kiểm tra: chọn tất cả theo nội dung, duyệt hoặc từ chối hàng loạt, người tạo hủy trên dòng
- [x] Trên điện thoại: duyệt hoặc từ chối đề nghị, thêm người từ kết quả tìm ứng viên, xem chi tiết nội dung đều làm được bằng cảm ứng (kịch bản `ui-check` ở 360 px)
- [x] Kiểm tra các trang nội dung và đề nghị ở 360, 768, 1280 px

### M3.6 Kiểm thử

- [x] Test: admin chỉ thấy người học trong nhánh mình ở nội dung có nhiều đơn vị
- [x] Test: giám khảo không thao tác được nội dung không phải của mình
- [x] Test: giám khảo chỉ nhận 4 trường khi tìm ứng viên, không nhận ngày sinh hay tài khoản
- [x] Test: user `UNIT` không đăng nhập, không làm giám khảo, không làm admin
- [x] Test: luồng đề nghị đầy đủ (tạo, duyệt, từ chối, hủy) kèm thông báo
- [x] Test: nội dung đóng từ chối thêm người và đề nghị

### M3.7 Bảng phân công (PRD 32, 37; ARCHITECTURE 3.12)

- [x] `GET /assignments/matrix`, `GET /assignments/units`, `POST /assignments/apply` (gói `assignment`); phạm vi danh sách: Super Admin và giám khảo toàn bộ, Admin theo nhánh; cột có chế độ `DIRECT`, `REQUEST`, `VIEW`
- [x] Xem từng đơn vị một (`unit=`), bộ lọc nhiều đơn vị, đối tượng, chuyên ngành, tìm tên hoặc mã (không dấu), cả nội dung đã đóng; phân trang tối đa 200 người
- [x] `apply` đi qua `ParticipantService`, `RequestService`; thay đổi lỗi báo riêng; Admin gửi đề nghị kèm một thông báo gộp mỗi nội dung (P5, P6)
- [x] Test `AssignmentApiTest`: phạm vi theo vai trò, danh sách trường lộ ra, bộ lọc, phân trang, thêm gỡ trực tiếp, xem từng đơn vị và lọc đối tượng, người đã có điểm, nội dung đã đóng, đề nghị gộp thông báo, ô chờ duyệt, giới hạn thay đổi; `EndpointAccessScanTest` khai báo các endpoint ở `USER_ALLOWED` (P7)
- [x] Trang `#/assign` (menu "Phân công"): chọn ô, chọn cả từng đơn vị, thanh Áp dụng hoặc Gửi đề nghị, hộp thoại xác nhận và báo lỗi từng thay đổi; vừa khít màn hình, chia nhóm cột khi thiếu chỗ; Trước, Sau ngay dưới bảng; khối thông báo khi lọc không có kết quả (ARCHITECTURE 4.1)
- [x] Phân công bằng Excel (`#/assign/import`, `GET /assignments/template`, `POST /assignments/import`, E10); test `AssignmentExcelApiTest`

**Hoàn thành khi:** Super Admin tạo đợt và nội dung, giám khảo và Admin lập được danh sách qua thêm trực tiếp, bảng phân công và đề nghị, thông báo hiện đúng người.

---

## M4. Chấm điểm và hồ sơ cá nhân

### M4.1 Nhập điểm

- [x] `POST .../participants/{pid}/result`: điểm 0.0–10.0 một chữ số thập phân, lưu `score_x10` (D1)
- [x] Trả 409 nếu người học đã có điểm (UNIQUE ở DB bảo đảm, kể cả khi hai giám khảo lưu cùng lúc)
- [x] Chỉ Super Admin và giám khảo của nội dung đó được nhập
- [x] Chặn giám khảo chấm điểm cho chính mình (R16)
- [x] Khi nhập điểm thì xóa lý do vắng cùng giao dịch
- [x] Lưu người nhập và thời điểm nhập
- [x] Test xếp loại biên: 4.9, 5.0, 6.5, 6.6, 7.9, 8.0, cộng 0, 10, 6.50 (R15)
- [x] Test: nhập trùng đồng thời chỉ một bên thành công (5 vòng, mỗi vòng đúng một bên)

### M4.2 Sửa điểm

- [x] `PUT /results/{rid}` chỉ Super Admin, bắt buộc `reason` và `version`
- [x] Ghi `result_history` và audit log (giá trị cũ, mới, lý do)
- [x] `GET /results/{rid}/history`
- [x] Test: Admin và giám khảo bị từ chối khi sửa điểm; sai `version` thì 409

### M4.3 Giao diện chấm điểm

- [x] Trang Chấm điểm (`#/classes/:id/grade`): khối công cụ (tìm, lọc nhiều đơn vị, "Chưa có điểm / Tất cả"), thanh tiến độ, danh sách gom theo đơn vị lúc tham gia, mỗi người một hàng thấp, nhập từng người, không bắt buộc nhập hết
- [x] Xếp loại xem trước ngay khi nhập (D6), nhận cả dấu phẩy
- [x] Một nút "Lưu điểm" chung có bước xác nhận dạng bảng (tên, đơn vị, điểm, xếp loại); lưu từng người, điểm lỗi giữ lại trong ô và được liệt kê
- [x] Hiển thị rõ người đã có điểm (khóa ô nhập) và điểm máy chủ đã ghi sau khi lưu
- [x] Hộp thoại sửa điểm cho Super Admin kèm ô lý do và xem lịch sử sửa
- [x] Điện thoại: bàn phím số (`inputmode="decimal"`), ô nhập cuộn vào vùng nhìn thấy khi bàn phím mở (R22)

### M4.4 Hồ sơ cá nhân

- [x] `GET /me/classes`: các nội dung mình tham gia và điểm
- [x] `GET /users/{id}/history`: lịch sử điểm qua các đợt (loại người đã bị gỡ khỏi nội dung)
- [x] Trang Hồ sơ (`#/me`) và thẻ "Nội dung kiểm tra đã tham gia và điểm" ở trang người dùng: gom theo đợt, tìm theo tên, lọc theo khoảng ngày, đợt, tình trạng, xếp loại; các bảng đợt có cột thẳng hàng nhau, mỗi ô một hàng (ARCHITECTURE 4.1)
- [x] Test: user chỉ xem được dữ liệu của mình, không xem được điểm của đơn vị mình (Q5)

**Hoàn thành khi:** mỗi người học chỉ có một điểm, xếp loại đúng ở mọi điểm biên, mọi lần sửa điểm đều có lịch sử và audit log.

---

## M5. Nhập, xuất Excel

### M5.1 Hạ tầng chung

- [x] Giới hạn file (5 MB, 5.000 dòng) và kiểm tra định dạng `.xlsx` (R7)
- [x] Cơ chế `dryRun`: kiểm tra và trả lỗi theo từng dòng mà không ghi
- [x] Cơ chế ghi: tất cả trong một giao dịch, có lỗi thì không ghi gì
- [x] Báo lỗi theo dòng và cột, tải được file lỗi (CSV, tạo ngay trong trình duyệt)
- [x] Ghi audit log mỗi lần nhập
- [x] Giao diện dùng chung `ui/importer.js`: thẻ 3 bước (tải mẫu, chọn file, kiểm tra rồi ghi), bảng lỗi, xem trước, xác nhận ghi
- [x] File mẫu chặn nhập sai và trình bày như văn bản của đơn vị (E9): ràng buộc Stop, ô chọn lấy từ DB, bố cục tiêu đề, in A4 ngang; sheet `HuongDan` đứng đầu và mở ra trước; không bật hộp nhắc khi chọn ô (test kiểm trong `ExcelImportApiTest`; đã kiểm bằng Excel 2016)
- [x] Dùng chung hạ tầng `common/excel` (`ExcelBook`, `ImportReport`, `ExcelTemplate`, `RowKeys`, `ContentRows`, `ImportRunner`)

### M5.2 Nhập user

- [x] File mẫu `GET /users/import-template` (cột mã định danh định dạng Text, cột cấp bậc dùng tên, không dùng ký hiệu) (R7)
- [x] Khớp người theo mã định danh, cập nhật nếu đã có, tạo mới nếu chưa có (dòng để trống mã thì tạo mới và tự cấp mã, A12); ô trống ở cột không bắt buộc giữ giá trị cũ
- [x] Mỗi dòng phải có đơn vị nằm trong phạm vi của người nhập
- [x] Hiển thị họ tên của người được khớp ở bước `dryRun` để đối chiếu (R8)
- [x] Kiểm tra giá trị cấp bậc, chức vụ, chuyên ngành (3 cột), đơn vị, đối tượng có trong danh mục; đối tượng bắt buộc với người mới (test `doiTuongBatBuocVoiNguoiMoiVaNhanTenDayDu`)
- [x] Cấp tài khoản và quyền (Người dùng, Admin) qua Excel, tài khoản mới có mật khẩu mặc định; Admin thiếu tài khoản, tài khoản trùng, quyền sai báo lỗi ngay ở bước kiểm tra (E4, test `capTaiKhoanVaQuyenBangExcel`)
- [x] Test với tập khoảng 1.000 dòng: dòng đúng ghi hết; dòng sai, trùng, ngoài phạm vi báo đúng dòng, không lộ họ tên người ngoài phạm vi, không ghi gì

### M5.3 Nhập nội dung kiểm tra

- [x] File mẫu và nhập nhiều nội dung kèm người học theo mã định danh (Super Admin; sheet `NoiDung`, `NguoiHoc`, đợt chọn theo từng nội dung, E5)
- [x] Test lỗi: đợt không có hoặc đã đóng, người không tồn tại, trùng người trong nội dung, tên nội dung trùng; chỉ Super Admin

### M5.4 Nhập điểm và xuất điểm một nội dung

- [x] File mẫu theo nội dung là danh sách đầy đủ của nội dung (người đã có điểm hiện sẵn và khóa ô; E6)
- [x] Dòng nào người học đã có điểm thì không ghi đè; ô trống bỏ qua
- [x] Giám khảo chỉ nhập được cho nội dung của mình, không chấm cho chính mình
- [x] Test: điểm sai (ngoài 0–10, quá một chữ số thập phân), người không thuộc nội dung, người không quản lý nhận 404
- [x] Xuất điểm một nội dung ra Excel (`GET /classes/{id}/results/export`, E11), cùng bố cục với file mẫu, theo phạm vi người xem; nút "Xuất điểm Excel" ở trang chi tiết và trang chấm điểm (test `xuatDiemMotNoiDungTheoPhamViNguoiXem`)

### M5.5 Chấm điểm cả đợt bằng Excel (PRD 41)

- [x] `GET /periods/{id}/results/import-template`, `POST /periods/{id}/results/import`, chỉ Super Admin; trang `#/periods/import-results` mở từ dòng của đợt (E12)
- [x] File phân biệt ba loại ô: được nhập (nền vàng), đã có điểm (nền xám, khóa), không tham gia (gạch chéo, khóa), có dòng chú thích trên tiêu đề cột; sọc bảng không phủ cột điểm. Test `PeriodResultImportApiTest` (kiểm điểm, kiểu tô, sọc, chú thích); đã mở bằng Excel 2016 và xuất PDF để xem

**Hoàn thành khi:** nhập 1.000 user bằng Excel đúng, báo lỗi theo dòng, không ghi nửa chừng.


---

## M6. Thống kê, dashboard và in ấn

### M6.1 API thống kê

- [x] `GET /stats/grade-by-unit`: số lượng và tỷ lệ xếp loại theo đơn vị
- [x] `GET /stats/class/{id}`: thống kê điểm của một nội dung (kèm phân bố điểm)
- [x] `GET /stats/unit-scores`: trung bình, cao nhất, thấp nhất theo đơn vị (giao diện không dùng)
- [x] `GET /stats/not-tested`: chưa kiểm tra và không kiểm tra kèm lý do
- [x] `GET /stats/overview` cho dashboard, tham số `group=unit|top`
- [x] Bộ lọc chung: đợt, nội dung, đơn vị (nhiều giá trị), khoảng thời gian, `kind` (mặc định `PERSON`)
- [x] Thống kê theo `unit_id_snapshot`, bỏ qua người đã bị gỡ khỏi nội dung (Q4, Q9)
- [x] Tự giới hạn theo phạm vi; giám khảo chỉ xem thống kê nội dung mình
- [x] Test: chuyển đơn vị một người thì thống kê cũ của đơn vị trước không đổi (R2)
- [x] Test: điểm đơn vị và điểm cá nhân không bị trộn (R9)
- [x] Test hiệu năng với khoảng 2.000 user và nhiều nội dung (S6)
- [x] `StatsApiTest`: R2, R9, xếp loại ở điểm biên, phân bố điểm, lọc ngày và nội dung, lọc nhiều đơn vị, phạm vi và 404, thống kê nội dung cho giám khảo và Admin, chưa kiểm tra kèm lý do

### M6.2 Giao diện thống kê và dashboard

- [x] Thư viện biểu đồ SVG tự viết: cột, cột chồng, vòng (`ui/charts.js`; thanh ngang chồng là HTML/CSS để nhãn dài xuống dòng)
- [x] Trang thống kê xếp loại theo đơn vị (biểu đồ và bảng, đã có điểm trung bình), trang danh sách chưa kiểm tra và không kiểm tra, trang thống kê một nội dung
- [x] Thanh bộ lọc dùng chung (đợt, nội dung của đợt, đơn vị chọn nhiều có tìm nhanh, thời gian)
- [x] Hiển thị điểm cá nhân và điểm đơn vị ở hai khu vực riêng (nút chọn loại điểm, có nhãn đang xem loại nào)
- [x] Biểu đồ co giãn theo `viewBox`, nhãn dài rút gọn trên điện thoại, luôn có bảng số liệu kèm theo
- [x] Xếp loại có chữ kèm màu, không chỉ phân biệt bằng màu
- [x] Dashboard Super Admin và Admin có 6 thẻ biểu đồ (hai vòng cá nhân và tập thể, xếp loại theo đơn vị, điểm trung bình theo đợt, xếp loại theo đợt, tỷ lệ đạt theo nội dung), lưới 6 + 6 thẳng cột, hai thẻ danh sách cuối trang cao bằng nhau; dùng chung `chartCard`, `statCard` với trang Thống kê
- [x] Trang chủ cho mọi vai trò: thẻ số liệu, nội dung gần đây, thông báo mới, dòng nhắc "N nội dung kiểm tra chưa chấm xong"
- [x] Kiểm tra các trang thống kê ở 360, 768, 1280 px (`ui-check` chạy 360, 390, 768, 1024, 1366, 1920 px), kèm kịch bản đối chiếu số trên giao diện với dữ liệu từ API khác

### M6.3 In ấn

- [x] `css/print.css`: khổ A4 (dọc; bảng nhiều cột A4 ngang bằng trang tên `wide`), ẩn thanh điều hướng, ngắt trang hợp lý (hàng tiêu đề bảng lặp lại, không cắt dòng), chân trang "Trang x / y"
- [x] Trang in cùng bố cục file Excel (`ui/print-sheet.js`): thông tin cá nhân (kèm kết quả kiểm tra qua các đợt) và kết quả một nội dung; cấp bậc bằng ký hiệu trong báo cáo danh sách, bằng tên đầy đủ khi in thông tin cá nhân. Trang thống kê không có bản in
- [x] In theo bộ lọc (PRD 42): nút In ở trang chi tiết nội dung, chi tiết người dùng, Hồ sơ mang bộ lọc sang trang in; chỉ in phần khớp, số liệu tính trên phần in, dòng "Bộ lọc", nút "In tất cả (bỏ lọc)"; logic lọc dùng chung với trang nguồn (`participant-filter.js`, `historyMatches`)
- [x] `print.css` ghi đè về đen trắng, bỏ màu nền và thanh điều hướng (ui-check `ban-in-den-trang` giả lập chế độ in)
- [x] Thử in và lưu PDF từ trình duyệt, tiếng Việt hiển thị đúng (ui-check lưu `print-user.pdf`, `print-class-results.pdf` bằng Chrome, đã mở xem)

**Hoàn thành khi:** các số liệu thống kê khớp với dữ liệu gốc qua kiểm tra chéo, in ra đúng khổ A4.

---

## M7. Sao lưu, nâng cấp, hoàn thiện

### M7.1 Sao lưu

- [x] Tác vụ định kỳ tạo bản sao lưu bằng `VACUUM INTO` hằng ngày theo giờ cấu hình (`admin/BackupScheduler`, sao lưu bù trong ngày khi máy tắt đúng giờ)
- [x] Chỉ giữ số bản tự động gần nhất theo cấu hình (mặc định 5, đổi được 1 đến 30); bản thủ công, trước nâng cấp không bị xóa
- [x] Bản sao lưu trước nâng cấp được lưu riêng, không bị vòng xoay xóa (`backups/upgrade/`)
- [x] `POST /admin/backup` tạo bản thủ công; `GET /admin/backups` danh sách; `GET /admin/backups/{name}` tải
- [x] Thư mục sao lưu cấu hình được (ổ khác hoặc ổ mạng), kiểm ghi được ngay khi lưu; trang Hệ thống cảnh báo khi cùng ổ đĩa với dữ liệu
- [x] Giao diện cho Super Admin: xem danh sách, tạo, tải, cài đặt (trang Hệ thống `#/admin/system`)
- [x] Tài liệu hướng dẫn khôi phục thủ công (dừng dịch vụ, thay file DB, khởi động lại): [OPERATIONS-GUIDE.md](OPERATIONS-GUIDE.md) mục 9
- [x] Test: bản sao lưu mở lại thành DB nguyên vẹn, vòng xoay chỉ giữ số bản cấu hình (`BackupApiTest`)

### M7.2 Nâng cấp

- [x] Khi khởi động: sao lưu DB rồi chạy Flyway (khi DB đã có dữ liệu và có migration mới; `admin/UpgradeBackup`)
- [x] Migration lỗi thì không khởi động và giữ nguyên bản sao lưu (`UpgradeBackupTest`)
- [x] Bản cài trước lần đổi chú thích `V1__init.sql` tự cập nhật checksum khi khởi động bản mới; chênh lệch checksum khác vẫn bị chặn (`UpgradeBackup.alignV1Checksum`, `UpgradeBackupTest`)
- [x] Bộ cài nâng cấp ghi đè chương trình, không chạm dữ liệu (`installer/windows/test-upgrade.ps1`, chạy được không cần Administrator; `-SkipService` không dừng dịch vụ thật đang chạy)

### M7.3 Quản trị hệ thống

- [x] `GET /admin/health`: phiên bản ứng dụng, phiên bản schema, dung lượng DB, ổ đĩa, sao lưu, cảnh báo
- [x] Trang quản trị cho Super Admin: nhật ký thao tác, sao lưu, tình trạng hệ thống (trang Hệ thống, liên kết sang Nhật ký)
- [x] Xoay vòng file log (10 MB mỗi file, 30 ngày, tối đa 300 MB, nén `.gz`)
- [x] Dịch vụ chạy bằng tài khoản ít quyền LocalService (PRD 40)

### M7.4 Kiểm thử tổng thể

- [x] Kiểm thử tải: 2.000 user, 50 người truy cập cùng lúc, màn hình chính dưới 2 giây (`LoadTest`, chạy với `-Dload=true`: 10.000 lượt tham gia, 50 người ảo qua HTTP thật mở trang chủ 3 lần; p50 493 ms, p95 866 ms, chậm nhất 991 ms, 0 lỗi, trên máy phát triển)
- [x] Kiểm thử nhập điểm đồng thời từ nhiều giám khảo (`LoadTest.chamDiemDongThoiNhieuGiamKhao`: 6 giám khảo cùng chấm 60 người, mỗi người đúng một điểm, còn lại 409)
- [x] Rà soát XSS: không có `innerHTML`, `outerHTML`, `insertAdjacentHTML`, `document.write`, `eval` trong frontend
- [x] Rà soát: mọi truy vấn trong `user/`, `participant/`, `result/`, `stats/` đều nhận `Scope`; lấy theo id kèm phạm vi trong SQL (ngoại lệ có lý do ghi ở ARCHITECTURE 4.2)
- [x] Rà soát: không có câu `DELETE` nào ngoài trigger chặn (chỉ `class_teacher`, `user_specialty` theo S4)
- [x] Chạy ứng dụng khi không có Internet, không có yêu cầu mạng ra ngoài nào (ui-check báo mọi request ra ngoài ở mọi trang)
- [x] Kiểm tra mọi trang ở 360, 390, 768, 1024, 1366, 1920 px: không cuộn ngang trang, không chữ bị cắt, mọi nút bấm được (ui-check, mọi trang trong `$pages`)
- [x] Rà soát chất lượng mã bằng công cụ: PMD, CPD (chạy từ dòng lệnh, không thêm vào `pom.xml`) và công cụ kiểm JS, CSS. Backend: hết đoạn trùng lặp, hết hàm quá phức tạp; hạ tầng dùng chung `ImportRunner`, `PersonColumns`, `RowKeys`, `ContentRows`, `SqlCondition`, record tham số `MatrixQuery`, `SearchQuery`, `ProfileInput`. Frontend: thành phần dùng chung (`pageHead`, `filterField`, `dlRow`, `stateCell`, `failureReport`, `periodImporter`, `labelsOf`...), không import thừa, không hàm hay `export` không dùng. CSS: không token, lớp không dùng; giá trị px cứng nằm trong `tokens.css`

### M7.5 Tài liệu và bàn giao

- [x] Hướng dẫn cài đặt, nâng cấp, sao lưu, khôi phục, đặt lại mật khẩu Super Admin, build từ mã nguồn, chạy, test, lỗi thường gặp: [OPERATIONS-GUIDE.md](OPERATIONS-GUIDE.md) (gộp từ các hướng dẫn chạy thử, kiểm tra giao diện, kiểm chứng M0, khôi phục)
- [x] Hướng dẫn sử dụng cho Super Admin, Admin, giám khảo, user: [USER-GUIDE.md](USER-GUIDE.md) (viết theo nhãn menu, nút thật của giao diện)
- [x] Đối chiếu từng mục "Tiêu chí hoàn thành" trong [PRD.md](PRD.md) và ghi kết quả (mục "Nghiệm thu theo PRD" bên dưới)
- [x] PRD.md, ARCHITECTURE.md, PLAN.md viết lại thành bản đặc tả đúng hiện trạng, đồng bộ với nhau và với mã

**Hoàn thành khi:** tất cả tiêu chí hoàn thành trong PRD.md đạt trên bộ cài thật, chạy trên máy Windows sạch.

---

## Nghiệm thu theo PRD

Mỗi dòng ứng với một tiêu chí trong mục "Tiêu chí hoàn thành" của PRD.md đã được kiểm bằng test, `ui-check` chạy thật hoặc chủ dự án xác nhận.

Căn cứ chung: backend 187 test đạt (1 bỏ qua là `LoadTest`, chạy riêng đạt); `ui-check` 382/382 trên DB mới, máy chủ thử cổng riêng; `installer/windows/test-upgrade.ps1` đạt.

- [x] Cài trên máy Windows sạch, không cần Java, truy cập được qua LAN, chạy không cần Internet — chủ dự án xác nhận; `test-upgrade.ps1` đạt; không có request ra ngoài ở mọi trang (ui-check)
- [x] Phân quyền theo nhánh đúng, kể cả khi gọi API trực tiếp; chuyển đơn vị thay đổi quyền đúng — `UserApiTest.adminNhanhANhanh404VoiNguoiNhanhKhacVaCapTren`, `chuyenSangNhanhKhacThiAdminCuMatQuyenNgay`, `r1AdminCap2ChuyenMotAdminSangDonViCap1ThiNguoiDoBiHaVeUser`; `ClassApiTest.adminChiThayNguoiHocTrongNhanhMinhOLopNhieuDonVi`, `chuyenDonViThiAdminCuMatQuyenXemChiTietNguoiHoc`; `StatsApiTest.gioiHanTheoPhamViVaDonViNgoaiPhamVi404`; `ScopeAndGuardsTest`; `EndpointAccessScanTest` (mọi endpoint: chưa đăng nhập 401, user thường ngoài danh sách cho phép bị chặn, Admin không vào được endpoint của Super Admin)
- [x] Mỗi người chỉ có một điểm, mọi lần sửa điểm đều có log — `ResultApiTest.daCoDiemThi409VaNhapDiemXoaLyDoVang`, `nhapTrungDongThoiChiMotBenThanhCong`, `suaDiemChiSuperAdminCoLyDoVaLichSu` (ai, lúc nào, điểm cũ → mới, lý do); `SchemaConstraintsTest.moiNguoiHocChiMotDiem`, `lichSuSuaDiemBatBuocLyDo`; `LoadTest.chamDiemDongThoiNhieuGiamKhao`
- [x] Xếp loại đúng ở các điểm biên — `ResultApiTest.xepLoaiDungOCacDiemBien` (4.9, 5.0, 6.5, 6.6, 7.9, 8.0, 0, 10), `SchemaConstraintsTest.diemNgoaiKhoangVaXepLoaiOCacDiemBien`, `StatsApiTest.demXepLoaiTheoCotDbOCacDiemBienVaPhanBoDiem`; ui-check `xep-loai-xem-truoc-khop-may-chu` (xem trước ở frontend khớp DB)
- [x] Không có xóa cứng; dữ liệu xóa mềm không hiện trong danh sách nhưng còn trong thống kê lịch sử — `SchemaConstraintsTest.khongXoaCungDuocBatKyBangNghiepVuNao`, `UserApiTest.xoaMemKhongMatBanGhiVaKhongHienTrongDanhSachMacDinh`, `ClassApiTest.lopThemSuaDongMoXoaMem`, `lopDaCoDiemKhongXoaDuoc`, `StatsApiTest.nguoiDungXoaMemKhongHienTrongDanhSachNhungConTrongThongKeLichSu`
- [x] Nhập Excel đúng với khoảng 1.000 user, khớp theo mã định danh, báo lỗi theo dòng, ghi tất cả hoặc không gì — `ExcelImportApiTest.nhap1000NguoiDungDungVaKhongGhiNuaChung`, `capNhatTheoMaHienNguoiDuocKhopVaGiuOTrong`
- [x] Giám khảo thêm người vào nội dung mình từ mọi đơn vị và chỉ nhận về 4 trường — `ClassApiTest.giaoVienTimUngVienChiNhanBonTruong`, `giaoVienKhongThaoTacDuocLopKhongPhaiCuaMinh`
- [x] Người đã có điểm bị gỡ khỏi nội dung không còn trong thống kê, điểm vẫn nằm trong dữ liệu — `StatsApiTest.chuyenDonViKhongDoiThongKeCuVaBoNguoiDaGo`, `ClassApiTest.lyDoVangVaGoNguoiDaCoDiem`
- [x] Chuyển đơn vị không làm đổi thống kê cũ của đơn vị trước — `StatsApiTest.chuyenDonViKhongDoiThongKeCuVaBoNguoiDaGo` (so sánh số liệu trước và sau khi chuyển)
- [x] Sao lưu tự động đúng lịch, giữ số bản cấu hình, mở lại được — `BackupApiTest.saoLuuThuCongMoLaiDuocVaTaiVe`, `tuDongXoayVongChiGiuSoBanCauHinh`, `denLuotSaoLuuTheoGioVietNamVaMoiNgayMotLan`; `UpgradeBackupTest` (lịch kiểm bằng test logic)
- [x] Thông báo đề nghị hiện đúng người và mất số đếm khi xử lý — `RequestApiTest.luongDeNghiThemDuyetKemThongBao`, `docThongBaoChiCuaMinh`; ui-check `chuong-co-so-chua-doc`, `bam-thong-bao-mo-de-nghi`
- [x] Frontend chỉ dùng HTML, CSS, JS thuần; màn hình chính dưới 2 giây ở tải mục tiêu — không có npm, CDN, font hay ảnh ngoài (ui-check đếm request ra ngoài ở mọi trang), không có `innerHTML` (M7.4); `LoadTest`: 150 lần mở trang chủ do 50 người ảo, p50 493 ms, p95 866 ms, chậm nhất 991 ms, 0 lỗi (máy phát triển)
- [x] Responsive: mọi độ rộng trong bộ kiểm tra không cuộn ngang, vùng chạm tối thiểu 44×44 px — `ui-check` ở 360, 390, 768, 1024, 1366, 1920 px
- [x] Tương phản đạt AA, xếp loại có chữ, bản in đen trắng — bảng đo WCAG ở [OPERATIONS-GUIDE.md](OPERATIONS-GUIDE.md) phụ lục B (các màu đo vẫn nằm đúng trong `tokens.css`; vàng kim chỉ dùng trên nền tối); xếp loại luôn kèm chữ (ui-check `nguoi-hoc-loc-tinh-trang-xep-loai`); bản in đen trắng (ui-check `ban-in-den-trang`, PDF `print-user.pdf`, `print-class-results.pdf` đã mở xem)
- [x] Giao diện đúng phong cách Hải quân đã thống nhất — chủ dự án xác nhận

**Kết luận:** 15 trên 15 tiêu chí nêu ở trên đạt; M7.5 hoàn thành, toàn bộ các mốc M0 đến M7 đạt dòng "Hoàn thành khi".
