# CLAUDE.md

Quy tắc cho agent làm việc trong repo này. Đọc kỹ trước mỗi phiên.

## Dự án

Ứng dụng web quản lý và thống kê huấn luyện cho một Lữ đoàn: user, đơn vị theo cây, nội dung kiểm tra (trong mã gọi là `class`), chấm điểm, xếp loại, thống kê, in báo cáo.

- Backend: Spring Boot 3.5, Java 25, SQLite (một file), Flyway, `JdbcTemplate` (không dùng JPA/Hibernate).
- Frontend: HTML, CSS, JS thuần, ES modules. **Không framework, không npm/npx, không CDN, không font hay ảnh từ bên ngoài** (font Inter lưu sẵn ở `frontend/assets/fonts`, giấy phép OFL).
- Triển khai: bộ cài Windows kèm JRE, chạy như Windows Service, mạng LAN không có Internet.
- Giao diện màu Hải quân Việt Nam (xanh hải quân, trắng, vàng kim) theo kiểu dashboard quản trị mật độ cao (tham khảo AdminKit, Admindek: thanh bên chia nhóm, đầu trang gọn, bảng dòng thấp có nút thao tác trên dòng qua `ui/row-actions.js`, thẻ số liệu, bo góc, đổ bóng nhẹ, chữ thường), responsive, ưu tiên điện thoại. Xem ARCHITECTURE 4.1.

## Tài liệu gốc

Đây là nguồn sự thật. Đọc phần liên quan trước khi làm.

| File | Dùng khi |
|---|---|
| [docs/PRD.md](docs/PRD.md) | Cần biết phạm vi chức năng, tiêu chí hoàn thành, 42 quyết định đã chốt |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Schema, API, cấu trúc thư mục, ma trận phân quyền, giao diện (mục 4.1), rủi ro |
| [docs/PLAN.md](docs/PLAN.md) | Các việc đã hoàn thành theo mốc M0–M7 và nghiệm thu theo PRD |
| [docs/USE-CASE.md](docs/USE-CASE.md), [docs/API.md](docs/API.md), [docs/ERD.md](docs/ERD.md) | Use case, danh sách API, sơ đồ dữ liệu. Thêm hoặc đổi endpoint, bảng, cột thì cập nhật cùng lúc |
| [docs/USER-GUIDE.md](docs/USER-GUIDE.md) | Hướng dẫn sử dụng theo vai trò. Đổi tên menu, nút, quy trình trên giao diện thì cập nhật cùng lúc |
| [docs/OPERATIONS-GUIDE.md](docs/OPERATIONS-GUIDE.md) | Cài đặt, nâng cấp, sao lưu, khôi phục, đặt lại mật khẩu Super Admin, build, test, lỗi thường gặp |
| `docs/images/` | Banner động, ảnh chụp, GIF và ảnh social preview dùng cho README (xem mục "Ảnh minh họa") |

## Quy trình làm việc

1. Đầu mỗi nhiệm vụ, mở `docs/PLAN.md`, xác định việc đang làm thuộc mốc nào. **Không nhảy sang mốc sau khi mốc trước chưa đạt dòng "Hoàn thành khi".**
2. Đánh dấu `[x]` trong PLAN.md **chỉ khi** việc đó xong thật: code chạy, test đạt (nếu là logic), đã kiểm tra trên giao diện (nếu là màn hình). PLAN chỉ ghi việc đã hoàn thành và đã kiểm tra; việc làm dở hoặc chưa kiểm tra được thì chưa đưa vào.
3. Nếu quyết định hoặc yêu cầu thay đổi, **sửa tài liệu trước hoặc cùng lúc với code**. Đồng bộ PRD, ARCHITECTURE, PLAN, cùng API, ERD, USE-CASE và hai bản hướng dẫn (bảng trên), không để chúng mâu thuẫn nhau hay với mã.
4. Gặp điều chưa rõ hoặc mâu thuẫn giữa tài liệu và yêu cầu: **hỏi người dùng**, không tự đoán. Với quyết định đã chốt trong PRD thì không đổi nếu chưa được đồng ý.
5. Việc nhỏ làm trọn một mạch. Không mở rộng phạm vi, không thêm tính năng ngoài PLAN.

## Lệnh build và test

