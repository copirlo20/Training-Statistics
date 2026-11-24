# API: Tài liệu giao diện lập trình

Tài liệu mô tả toàn bộ REST API của hệ thống, đối chiếu với các controller trong `backend/src/main/java/vn/trainingstats/`. Quyết định thiết kế và ghi chú chi tiết từng điểm nằm ở [ARCHITECTURE.md](ARCHITECTURE.md) mục 3 (mã A, C, D, E, P, S trong ngoặc là ghi chú ở đó). Dữ liệu ở [ERD.md](ERD.md), kịch bản sử dụng ở [USE-CASE.md](USE-CASE.md).

> **Thuật ngữ.** "Lớp" (`/classes`) là **nội dung kiểm tra** trên giao diện, "giáo viên" (`teachers`) là **giám khảo**. Tên trong API giữ nguyên để không đổi hợp đồng.

## Mục lục

1. [Quy ước chung](#1-quy-ước-chung)
2. [Xác thực và tài khoản của mình](#2-xác-thực-và-tài-khoản-của-mình)
3. [Đơn vị và danh mục](#3-đơn-vị-và-danh-mục)
4. [Người dùng](#4-người-dùng)
5. [Đợt kiểm tra và nội dung kiểm tra](#5-đợt-kiểm-tra-và-nội-dung-kiểm-tra)
6. [Người học](#6-người-học)
7. [Đề nghị](#7-đề-nghị)
8. [Điểm](#8-điểm)
9. [Bảng phân công](#9-bảng-phân-công)
10. [Thống kê](#10-thống-kê)
11. [Thông báo](#11-thông-báo)
12. [Quản trị hệ thống](#12-quản-trị-hệ-thống)
13. [Nhập, xuất Excel](#13-nhập-xuất-excel)
14. [Phụ lục: mã lỗi, hành động nhật ký](#14-phụ-lục)

---

## 1. Quy ước chung

### 1.1 Địa chỉ, định dạng

| Mục | Giá trị |
|---|---|
| Gốc | `http://<máy chủ>:<cổng>/api/v1` (mặc định cổng 8080, chỉ HTTP) |
| Dữ liệu | JSON, UTF-8 (`Content-Type: application/json`). File Excel: `multipart/form-data` |
| Ngày, giờ | Ngày `YYYY-MM-DD`; thời điểm ISO-8601 theo UTC (`2026-11-24T03:15:00Z`). Giao diện đổi sang giờ `Asia/Ho_Chi_Minh` |
| Điểm | Gửi lên là số thập phân 0–10 tối đa một chữ số lẻ (`6.5`); máy chủ lưu `score_x10` (65). Trả về `scoreX10` (số nguyên) và `grade` |
| Không xóa cứng | Không có `DELETE`. Xóa mềm dùng `POST .../delete` hoặc `PATCH .../status` |

### 1.2 Xác thực và CSRF

Đăng nhập tạo session phía server (cookie `HttpOnly`, `SameSite=Lax`, không `Secure` vì chạy HTTP). Mật khẩu chỉ lưu BCrypt.

1. `GET /auth/csrf` → máy chủ đặt cookie `XSRF-TOKEN` và trả `{headerName, token}` (`headerName` là `X-XSRF-TOKEN`).
2. Mọi request **ghi** (`POST`, `PUT`, `PATCH`) gửi lại token trong header `X-XSRF-TOKEN`. Thiếu hoặc sai: `403 CSRF_INVALID`.
3. `POST /auth/login` → cookie phiên. Mọi route khác (trừ `GET /auth/csrf`) yêu cầu đã đăng nhập, không thì `401 UNAUTHENTICATED`.
4. Phiên hết hạn sau `session.timeout-minutes` (mặc định 30 phút) không hoạt động. Mỗi request nạp lại người dùng từ DB nên khóa tài khoản, đổi quyền, chuyển đơn vị có hiệu lực ngay.

```http
POST /api/v1/auth/login
Content-Type: application/json
X-XSRF-TOKEN: <token>

{ "username": "admin", "password": "******" }
```

### 1.3 Phân quyền

Cột "Ai" ở các bảng dưới là quyền tối thiểu; chi tiết ở [ARCHITECTURE.md mục 5](ARCHITECTURE.md#5-ma-trận-phân-quyền).

| Ký hiệu | Nghĩa |
|---|---|
| Công khai | Không cần đăng nhập |
| Đã đăng nhập | Mọi vai trò; kết quả tự lọc theo quyền |
| Admin+ | `ADMIN` hoặc `SUPER_ADMIN` |
| Super Admin | Chỉ `SUPER_ADMIN` |
| Giám khảo | Super Admin hoặc người được gán vào nội dung đó (`class_teacher`); không phải vai trò tài khoản |

Quy tắc xuyên suốt:

- Phạm vi nhánh áp dụng **trong truy vấn SQL**, không chỉ ở giao diện. Admin của đơn vị `U` thấy `U` và mọi đơn vị con.
- **Bản ghi ngoài phạm vi trả `404`**, không phải `403`, để không lộ là có tồn tại.
- Endpoint nhận file kiểm quyền **trước** khi đọc file: tham số `file` không bắt buộc ở tầng Spring (E7).

### 1.4 Phân trang

Danh sách lớn nhận `?page=0&size=20` (`size` tối đa 100) và trả:

```json
{ "items": [ ... ], "page": 0, "size": 20, "total": 134 }
```

### 1.5 Lỗi

Mọi lỗi có dạng thống nhất:

```json
{
  "code": "VALIDATION_FAILED",
  "message": "Dữ liệu không hợp lệ.",
  "fieldErrors": [ { "field": "score", "message": "Điểm tối đa một chữ số thập phân." } ]
}
```

| Mã HTTP | Ý nghĩa |
|---|---|
| 400 | Dữ liệu sai (`VALIDATION_FAILED` kèm `fieldErrors`), file sai, thiếu tham số |
| 401 | Chưa đăng nhập (`UNAUTHENTICATED`), sai tài khoản hoặc mật khẩu (`INVALID_CREDENTIALS`) |
| 403 | Đã đăng nhập nhưng sai vai trò (`FORBIDDEN`), thiếu CSRF (`CSRF_INVALID`), hoặc quy tắc nghiệp vụ (`SELF_ACTION`, `SELF_GRADING`, `NOT_TEACHER`...) |
| 404 | Không có hoặc **ngoài phạm vi** |
| 409 | Trùng (`DUPLICATE`...), đã chấm điểm (`ALREADY_GRADED`), sai `version` (`VERSION_CONFLICT`), trạng thái không cho phép (`CLASS_CLOSED`...) |
| 500 | Lỗi lạ, trả câu chung, không lộ SQL (`INTERNAL_ERROR`) |

Danh sách mã lỗi nghiệp vụ ở [phụ lục](#14-phụ-lục).

### 1.6 Khóa lạc quan

Bản ghi có `version` (người dùng, đợt, nội dung, điểm): gửi kèm `version` đã đọc khi sửa. Hai người sửa cùng bản ghi thì người lưu sau nhận `409 VERSION_CONFLICT`.

### 1.7 Lọc nhiều giá trị

Tham số lọc nhận nhiều giá trị bằng dấu phẩy (`unitId=1,2`) hoặc lặp tham số (`unitId=1&unitId=2`). "Hoặc" trong một tiêu chí, "và" giữa các tiêu chí. Đơn vị gồm cả đơn vị con; một đơn vị ngoài phạm vi trong danh sách là `404`.

---

## 2. Xác thực và tài khoản của mình

| Method | Route | Ai | Mô tả |
|---|---|---|---|
| GET | `/auth/csrf` | Công khai | Cấp token CSRF, đặt cookie `XSRF-TOKEN` |
| POST | `/auth/login` | Công khai | Đăng nhập. Body `{username, password}`. Trả `Me`. Lỗi: `401 INVALID_CREDENTIALS` (sai tài khoản hoặc mật khẩu, cùng một câu), `401 ACCOUNT_LOCKED` (chỉ báo khi mật khẩu đúng nhưng tài khoản bị khóa). Không trì hoãn, không khóa khi nhập sai; mọi lần thất bại ghi nhật ký kèm IP |
| POST | `/auth/logout` | Đã đăng nhập | Đăng xuất. `204` |
| GET | `/me` | Đã đăng nhập | Thông tin của mình |
| PUT | `/me/password` | Đã đăng nhập | Đổi mật khẩu. Body `{currentPassword, newPassword}`. `204`. Độ dài tối thiểu theo cài đặt, tối đa 72 byte |
| GET | `/me/classes` | Đã đăng nhập | Các nội dung mình tham gia và điểm (D5) |

`Me`:

```json
{
  "id": 7, "username": "admin", "displayName": "Quản trị hệ thống",
  "role": "SUPER_ADMIN", "adminLevel": null,
  "unit": null, "rank": null, "position": null,
  "teachingClasses": [ { "id": 3, "name": "...", "status": "OPEN" } ],
  "sessionTimeoutMinutes": 30
}
```

`adminLevel` suy ra từ đơn vị (1 hoặc 2), `null` với Super Admin và người dùng thường.

---

## 3. Đơn vị và danh mục

| Method | Route | Ai | Mô tả |
|---|---|---|---|
| GET | `/units/tree` | Admin+ | **Toàn bộ** cây đơn vị (không giới hạn theo phạm vi), dùng cho ô chọn và chuyển đơn vị. Trả `[UnitView]` |
| POST | `/units` | Super Admin | Thêm đơn vị. Body `{parentId, segment, name, sortOrder}`. `segment` là 4 ký tự chữ hoa hoặc số, bỏ trống thì tự đánh số kế tiếp trong cùng cấp; `parentId` null là cấp 1, phải là cấp 1 mới thêm được cấp 2 (A1). `201` |
| PUT | `/units/{id}` | Super Admin | Sửa `{name, sortOrder}`. Không đổi `code`, `parentId`, `level` |
| PATCH | `/units/{id}/status` | Super Admin | Khóa hoặc mở. Body `{active}` |
| GET | `/catalogs` | Đã đăng nhập | Toàn bộ danh mục: `{ranks, positions, specialties, absenceReasons}`, kể cả mục đã khóa (có cờ `active`) |
| POST | `/catalogs/{type}` | Super Admin | Thêm mục. `type` ∈ `ranks`, `positions`, `specialties`, `absence-reasons`. Body `{name, symbol, sortOrder}` (`symbol` chỉ với `ranks`). `201` |
| PUT | `/catalogs/{type}/{id}` | Super Admin | Sửa mục |
| PATCH | `/catalogs/{type}/{id}/status` | Super Admin | Khóa, mở. Body `{active}`. Mục đã khóa không gán được cho dữ liệu mới |

`UnitView`: `{id, code, name, parentId, level, sortOrder, active}`.

---

## 4. Người dùng

| Method | Route | Ai | Mô tả |
|---|---|---|---|
| GET | `/users` | Admin+ | Tìm kiếm, lọc, phân trang. **Chỉ trong phạm vi**. Mặc định chỉ `PERSON` |
| GET | `/users/{id}` | Trong phạm vi, hoặc chính mình | Chi tiết (`UserView`). User thường chỉ gọi cho chính mình, id khác `404` (A10) |
| POST | `/users` | Admin+ trong phạm vi | Tạo người `PERSON`. `201`. Đơn vị phải thuộc phạm vi người tạo |
| PUT | `/users/{id}` | Admin+ trong phạm vi | Sửa **hồ sơ** (kèm `version`). Không gồm tài khoản, đơn vị, quyền, trạng thái (A5) |
| PUT | `/users/{id}/account` | Admin+ trong phạm vi | Cấp, đổi tên hoặc thu hồi tài khoản đăng nhập. Body `{username}`; để trống là thu hồi (chỉ với người dùng thường). Tài khoản mới nhận mật khẩu mặc định `123456789` (A13). Trùng `409` |
| PATCH | `/users/{id}/status` | Admin+ trong phạm vi | Body `{status}`: `ACTIVE`, `INACTIVE`, `DELETED`. `ACTIVE` dùng để mở khóa hoặc khôi phục. Không tự đổi của mình; luôn còn một Super Admin |
| PUT | `/users/{id}/role` | Admin+ trong phạm vi | Body `{role, unitId}`. Admin cấp `ADMIN` cho người trong nhánh; chỉ Super Admin cấp `SUPER_ADMIN`. Lên `SUPER_ADMIN` thì bỏ đơn vị; hạ xuống thì bắt buộc `unitId` (A6) |
| POST | `/users/{id}/reset-password` | Admin+ trong phạm vi | Body `{newPassword}`. `204`. Không dùng cho chính mình (dùng `/me/password`); tài khoản Super Admin với Admin là `404` (A8) |
| POST | `/users/{id}/move` | Admin+ trong phạm vi | Chuyển sang **bất kỳ** đơn vị. Body `{unitId}`. Trả `{downgraded, stillInScope, user}`. Người chuyển không phải Super Admin và đích ngoài phạm vi: người được chuyển nếu là `ADMIN` bị **tự hạ về `USER`** và ghi nhật ký (R1); `user` là null khi người chuyển không còn quyền xem (A7) |
| GET | `/users/{id}/history` | Trong phạm vi, hoặc chính mình | Lịch sử điểm qua các đợt, cùng dạng `/me/classes` (D5) |
| GET | `/users/{id}/print` | Trong phạm vi, hoặc chính mình | Dữ liệu trang in thông tin cá nhân (cấp bậc tên đầy đủ) |
| GET | `/users/import-template` | Admin+ | Tải file Excel mẫu nhập người dùng |
| POST | `/users/import?dryRun=` | Admin+ | Nhập Excel, xem [mục 13](#13-nhập-xuất-excel) |

### `GET /users` — tham số

| Tham số | Kiểu | Mô tả |
|---|---|---|
| `q` | string | Tìm trên `search_text` (họ tên, mã định danh, tài khoản; không dấu) |
| `unitId` | số, nhiều giá trị | Đơn vị, gồm đơn vị con |
| `rankId`, `positionId`, `specialtyId` | số, nhiều giá trị | |
| `category` | chuỗi, nhiều giá trị | Đối tượng `SQ`, `QNCN`, `HSQ-CS` |
| `status` | chuỗi, nhiều giá trị | `ACTIVE`, `INACTIVE`, `DELETED`, `ALL`. Mặc định bỏ `DELETED` |
| `role` | chuỗi, nhiều giá trị | `USER`, `ADMIN`, `SUPER_ADMIN` |
| `kind` | string | `PERSON` (mặc định) hoặc `UNIT` |
| `sort`, `dir` | string | `sort` ∈ `name`, `code`, `unit`, `rank`, `status`; `dir` = `asc`/`desc` |
| `page`, `size` | số | Phân trang |

Mỗi phần tử `UserListItem`: `{id, kind, fullName, personalCode, category, rankSymbol, rankName, positionName, unitId, unitCode, unitName, username, role, status}`.

### `POST /users` — thân yêu cầu

```json
{
  "lastName": "Nguyễn Văn", "firstName": "An",
  "personalCode": "",          // để trống: hệ thống cấp ND000001, ND000002...
  "unitId": 12, "rankId": 5, "positionId": 3,
  "specialtyIds": [1, 4],
  "category": "SQ",            // bắt buộc với người có đơn vị
  "birthDate": "1990-05-12", "phone": "0900000000", "note": "",
  "username": "an.nv", "password": null,   // có username mà không gửi password: dùng mật khẩu mặc định
  "role": "USER"               // USER hoặc ADMIN; SUPER_ADMIN chỉ Super Admin tạo, không có đơn vị
}
```

`PUT /users/{id}` nhận cùng các trường hồ sơ (không có `unitId`, `username`, `password`, `role`) kèm `version`. Mã định danh sửa được, không xóa được.

`UserView` gồm: `id, kind, lastName, firstName, fullName, personalCode, category, rank, position, unit, specialties[], username, hasPassword, role, adminLevel, status, birthDate, phone, note, lastLoginAt, version` (không bao giờ có `password_hash`).

---

## 5. Đợt kiểm tra và nội dung kiểm tra

### 5.1 Đợt

| Method | Route | Ai | Mô tả |
|---|---|---|---|
| GET | `/periods` | Đã đăng nhập | Danh sách đợt (để lọc). Trả `[PeriodView]` |
| GET | `/periods/{id}` | Đã đăng nhập | Một đợt |
| POST | `/periods` | Super Admin | Tạo đợt. Body `{name, startDate, endDate, note}`. `201` |
| PUT | `/periods/{id}` | Super Admin | Sửa, kèm `version` |
| PATCH | `/periods/{id}/status` | Super Admin | Body `{status}`: `OPEN`, `CLOSED`. **Đóng đợt thì mọi nội dung của đợt coi như đóng** (C1); mở lại thì nội dung trở về trạng thái riêng |
| POST | `/periods/{id}/delete` | Super Admin | Xóa mềm; chỉ khi đợt không còn nội dung (`409 PERIOD_HAS_CLASSES`). `204` |

`PeriodView`: `{id, name, startDate, endDate, status, note, classCount, version}`.

### 5.2 Nội dung kiểm tra

| Method | Route | Ai | Mô tả |
|---|---|---|---|
| GET | `/classes` | Đã đăng nhập | Danh sách, tự lọc theo vai trò: Super Admin và Admin thấy tất cả (Admin: số người chỉ đếm trong nhánh, C4); giám khảo thấy nội dung mình dạy; user thấy nội dung mình tham gia. Tham số: `periodId`, `status`, `teaching`, `ungraded` (chỉ nội dung còn người chưa chấm), `page`, `size` |
| GET | `/classes/open` | Admin+ | Nội dung đang `OPEN` (id, tên, đợt, ngày) để chọn khi tạo đề nghị |
| GET | `/classes/{id}` | Có liên quan | Chi tiết, kèm `access {manage, request, edit}` |
| POST | `/classes` | Super Admin | Tạo. Body `{periodId, name, examDate, location, note, teacherIds}`. `teacherIds` lưu cùng giao dịch (C15). Đợt đóng thì `400` ở `periodId`. `201` |
| PUT | `/classes/{id}` | Super Admin | Sửa, kèm `version`. `teacherIds` null thì giữ nguyên giám khảo; có giá trị (kể cả rỗng) thì thay toàn bộ |
| PATCH | `/classes/{id}/status` | Super Admin | Body `{status}`. Đợt đang khóa thì `409 PERIOD_CLOSED` khi mở lại |
| POST | `/classes/{id}/delete` | Super Admin | Xóa mềm. Đã có điểm: `409 CLASS_HAS_RESULTS`. Các đề nghị đang chờ chuyển `CANCELLED`. `204` |
| PUT | `/classes/{id}/teachers` | Super Admin | Body `{userIds}`: thay toàn bộ giám khảo. Phải là `PERSON` có tài khoản và `ACTIVE`, tối đa 20 (C3) |
| GET | `/classes/import-template` | Super Admin | File mẫu nhập nội dung (sheet `NoiDung`, `NguoiHoc`) |
| POST | `/classes/import?dryRun=&periodId=` | Super Admin | Nhập nhiều nội dung kèm người học (E5) |

`ClassListItem`: `{id, name, period {id, name, status}, examDate, location, status, participantCount, gradedCount, pendingCount, teaching}`. `pendingCount` là số người chưa có điểm và chưa ghi lý do vắng, cùng phạm vi đếm với `participantCount`.

`ClassView` thêm: `note, version, teachers[{id, fullName, rankSymbol, unitName}], access`.

---

## 6. Người học

Đường dẫn gốc `/classes/{classId}`.

| Method | Route | Ai | Mô tả |
|---|---|---|---|
| GET | `/participants?state=` | Có liên quan | Danh sách người học. Admin chỉ người trong nhánh (theo đơn vị **hiện tại**, C5); giám khảo và Super Admin cả nội dung; người học chỉ dòng của mình. `state` = `ACTIVE` (mặc định), `REMOVED`, `ALL`; hai giá trị sau chỉ Super Admin (`403`). Không phân trang (C7) |
| GET | `/candidates?q=&kind=` | Giám khảo | Tìm ứng viên **toàn Lữ đoàn** để thêm. Chỉ trả tên, cấp bậc, đơn vị, mã định danh; tối thiểu 2 ký tự, khoảng 20 kết quả; chỉ khi nội dung còn mở. `kind=UNIT` tìm đơn vị (R20) |
| POST | `/participants` | Giám khảo | Thêm trực tiếp. Body `{userId}` **hoặc** `{unitId}` (đơn vị: hệ thống tự tạo bản ghi `UNIT` lần đầu). `201`. Trùng `409 ALREADY_IN_CLASS`; nội dung đóng `409 CLASS_CLOSED` |
| POST | `/participants/{pid}/remove` | Giám khảo | Gỡ mềm. Body `{reason}`. Người **chưa có điểm**: giám khảo và Super Admin gỡ được. Người **đã có điểm**: chỉ Super Admin, bắt buộc `reason` (giám khảo `403 HAS_RESULT`); điểm giữ lại nhưng loại khỏi thống kê. Vẫn làm được khi nội dung đã đóng |
| PUT | `/participants/{pid}/absence` | Giám khảo | Ghi hoặc xóa lý do không kiểm tra. Body `{reasonId, note}`; `reasonId` null là xóa. Không thể vừa có điểm vừa có lý do vắng |

`Participant`:

```json
{
  "id": 41,
  "user": { "id": 9, "kind": "PERSON", "fullName": "...", "personalCode": "ND000009",
            "rankSymbol": "1/", "rankName": "...", "unitName": "...", "category": "SQ" },
  "snapshotUnit": { "id": 12, "code": "0002.0001", "name": "..." },
  "state": "TESTED",            // TESTED | ABSENT | NOT_TESTED
  "absence": null,
  "result": { "id": 5, "scoreX10": 65, "grade": "PASS", "version": 0,
              "gradedAt": "2026-11-24T03:15:00Z", "gradedByName": "...", "edited": false },
  "addedVia": "DIRECT", "requestId": null, "createdAt": "...", "removed": null
}
```

Dòng đại diện cả đơn vị có `user.kind = "UNIT"`.

---

## 7. Đề nghị

Admin không thêm trực tiếp được; Admin gửi đề nghị, giám khảo hoặc Super Admin duyệt.

| Method | Route | Ai | Mô tả |
|---|---|---|---|
| POST | `/classes/{classId}/requests` | Admin | Tạo đề nghị. Body `{type, userId}` hoặc `{type, unitId}`; `type` = `ADD`/`REMOVE`. Đối tượng ngoài nhánh `404`; `ADD` người đã trong nội dung hoặc `REMOVE` người không trong nội dung `409`; trùng đề nghị chờ `409 DUPLICATE_REQUEST`; nội dung đóng `409 CLASS_CLOSED`. `201` |
| GET | `/requests` | Admin, giám khảo, Super Admin | Admin xem đề nghị của mình; giám khảo của nội dung mình; Super Admin tất cả. Tham số `status`, `classId`, `page`, `size` |
| GET | `/requests/{id}` | Như trên | Một đề nghị. Không thấy là `404` |
| POST | `/requests/{id}/approve` | Giám khảo | Duyệt. Body tùy chọn `{note}`. `ADD`: tạo người học (đơn vị lúc vào lấy theo đơn vị **hiện tại** của đối tượng); `REMOVE`: gỡ mềm. Gỡ người đã có điểm chỉ Super Admin (ghi chú duyệt là lý do gỡ) |
| POST | `/requests/{id}/reject` | Giám khảo | Từ chối, kèm `{note}` |
| POST | `/requests/{id}/cancel` | Người đề nghị | Chỉ khi còn `PENDING` |

`RequestView`: `{id, classRef, type, target, requestedBy, requestedUnitName, status, createdAt, decidedBy, decidedAt, decisionNote, access {decide, cancel}}`. `status`: `PENDING`, `APPROVED`, `REJECTED`, `CANCELLED`. Thông báo: đề nghị mới gửi cho giám khảo của nội dung và mọi Super Admin `ACTIVE`; duyệt, từ chối gửi cho người đề nghị (C12).

---

## 8. Điểm

| Method | Route | Ai | Mô tả |
|---|---|---|---|
| POST | `/classes/{classId}/participants/{pid}/result` | Giám khảo | **Nhập điểm lần đầu.** Body `{score}` (0–10, tối đa một chữ số lẻ). `201`, trả `Participant` kèm điểm. Xóa lý do vắng trong cùng giao dịch. Nội dung đã đóng vẫn nhập được (D2) |
| PUT | `/results/{rid}` | Super Admin | **Sửa điểm.** Body `{score, reason, version}`; `reason` bắt buộc, tối đa 500 ký tự; điểm mới trùng điểm cũ thì `400`. Ghi lịch sử và nhật ký |
| GET | `/results/{rid}/history` | Super Admin | Lịch sử sửa điểm: `[{id, oldScoreX10, newScoreX10, reason, changedByName, changedAt}]` |
| GET | `/me/classes` | Đã đăng nhập | Điểm của mình (xem [mục 2](#2-xác-thực-và-tài-khoản-của-mình)) |
| GET | `/users/{id}/history` | Trong phạm vi, hoặc chính mình | Lịch sử điểm của một người |

Lỗi của `POST .../result`: `404` người không quản lý nội dung; `403 SELF_GRADING` giám khảo tự chấm cho mình (Super Admin chấm thay được); `409 ALREADY_GRADED` đã có điểm (cả khi hai người lưu cùng lúc, chỉ một bên thành công); `409 ALREADY_REMOVED` người đã gỡ; `400` điểm sai định dạng (`6.55`, `11`).

Xếp loại (cột tính ở DB): `< 5.0` `FAIL` Không đạt; `5.0–6.5` `PASS` Đạt; `6.6–7.9` `GOOD` Khá; `≥ 8.0` `EXCELLENT` Giỏi.

`GET /me/classes` và `GET /users/{id}/history` trả `[ClassResult]` = `{participantId, classInfo {id, name, periodName, examDate, status}, state, absence, result {scoreX10, grade, gradedAt, edited}}`, nội dung mới nhất trước, **bỏ dòng đã gỡ và nội dung đã xóa**. Bản ghi `UNIT` chỉ Admin trong nhánh và Super Admin xem được; thành viên đơn vị `404` (Q5).

---

## 9. Bảng phân công

Giao diện thêm người theo ma trận: hàng là người, cột là các nội dung của một đợt. Quyền: Super Admin, giám khảo của ít nhất một nội dung trong đợt, Admin; người dùng thường không phải giám khảo nhận `404` (P1).

| Method | Route | Mô tả |
|---|---|---|
| GET | `/assignments/matrix` | Ma trận người × nội dung |
| GET | `/assignments/units?periodId=` | Đơn vị trong phạm vi kèm `people` (số người thuộc đúng đơn vị) |
| POST | `/assignments/apply` | Áp dụng thay đổi. Body `{changes: [{classId, userId, action}]}`, `action` = `ADD`/`REMOVE`, tối đa 300 thay đổi. Trả kết quả từng thay đổi |
| GET | `/assignments/template?periodId=` | File mẫu phân công (E10) |
| POST | `/assignments/import?periodId=&dryRun=` | Nhập file phân công (E10) |

`GET /assignments/matrix` — tham số: `periodId` (bắt buộc), `unit` (chỉ người thuộc đúng đơn vị đó, không gồm con), `unitId` (nhiều giá trị, gồm con), `specialtyId` (nhiều giá trị), `category` (nhiều giá trị), `q`, `includeClosed`, `page`, `size` (mặc định 100, tối đa 200).

Phản hồi: `{period, viewer, columns[], rows[], page, size, total}`.

- **Cột** (`columns`) có `mode`: `DIRECT` (Super Admin, giám khảo của nội dung đó: thêm, gỡ có hiệu lực ngay), `REQUEST` (Admin: gửi đề nghị), `VIEW` (nội dung đã đóng: chỉ xem). Super Admin thấy mọi nội dung của đợt, giám khảo nội dung mình, Admin mọi nội dung đang mở.
- **Hàng** (`rows`) là người, kèm `category` và `cells`. Chỉ lộ họ tên, cấp bậc, mã định danh, đơn vị (và đơn vị cha), chuyên ngành — không có ngày sinh, số điện thoại, tài khoản (P2).
- **Ô** (`cells`): `IN` đang tham gia, `IN_GRADED` đã có điểm (chỉ gỡ được ở trang nội dung kèm lý do), `PENDING_ADD`/`PENDING_REMOVE` có đề nghị chờ duyệt.
- Phạm vi danh sách: Super Admin và giám khảo thấy **toàn bộ** người `PERSON`, `ACTIVE`, có đơn vị; Admin chỉ người thuộc nhánh mình (P1).

`apply` đi qua đúng service của chức năng gốc (`ParticipantService`, `RequestService`) nên giữ mọi kiểm tra và nhật ký (P5): mỗi thay đổi có giao dịch ghi riêng, thay đổi lỗi được báo riêng, các thay đổi còn lại vẫn áp dụng. Admin gửi nhiều đề nghị cùng lúc chỉ tạo **một thông báo gộp** cho mỗi nội dung (P6).

---

## 10. Thống kê

Tất cả tự giới hạn theo phạm vi. Quyền: Admin+ (riêng `/stats/class/{id}` còn cho giám khảo của nội dung đó).

Tham số lọc chung (`Filter`):

| Tham số | Mô tả |
|---|---|
| `kind` | `PERSON` (mặc định) hoặc `UNIT`. **Không trộn** hai loại: điểm cá nhân và điểm đơn vị luôn tách riêng |
| `periodId` | Đợt |
| `classId` | Nội dung kiểm tra |
| `unitId` | Đơn vị (nhiều giá trị, gồm đơn vị con; ngoài phạm vi `404`) |
| `from`, `to` | Khoảng ngày kiểm tra |

Thống kê theo đơn vị dùng đơn vị **lúc vào nội dung** (`unit_id_snapshot`) và bỏ qua người đã bị gỡ khỏi nội dung, nên không đổi khi người chuyển đơn vị.

| Method | Route | Mô tả |
|---|---|---|
| GET | `/stats/overview` | Số liệu dashboard: tổng, theo đơn vị, theo đợt (6 đợt gần nhất), theo nội dung (`byContent`: 8 nội dung gần nhất). Tham số `group` |
| GET | `/stats/grade-by-unit` | Số lượng và tỷ lệ xếp loại theo đơn vị. Tham số `group` |
| GET | `/stats/unit-scores` | Điểm trung bình, cao nhất, thấp nhất theo đơn vị, xếp theo điểm trung bình giảm dần (giao diện không dùng) |
| GET | `/stats/not-tested` | Danh sách chưa kiểm tra và không kiểm tra kèm lý do. Tham số `state` (`ALL`, `ABSENT`, `NOT_TESTED`), `page`, `size` |
| GET | `/stats/class/{id}` | Thống kê một nội dung kèm phân bố điểm 10 khoảng. Tham số `kind`. Admin chỉ thấy người thuộc nhánh mình |

`group`: `unit` (mặc định, theo đơn vị lúc vào nội dung) hoặc `top` (gộp đơn vị cấp 2 về cấp 1).

Mỗi nhóm trả `Counts`: tổng lượt, đã có điểm, không kiểm tra (có lý do), chưa kiểm tra, số lượng từng xếp loại (`FAIL`, `PASS`, `GOOD`, `EXCELLENT`, đếm theo cột `result.grade`), điểm trung bình ×10, cao nhất, thấp nhất. Tỷ lệ phần trăm do giao diện tính từ số lượng (S2).

Lưu ý với `/stats/not-tested`: ngoài phạm vi theo đơn vị lúc vào, còn yêu cầu **đơn vị hiện tại** của người đó nằm trong phạm vi người xem, nên Admin không thấy họ tên người đã chuyển khỏi nhánh (S5).

---

## 11. Thông báo

| Method | Route | Ai | Mô tả |
|---|---|---|---|
| GET | `/notifications` | Đã đăng nhập | Thông báo của chính mình. Tham số `unread`, `page`, `size` |
| GET | `/notifications/unread-count` | Đã đăng nhập | `{count}`. Giao diện gọi khoảng 30 giây một lần, chỉ khi tab đang hiển thị và người dùng có thao tác (R26) |
| POST | `/notifications/{id}/read` | Đã đăng nhập | Đánh dấu đã đọc. `204` |
| POST | `/notifications/read-all` | Đã đăng nhập | Đọc hết. Trả `{count}` |

`NotificationView`: `{id, type, title, body, refType, refId, createdAt, readAt}`. `type`: `REQUEST_CREATED`, `REQUEST_APPROVED`, `REQUEST_REJECTED`. `refType = "class_request"`, `refId` là id đề nghị.

---

## 12. Quản trị hệ thống

Chỉ Super Admin. Sao lưu tự động hằng ngày là tác vụ nội bộ, **không phải API**.

| Method | Route | Mô tả |
|---|---|---|
| GET | `/audit-logs` | Nhật ký thao tác. Tham số `actorId`, `actor`, `action`, `from`, `to`, `page`, `size`. Chỉ thêm, không sửa, không xóa |
| GET | `/admin/health` | Tình trạng: phiên bản ứng dụng và schema, Java, thời điểm khởi động, thư mục dữ liệu, dung lượng DB và WAL, ổ đĩa còn trống, thư mục sao lưu, bản sao lưu gần nhất, danh sách cảnh báo (chưa có hoặc quá 2 ngày chưa sao lưu, sao lưu cùng ổ đĩa với dữ liệu, ổ sắp đầy) |
| GET | `/admin/backups` | `{settings {time, keep, dir, effectiveDir, defaultDir}, items [{name, kind, createdAt, sizeBytes}]}`: mọi bản sao lưu (tự động, thủ công, trước nâng cấp), mới nhất trước |
| POST | `/admin/backup` | Tạo ngay một bản thủ công. `201` trả `{name, kind, createdAt, sizeBytes}`; lỗi `400 BACKUP_FAILED` |
| GET | `/admin/backups/{name}` | Tải file sao lưu. Tên phải đúng mẫu `trainingstats-<loại>-yyyyMMdd-HHmmss….db`, không thì `404` |
| PUT | `/admin/backup-settings` | Body `{time: "H:mm", keep: 1..30, dir}`. `dir` rỗng là mặc định; thư mục phải tạo và ghi được ngay, không thì `400` theo ô |

Mọi thao tác trên đều ghi nhật ký (`BACKUP_CREATED`, `BACKUP_FAILED`, `BACKUP_DOWNLOADED`, `SETTINGS_UPDATED`).

---

## 13. Nhập, xuất Excel

### 13.1 Quy ước chung

| Mục | Nội dung |
|---|---|
| Gửi | `multipart/form-data`, trường `file` (`.xlsx`), tham số `dryRun` (mặc định `true`) |
| Giới hạn | Tối đa 5 MB và 5.000 dòng dữ liệu mỗi sheet |
| Hai bước | Gọi với `dryRun=true` để xem lỗi theo dòng, rồi gọi lại với `dryRun=false` để ghi |
| Ghi tất cả hoặc không | Có lỗi dữ liệu thì không ghi gì. Khi ghi, kiểm lại toàn bộ trong giao dịch ghi nên dữ liệu đổi giữa chừng vẫn bị bắt |
| Kiểm quyền trước | Người không có quyền nhận `403` (người dùng, nội dung) hoặc `404` (điểm) dù gửi gì |
| Mã trạng thái | `200` kể cả khi có lỗi dữ liệu (`written = false`). `400` khi file hỏng, sai định dạng, quá lớn, thiếu cột, quá 5.000 dòng |
| Nhật ký | Mỗi lần nhập một dòng tổng (`USERS_IMPORTED`, `CLASSES_IMPORTED`, `RESULTS_IMPORTED`, `ASSIGNMENTS_IMPORTED`) cộng nhật ký từng bản ghi có `source: IMPORT` |
| Cột | Tìm theo tên tiêu đề (không phân biệt hoa thường, dấu, dấu `*`); đổi thứ tự cột vẫn đọc được |
| File mẫu | Sheet `HuongDan` luôn đứng đầu; ô chọn lấy từ DB lúc tải; ràng buộc Stop; máy chủ vẫn kiểm lại mọi giá trị |

`ImportReport`:

```json
{
  "dryRun": true, "written": false, "totalRows": 1000, "errorCount": 2,
  "errors": [ { "sheet": "NguoiDung", "row": 15, "column": "Đơn vị", "message": "Đơn vị không thuộc phạm vi của bạn." } ],
  "rows":   [ { "sheet": "NguoiDung", "row": 2, "action": "CREATE", "label": "Nguyễn Văn An", "detail": "..." } ],
  "counts": { "CREATE": 998, "UPDATE": 0 }
}
```

`row` là số dòng như Excel hiển thị (tiêu đề là dòng 1). File lỗi tải về là CSV (UTF-8 có BOM) do trình duyệt tạo từ báo cáo, không có API riêng. Mã lỗi file: `INVALID_FILE`, `FILE_TOO_LARGE`, `INVALID_TEMPLATE`, `TOO_MANY_ROWS`, `FILE_REQUIRED`.

### 13.2 Danh sách endpoint

| Method | Route | Ai | Dữ liệu |
|---|---|---|---|
| GET | `/users/import-template` | Admin+ | Mẫu người dùng |
| POST | `/users/import` | Admin+ | Nhập người dùng (E4): khớp theo mã định danh (có thì cập nhật, không thì tạo; để trống mã thì tự cấp). Ô trống ở cột không bắt buộc giữ giá trị cũ. Mỗi dòng phải có đơn vị trong phạm vi. Cột Tài khoản, Quyền cấp tài khoản; Admin thiếu tài khoản báo lỗi ngay ở bước kiểm tra |
| GET | `/classes/import-template` | Super Admin | Mẫu nội dung (sheet `NoiDung`, `NguoiHoc`) |
| POST | `/classes/import` | Super Admin | Nhập nhiều nội dung kèm người học (E5). Đợt chọn trong file theo từng nội dung (phải đang mở) |
| GET | `/classes/{classId}/results/import-template` | Giám khảo | Mẫu điểm một nội dung: danh sách đầy đủ của nội dung, người đã có điểm hiện sẵn và khóa ô (E6) |
| POST | `/classes/{classId}/results/import` | Giám khảo | Nhập điểm một nội dung: không ghi đè điểm đã có, ô trống bỏ qua, không tự chấm cho mình |
| GET | `/classes/{classId}/results/export` | Người xem được nội dung | **Xuất** điểm ra Excel (E11), theo phạm vi người xem (như `GET /participants`) |
| GET | `/periods/{id}/results/import-template` | Super Admin | Mẫu chấm cả đợt: mỗi đơn vị lúc tham gia một sheet, cột là mọi nội dung của đợt (E12) |
| POST | `/periods/{id}/results/import` | Super Admin | Nhập điểm nhiều nội dung một lần. Ô được nhập nền vàng; ô đã có điểm nền xám khóa; ô người không có trong nội dung gạch chéo khóa |
| GET | `/assignments/template?periodId=` | Như bảng phân công | Mẫu phân công (E10): mỗi đơn vị một sheet, cột là nội dung đang mở, ô `x` |
| POST | `/assignments/import?periodId=` | Như bảng phân công | Nhập phân công: `x` ở người chưa tham gia là thêm, ô trống ở người đang tham gia là gỡ. Super Admin, giám khảo làm ngay; Admin gửi đề nghị |

---

## 14. Phụ lục

### 14.1 Mã lỗi nghiệp vụ

| Mã HTTP | `code` | Khi nào |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Dữ liệu sai; `fieldErrors` chỉ rõ trường |
| 400 | `BAD_REQUEST`, `INVALID_CHANGE`, `TARGET_REQUIRED` | Yêu cầu sai, thay đổi phân công không hợp lệ, thiếu đối tượng |
| 400 | `NO_ACCOUNT`, `SELF_RESET`, `SUPER_ADMIN_NO_UNIT`, `UNIT_RECORD` | Thiếu tài khoản khi cấp quyền; đặt lại mật khẩu cho chính mình; Super Admin không có đơn vị; bản ghi đại diện đơn vị không sửa được |
| 400 | `INVALID_FILE`, `INVALID_TEMPLATE`, `FILE_REQUIRED`, `FILE_TOO_LARGE`, `TOO_MANY_ROWS`, `BACKUP_FAILED` | Nhập Excel, sao lưu |
| 401 | `UNAUTHENTICATED`, `INVALID_CREDENTIALS`, `ACCOUNT_LOCKED` | Đăng nhập |
| 403 | `FORBIDDEN`, `CSRF_INVALID` | Sai vai trò; thiếu hoặc sai CSRF |
| 403 | `SELF_ACTION`, `SELF_GRADING`, `NOT_TEACHER`, `NOT_REQUESTER`, `HAS_RESULT` | Tự khóa, tự xóa, tự hạ quyền; giám khảo tự chấm; không phải giám khảo của nội dung; không phải người đề nghị; gỡ người đã có điểm không phải Super Admin |
| 404 | `NOT_FOUND` | Không có hoặc ngoài phạm vi |
| 409 | `VERSION_CONFLICT` | Sai `version` |
| 409 | `DUPLICATE`, `DUPLICATE_NAME`, `DUPLICATE_REQUEST` | Trùng mã định danh, tài khoản, tên, đề nghị chờ |
| 409 | `ALREADY_GRADED`, `ALREADY_IN_CLASS`, `ALREADY_REMOVED`, `NOT_IN_CLASS` | Điểm, người học |
| 409 | `CLASS_CLOSED`, `PERIOD_CLOSED`, `PERIOD_HAS_CLASSES`, `CLASS_HAS_RESULTS` | Trạng thái đợt, nội dung |
| 409 | `NOT_PENDING`, `TARGET_INACTIVE` | Đề nghị đã xử lý; đối tượng không còn hoạt động |
| 409 | `LAST_SUPER_ADMIN`, `NOT_SUPER_ADMIN`, `SUPER_ADMIN_EXISTS` | Luôn còn ít nhất một Super Admin |
| 409 | `NO_CONTENT`, `NO_PARTICIPANTS`, `NO_PEOPLE` | Đợt chưa có nội dung, nội dung chưa có người, không có người trong phạm vi (khi tạo file mẫu) |
| 500 | `INTERNAL_ERROR` | Lỗi lạ |

### 14.2 Hành động ghi nhật ký

| Nhóm | `action` |
|---|---|
| Phiên | `LOGIN_SUCCESS`, `LOGIN_FAILED`, `LOGOUT`, `PASSWORD_CHANGED`, `PASSWORD_RESET` |
| Người dùng | `USER_CREATED`, `USER_UPDATED`, `USER_STATUS_CHANGED`, `USER_ROLE_CHANGED`, `USER_MOVED`, `UNIT_RECORD_CREATED` |
| Đơn vị, danh mục | `UNIT_CREATED`, `UNIT_UPDATED`, `UNIT_STATUS_CHANGED`, `CATALOG_CREATED`, `CATALOG_UPDATED`, `CATALOG_STATUS_CHANGED` |
| Nội dung | `CLASS_CREATED`, `CLASS_UPDATED`, `CLASS_STATUS_CHANGED`, `CLASS_DELETED`, `CLASS_TEACHERS_CHANGED` |
| Người học, đề nghị | `PARTICIPANT_ADDED`, `PARTICIPANT_REMOVED`, `PARTICIPANT_ABSENCE_SET`, `REQUEST_CREATED`, `REQUEST_APPROVED`, `REQUEST_REJECTED`, `REQUEST_CANCELLED` |
| Điểm | `RESULT_CREATED`, `RESULT_UPDATED` |
| Nhập Excel | `USERS_IMPORTED`, `CLASSES_IMPORTED`, `RESULTS_IMPORTED`, `ASSIGNMENTS_IMPORTED` |
| Hệ thống | `BACKUP_CREATED`, `BACKUP_AUTO_CREATED`, `BACKUP_FAILED`, `BACKUP_DOWNLOADED`, `SETTINGS_UPDATED` |

Mật khẩu, hash, token không bao giờ ghi vào nhật ký hay phản hồi API.

### 14.3 Tổng hợp quyền theo nhóm route

| Nhóm | Super Admin | Admin | Giám khảo (user thường) | User |
|---|---|---|---|---|
| `/auth`, `/me` | ✅ | ✅ | ✅ | ✅ |
| `/units/tree` | ✅ | ✅ | ❌ | ❌ |
| Ghi `/units`, `/catalogs`, `/periods`, tạo/sửa `/classes` | ✅ | ❌ | ❌ | ❌ |
| `/users` (ghi, danh sách) | ✅ | 🔶 nhánh | ❌ | ❌ |
| `/users/{id}`, `/history`, `/print` | ✅ | 🔶 nhánh | ❌ | 🔶 chính mình |
| `/classes` (đọc) | ✅ tất cả | ✅ tất cả, người học trong nhánh | 🔶 nội dung mình dạy | 🔶 nội dung mình tham gia |
| `/classes/{id}/candidates`, thêm/gỡ người, lý do vắng, nhập điểm | ✅ | ❌ | 🔶 nội dung mình | ❌ |
| `/classes/{id}/requests` (tạo) | ❌ (không cần) | ✅ | ❌ | ❌ |
| `/requests` duyệt, từ chối | ✅ | ❌ | 🔶 nội dung mình | ❌ |
| `PUT /results/{rid}`, `/results/{rid}/history` | ✅ | ❌ | ❌ | ❌ |
| `/assignments` | ✅ | 🔶 nhánh, gửi đề nghị | 🔶 cột nội dung mình | ❌ |
| `/stats` | ✅ | 🔶 nhánh | 🔶 `/stats/class/{id}` nội dung mình | ❌ |
| `/notifications` | ✅ | ✅ | ✅ | ✅ |
| `/audit-logs`, `/admin/*`, chấm cả đợt | ✅ | ❌ | ❌ | ❌ |

Mọi endpoint mới đều bị `EndpointAccessScanTest` quét tự động: user thường gọi được thì phải khai báo kèm lý do trong `USER_ALLOWED`.