Cần `JAVA_HOME` trỏ tới JDK 25. Chạy trong `backend/` (Windows dùng `mvnw.cmd`):

- Test toàn bộ: `./mvnw test`
- Một lớp test: `./mvnw test -Dtest=SqliteSpikeTest`
- Chạy ứng dụng: `./mvnw spring-boot:run` (cổng 8080, dữ liệu ở `backend/data/`)
- Đóng gói JAR: `./mvnw clean package -DskipTests` (luôn `clean`: file migration đã xóa mà còn trong `target/classes` sẽ vào JAR và Flyway báo trùng version)
- Bộ cài Windows: `installer\windows\build.ps1` (thêm `-SkipTests` nếu chỉ cần đóng gói)
- `tools/ui-check.ps1` phải lưu UTF-8 có BOM (có chữ tiếng Việt, Windows PowerShell 5.1 đọc sai nếu không có BOM). Các script khác trong `installer/` chỉ dùng ASCII.
- Trong script PowerShell, tham số dạng `-Dx.y=z` truyền cho `java` phải đặt trong nháy (`'-Dfile.encoding=UTF-8'`). PowerShell 5.1 tách nó tại dấu chấm và java báo "Could not find or load main class".
- Gọi API trong `tools/ui-check.ps1` bằng `fetch` trong trình duyệt, không dùng `Invoke-WebRequest` để đăng nhập: phần mềm diệt virus trên máy phát triển chặn cả script.
- Kiểm tra giao diện (cần máy chủ đang chạy): `tools\ui-check.ps1 -BaseUrl http://localhost:18095 -Password '...'`. Nó đổi mật khẩu tạm rồi đổi lại và tạo dữ liệu mẫu, nên **chỉ chạy vào máy chủ thử riêng** (dòng dưới), không vào dịch vụ thật đang dùng (cổng 8080).
- Máy chủ thử riêng cho ui-check và chụp ảnh: `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=18095 --app.data-dir=$env:TEMP\ts-thu\data --app.config-dir=$env:TEMP\ts-thu\config"` (cả `-Dspring-boot.run.arguments=...` phải trong một cặp nháy kép, nếu không Maven báo `Unrecognized option`); đặt mật khẩu Super Admin bằng `config\first-run.properties`. Dừng bằng cách kết thúc **đúng tiến trình đang nghe cổng thử**; không tắt hàng loạt tiến trình (java, msedge) và không động tới dịch vụ `TrainingStats` thật.
- Tạo hoặc đặt lại Super Admin khi phát triển: đặt biến `TS_RESET_PASSWORD` rồi `./mvnw spring-boot:run -Dspring-boot.run.arguments=--reset-superadmin`
- Kiểu xuống dòng: repo có cả file CRLF thuần và LF thuần (tài liệu, JS, CSS, script là CRLF; Java là LF). Giữ nguyên kiểu của từng file, không để một file lẫn cả hai; sau khi sửa bằng công cụ dòng lệnh thì kiểm lại.
- Test DB dùng `DbTestBase` (không web) hoặc `WebTestBase` (server thật, có Super Admin `admin` tạo từ first-run). Dữ liệu trong `WebTestBase` phải commit, không dùng `@Transactional` ở lớp test.

Mở `http://localhost:8080/` sau khi chạy. Cài đặt, build, chạy, test, sao lưu, khôi phục và lỗi thường gặp nằm ở [docs/OPERATIONS-GUIDE.md](docs/OPERATIONS-GUIDE.md); kết quả kiểm chứng kỹ thuật và các phát hiện của M0 ở phụ lục A của file đó. Đọc phụ lục A trước khi viết code truy cập dữ liệu.

## Quy tắc bất biến

Vi phạm các điều này là lỗi nghiêm trọng, kể cả khi "tiện hơn".

**Phân quyền**
- Kiểm tra phạm vi nhánh ở **backend, trong truy vấn SQL**, qua `ScopeService`. Ẩn nút trên giao diện không phải là phân quyền.
- Mọi truy vấn trong các gói `user`, `participant`, `result`, `stats` phải nhận tham số `Scope`. Không có truy vấn "lấy theo id" mà không kèm phạm vi.
- Bản ghi ngoài phạm vi trả **404**, không phải 403.
- Điều kiện nhánh: `unit.code = U OR unit.code LIKE U || '.%'`. Luôn có dấu chấm, để `0002` không khớp nhầm `00021`.
- Cấp admin suy ra từ đơn vị, không lưu riêng. Khi người không phải Super Admin chuyển một `ADMIN` ra ngoài phạm vi của mình thì tự hạ về `USER` và ghi audit log.
- Giám khảo (trong mã là `teacher`) là gán theo từng nội dung kiểm tra (`class_teacher`), không phải vai trò tài khoản. Kiểm bằng `ClassAccess`.
- Lấy người đang đăng nhập và phạm vi qua `ScopeService`; ghép điều kiện bằng `Scope.unitFilter/userFilter`. Principal được nạp lại từ DB ở mỗi request, không cache thông tin quyền ở chỗ khác.
- Thêm endpoint mới thì `EndpointAccessScanTest` tự kiểm. Nếu user thường được gọi, thêm vào `USER_ALLOWED` trong test đó kèm lý do.
- Endpoint nhận file khai báo `@RequestParam(value = "file", required = false) MultipartFile` và kiểm quyền trước khi đọc file. Để `required = true` thì Spring trả 400 trước khi kịp kiểm quyền, người không có quyền không nhận 403/404.
- Thao tác khóa, xóa, hạ quyền tài khoản phải gọi `UserGuards.checkNotSelf` và `checkSuperAdminRemains`.

**Dữ liệu**
- **Không bao giờ xóa cứng.** Dùng `status`/`deleted_at`/`removed_at`. Không viết câu `DELETE` trên bảng nghiệp vụ.
- Điểm lưu dạng số nguyên `score_x10` (6.5 lưu là 65). Không dùng số thực. Xếp loại là cột tính ở DB, không tính lại ở nơi khác (ngoại lệ duy nhất: `previewGrade` ở frontend để xem trước khi chấm, có kịch bản ui-check so với DB, xem ARCHITECTURE D6).
- Mỗi người học **chỉ một điểm** (UNIQUE `participant_id`). Chỉ Super Admin sửa, bắt buộc có lý do, ghi `result_history` và audit log.
- Thống kê theo đơn vị dùng `unit_id_snapshot`, bỏ qua người đã bị gỡ khỏi lớp, và **không trộn** điểm cá nhân (`PERSON`) với điểm đơn vị (`UNIT`).
- User `UNIT` không có tài khoản, không đăng nhập, không làm giáo viên hay admin. Danh sách nhân sự mặc định luôn lọc `kind = 'PERSON'`.
- Bật `PRAGMA foreign_keys=ON` cho mọi kết nối. Thời gian lưu UTC ISO-8601.
- Kết nối SQLite luôn dùng `transaction_mode=IMMEDIATE` (đã có trong `SqliteConfig`). Bỏ đi sẽ gặp `SQLITE_BUSY` khi giao dịch đọc rồi ghi.
- Trùng khóa bắt `DuplicateKeyException`, vi phạm ràng buộc khác là `DataIntegrityViolationException` (nhờ `SqliteExceptionTranslator`). Không đọc mã lỗi SQLite bằng tay trong code nghiệp vụ.
- **Mọi lệnh ghi nằm trong giao dịch ghi** (`@Transactional` không readOnly hoặc `TransactionTemplate`). Ngoài đó `JdbcTemplate` đi pool chỉ đọc và lỗi ngay.
- **Không dùng `REQUIRES_NEW`**: pool ghi có đúng một kết nối, giao dịch lồng sẽ tự chờ mãi. Không chạy việc chậm (BCrypt, đọc file Excel) bên trong giao dịch ghi.
- Cột có thể NULL đọc bằng `JdbcValues.nullableLong/nullableInt`, không dùng `rs.getObject(col, Long.class)` (sqlite-jdbc ném lỗi khi NULL).
- Mọi thay đổi schema đi qua file Flyway mới (`V<n>__*.sql`). **Không sửa file migration đã có.** (Ngoại lệ duy nhất đã làm, theo yêu cầu của chủ dự án ngày 2026-11-19: gộp `V1` đến `V6` thành `V1__init.sql` vì gỡ bản cũ và cài lại từ đầu; ngày 2026-11-20 thêm cột `app_user.category` thẳng vào `V1__init.sql`, cũng theo yêu cầu và cũng xóa dữ liệu cài lại. Từ đó quy tắc có hiệu lực lại với `V1__init.sql`. Ngoại lệ thứ hai và thứ ba, cũng theo yêu cầu của chủ dự án: sửa chú thích trong `V1__init.sql` (checksum `-669641724` thành `2136348535`), rồi thêm cột `app_user.must_change_password` thẳng vào `V1__init.sql` (checksum thành `-527056567`); `UpgradeBackup.alignV1` tự đưa bản cài cũ lên bản hiện tại (sao lưu, thêm cột, đổi checksum), `UpgradeBackupTest` chặn mọi lần sửa thêm. Sửa `V1` lần nữa thì phải thêm checksum cũ vào `alignV1` và cách nâng cấp tương ứng. Xem ARCHITECTURE S13.)
- Tìm kiếm tiếng Việt dùng cột `search_text` (đã bỏ dấu, chữ thường), không dùng `LIKE` trực tiếp trên cột có dấu.

**Bảo mật**
- Mật khẩu chỉ lưu BCrypt. Không ghi mật khẩu, hash hay token vào log, audit log, hay phản hồi API.
- Chỗ nào ghi mật khẩu do người khác đặt (tạo, cấp tài khoản, nhập Excel, đặt lại) phải bật `app_user.must_change_password = 1`; chỉ chính chủ đổi mật khẩu mới tắt. `PasswordChangeGate` chặn mọi API khác bằng 403 `PASSWORD_CHANGE_REQUIRED` ở máy chủ; endpoint mới cần dùng được trước khi đổi mật khẩu thì thêm vào `PasswordChangeGate.allowed` kèm lý do (ARCHITECTURE A14).
- Ghi nhật ký qua `AuditService.record` (cùng giao dịch với thao tác). Mọi hành động quan trọng ghi audit log: đăng nhập thành công và thất bại, đổi quyền, đổi đơn vị, xóa mềm, thêm/gỡ người học, nhập và sửa điểm, nhập Excel, sao lưu.
- Giáo viên tìm ứng viên toàn Lữ đoàn chỉ nhận tên, cấp bậc, đơn vị, mã định danh. Không trả ngày sinh, số điện thoại, tài khoản.
- Dữ liệu người dùng đưa vào DOM luôn qua `ui/escape.js` hoặc `textContent`. **Cấm `innerHTML` với dữ liệu từ server hoặc người dùng.**
- Đã chốt không làm HTTPS và không trì hoãn/khóa khi nhập sai. Không tự thêm vào, nhưng không để làm hỏng thiết kế (xem R4 trong ARCHITECTURE.md).

**Frontend**
- Thiết kế ưu tiên điện thoại: viết kiểu cho màn hình nhỏ trước, mở rộng bằng `min-width`. Điểm ngắt 600, 1024, 1440 px.
- Màu, cỡ chữ, khoảng cách, bo góc **chỉ** nằm trong `css/tokens.css`. Không đặt giá trị cứng ở file khác.
- Vùng chạm tối thiểu 44×44 px, cỡ chữ ô nhập tối thiểu 16 px, không có chức năng chỉ dùng được khi hover, không cuộn ngang ở cấp trang.
- Không dùng quốc huy, quân hiệu, cấp hiệu, hải quân kỳ hay hình ảnh chính thức. Biểu tượng là SVG tự vẽ trung tính (logo khiên và mỏ neo).
- Xếp loại luôn có chữ kèm màu, không chỉ dùng màu.
- Bản in (`print.css`) đen trắng, bỏ nền và thanh điều hướng.
- Thêm phần tử vào trang bằng `append()`/`replace()` của `ui/escape.js`, không dùng `container.append()` gốc (nó in `null` thành chữ). Dựng DOM bằng `h()` (`ui/escape.js`), gọi API bằng `api()` (`core/api.js`), form bằng `createForm`, bảng bằng `createTable`, hộp thoại bằng `openModal`, ô lọc chọn nhiều bằng `ui/multi-select.js`, ô chọn một đơn vị bằng `ui/radio-select.js`, trang in bằng `ui/print-sheet.js` (cùng bố cục file Excel), biểu đồ bằng `ui/charts.js` (màu xếp loại cố định theo xếp loại, luôn kèm chú giải chữ và bảng số liệu), đầu trang bằng `pageHead` (`ui/page-head.js`), ô thanh lọc bằng `filterField`/`filterSelect` (`ui/filter-field.js`), dòng danh sách mô tả bằng `dlRow` (`ui/dl.js`). Không tự viết lại các phần này trong từng trang.
- Thêm trang: module trong `js/pages/` xuất `render(container, params)`, khai báo `route(...)` trong `app.js`, thêm vào `NAV_ITEMS` nếu cần, thêm vào `$pages` của `tools/ui-check.ps1`. Trang con (không nằm trên menu) đặt `backLink(href, tên trang cha)` của `ui/back-link.js` ngay trước `.page-head`, không đặt nút "Về ..." trong nhóm nút thao tác.
- Trang chỉ được tích `[x]` trong PLAN khi đã qua `tools/ui-check.ps1` và đã xem ảnh chụp ở 360 px và 1366 px (docs/OPERATIONS-GUIDE.md mục 11.3, 11.4).
- Vàng kim (`--c-gold`, `--accent-on-dark`) CHỈ dùng trên nền xanh tối. Trên nền sáng nó không đạt tương phản (2.42:1); viền focus trên nền sáng là `--focus-ring`.

## Quy ước mã

- Package gốc `vn.trainingstats`, tổ chức **theo tính năng** (`user`, `exam`, `participant`, `result`...), mỗi tính năng có controller, service, repository, DTO. Không tổ chức theo tầng.
- Route dưới `/api/v1`, JSON. Lỗi theo dạng `{code, message, fieldErrors[]}`: ném `ApiException` (`notFound`, `conflict`, `invalidField`...), không tự dựng response lỗi. Phân trang `{items, page, size, total}`. 409 khi trùng hoặc sai `version`.
- Bảng tên số ít, `snake_case`. Bảng người dùng là `app_user` (vì `user` là từ khóa SQL).
- Tên mã, tên biến, tên bảng, tên API dùng tiếng Anh. **Nội dung hiển thị, thông báo lỗi cho người dùng, tài liệu, commit message dùng tiếng Việt.**
- Mỗi trang frontend là một ES module xuất `render(container, params)`. Không biến toàn cục.
- Nhập Excel luôn có bước `dryRun`, ghi tất cả hoặc không ghi gì, báo lỗi theo dòng. Giới hạn kích thước file.
- Khi thêm logic phân quyền, xếp loại, điểm một lần, chuyển đơn vị, nhập Excel: **viết test cùng lúc**. Các điểm biên xếp loại cần test: 4.9, 5.0, 6.5, 6.6, 7.9, 8.0.

## Ảnh minh họa (docs/images)

- Chỉ chụp từ máy chủ thử với **dữ liệu mẫu giả** (tên người hư cấu, tên đơn vị chung). Không đưa tên thật của đơn vị, người hay số hiệu vào ảnh; logo trong ảnh ghi "Lữ đoàn" không kèm số.
- Dựng lại ảnh khi giao diện thay đổi đáng kể. `banner.svg` chỉ dùng SMIL, không script, không tài nguyên ngoài. `social-preview.png` rộng 1280×640 và dưới 1 MB, **phải tải lên thủ công** ở GitHub: Settings → General → Social preview.

## Git

- Không commit, không push, không tạo PR nếu người dùng chưa yêu cầu.
- Commit message tiếng Việt, ngắn, nêu rõ làm gì. Mỗi commit một việc.
- Không commit file DB, log, thư mục build, mật khẩu hay khóa. Ảnh trong `docs/images` thì được commit.

## Cách trả lời

- Trả lời bằng tiếng Việt, ngắn gọn, nêu kết quả trước.
- Báo cáo trung thực: test lỗi thì nói rõ kèm kết quả, việc bỏ qua thì nói bỏ qua. Không nói "xong" khi chưa kiểm tra.
- Khi đề xuất lựa chọn, đưa một khuyến nghị kèm lý do, không liệt kê dàn trải.
- Không thêm bình luận trong code chỉ để mô tả việc code đang làm. Chỉ ghi chú **vì sao** khi lý do không hiển nhiên.
