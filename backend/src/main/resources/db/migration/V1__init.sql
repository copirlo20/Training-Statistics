-- Schema và dữ liệu ban đầu, gộp từ các migration V1 đến V6 cũ (ngày 2026-11-19) để cài mới từ đầu. Thiết kế chi tiết và
-- lý do: docs/ARCHITECTURE.md mục 2. Không có bảng subject: trường Môn kiểm tra đã bỏ. Lớp (exam_class) gọi là nội dung
-- kiểm tra trên giao diện, giáo viên (class_teacher) gọi là giám khảo; tên trong DB giữ nguyên.
-- Thời gian lưu dạng TEXT ISO-8601 theo UTC. Khóa ngoại luôn ON DELETE RESTRICT (mặc định NO ACTION cũng chặn xóa).
-- Các bảng nghiệp vụ không bao giờ bị xóa cứng: trigger trg_*_no_delete chặn ở tầng DB.

-- ======================================================================
-- Danh mục
-- ======================================================================

CREATE TABLE unit (
    id         INTEGER PRIMARY KEY,
    code       TEXT    NOT NULL UNIQUE,
    name       TEXT    NOT NULL,
    parent_id  INTEGER REFERENCES unit (id),
    level      INTEGER NOT NULL CHECK (level IN (1, 2)),
    sort_order INTEGER NOT NULL DEFAULT 0,
    active     INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
    created_at TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    created_by INTEGER REFERENCES app_user (id),
    updated_at TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_by INTEGER REFERENCES app_user (id),
    -- Cấp 1: [xxxx]. Cấp 2: [mã cha].[xxxx]. Cấp 0 (Super Admin) không có bản ghi đơn vị.
    CHECK ((level = 1 AND parent_id IS NULL AND length(code) = 4 AND instr(code, '.') = 0)
        OR (level = 2 AND parent_id IS NOT NULL AND length(code) = 9 AND substr(code, 5, 1) = '.'))
);

CREATE INDEX ix_unit_parent ON unit (parent_id);

-- Cấp 2 phải nằm dưới một đơn vị cấp 1 và mã phải bắt đầu bằng mã cha cộng dấu chấm.
CREATE TRIGGER trg_unit_parent_check
    BEFORE INSERT ON unit
    WHEN NEW.level = 2
BEGIN
    SELECT RAISE(ABORT, 'Don vi cap 2 phai thuoc don vi cap 1 va ma phai bat dau bang ma cha')
    WHERE NOT EXISTS (SELECT 1 FROM unit p
                      WHERE p.id = NEW.parent_id
                        AND p.level = 1
                        AND substr(NEW.code, 1, 5) = p.code || '.');
END;

-- Mã, cấp, đơn vị cha không đổi sau khi tạo (mã chứa đường dẫn, xem ARCHITECTURE R14).
CREATE TRIGGER trg_unit_immutable
    BEFORE UPDATE OF code, parent_id, level ON unit
    WHEN NEW.code IS NOT OLD.code OR NEW.parent_id IS NOT OLD.parent_id OR NEW.level IS NOT OLD.level
BEGIN
    SELECT RAISE(ABORT, 'Khong doi duoc ma, cap, don vi cha cua don vi');
END;

CREATE TABLE rank (
    id         INTEGER PRIMARY KEY,
    name       TEXT    NOT NULL UNIQUE,
    symbol     TEXT    NOT NULL UNIQUE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    active     INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
    created_at TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE TABLE position (
    id         INTEGER PRIMARY KEY,
    name       TEXT    NOT NULL UNIQUE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    active     INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
    created_at TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE TABLE specialty (
    id         INTEGER PRIMARY KEY,
    name       TEXT    NOT NULL UNIQUE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    active     INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
    created_at TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE TABLE absence_reason (
    id         INTEGER PRIMARY KEY,
    name       TEXT    NOT NULL UNIQUE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    active     INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
    created_at TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

-- ======================================================================
-- Người dùng
-- ======================================================================

-- kind = PERSON: một con người. kind = UNIT: bản ghi đại diện cho một đơn vị để đưa vào lớp (không có tài khoản).
CREATE TABLE app_user (
    id            INTEGER PRIMARY KEY,
    kind          TEXT    NOT NULL DEFAULT 'PERSON' CHECK (kind IN ('PERSON', 'UNIT')),
    last_name     TEXT,
    first_name    TEXT,
    search_text   TEXT    NOT NULL,
    rank_id       INTEGER REFERENCES rank (id),
    position_id   INTEGER REFERENCES position (id),
    unit_id       INTEGER REFERENCES unit (id),
    username      TEXT COLLATE NOCASE UNIQUE,
    password_hash TEXT,
    -- 1 khi mật khẩu do người khác đặt (tạo hoặc cấp tài khoản, nhập Excel, đặt lại mật khẩu): phải tự đổi trước khi
    -- dùng chức năng khác. Chỉ chính chủ đổi mật khẩu mới về 0 (ARCHITECTURE A14).
    must_change_password INTEGER NOT NULL DEFAULT 0 CHECK (must_change_password IN (0, 1)),
    role          TEXT    NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN', 'SUPER_ADMIN')),
    status        TEXT    NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE', 'DELETED')),
    personal_code TEXT UNIQUE,
    -- Đối tượng (thêm ngày 2026-11-20): SQ sĩ quan, QNCN quân nhân chuyên nghiệp, HSQ-CS hạ sĩ quan - chiến sĩ.
    -- Cho NULL vì tài khoản Super Admin tạo lúc cài không thuộc đơn vị; máy chủ bắt buộc với mọi người có đơn vị.
    category      TEXT CHECK (category IS NULL OR category IN ('SQ', 'QNCN', 'HSQ-CS')),
    birth_date    TEXT,
    phone         TEXT,
    note          TEXT,
    last_login_at TEXT,
    version       INTEGER NOT NULL DEFAULT 0,
    created_at    TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    created_by    INTEGER REFERENCES app_user (id),
    updated_at    TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_by    INTEGER REFERENCES app_user (id),
    deleted_at    TEXT,
    deleted_by    INTEGER REFERENCES app_user (id),

    -- PERSON bắt buộc họ, tên, mã định danh.
    CHECK (kind <> 'PERSON' OR (last_name IS NOT NULL AND first_name IS NOT NULL AND personal_code IS NOT NULL)),
    -- UNIT: gắn một đơn vị, luôn là USER, không có thông tin cá nhân và không có tài khoản.
    CHECK (kind <> 'UNIT' OR (unit_id IS NOT NULL AND role = 'USER'
        AND last_name IS NULL AND first_name IS NULL AND personal_code IS NULL
        AND rank_id IS NULL AND position_id IS NULL AND category IS NULL
        AND username IS NULL AND password_hash IS NULL)),
    -- PERSON: Super Admin không thuộc đơn vị, mọi vai trò khác phải thuộc một đơn vị.
    CHECK (kind <> 'PERSON'
        OR (role = 'SUPER_ADMIN' AND unit_id IS NULL)
        OR (role <> 'SUPER_ADMIN' AND unit_id IS NOT NULL)),
    -- Admin và Super Admin bắt buộc có tài khoản và mật khẩu.
    CHECK (role = 'USER' OR (username IS NOT NULL AND password_hash IS NOT NULL)),
    -- Có mật khẩu thì phải có tài khoản.
    CHECK (password_hash IS NULL OR username IS NOT NULL),
    -- Xóa mềm: trạng thái DELETED đi đôi với thời điểm xóa.
    CHECK ((status = 'DELETED') = (deleted_at IS NOT NULL))
);

CREATE INDEX ix_app_user_unit_status ON app_user (unit_id, status);
CREATE INDEX ix_app_user_search ON app_user (search_text);
CREATE INDEX ix_app_user_kind ON app_user (kind);
CREATE INDEX ix_app_user_category ON app_user (category);
-- Mỗi đơn vị có đúng một bản ghi đại diện.
CREATE UNIQUE INDEX uq_app_user_unit_kind ON app_user (unit_id) WHERE kind = 'UNIT';

CREATE TABLE user_specialty (
    user_id      INTEGER NOT NULL REFERENCES app_user (id),
    specialty_id INTEGER NOT NULL REFERENCES specialty (id),
    PRIMARY KEY (user_id, specialty_id)
);

CREATE INDEX ix_user_specialty_specialty ON user_specialty (specialty_id);

-- ======================================================================
-- Đợt kiểm tra, lớp, người học
-- ======================================================================

CREATE TABLE exam_period (
    id         INTEGER PRIMARY KEY,
    name       TEXT    NOT NULL,
    start_date TEXT,
    end_date   TEXT,
    status     TEXT    NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
    note       TEXT,
    version    INTEGER NOT NULL DEFAULT 0,
    created_at TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    created_by INTEGER REFERENCES app_user (id),
    updated_at TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_by INTEGER REFERENCES app_user (id),
    deleted_at TEXT,
    deleted_by INTEGER REFERENCES app_user (id),
    CHECK (end_date IS NULL OR start_date IS NULL OR end_date >= start_date)
);

CREATE TABLE exam_class (
    id         INTEGER PRIMARY KEY,
    period_id  INTEGER NOT NULL REFERENCES exam_period (id),
    name       TEXT    NOT NULL,
    exam_date  TEXT,
    location   TEXT,
    status     TEXT    NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
    note       TEXT,
    version    INTEGER NOT NULL DEFAULT 0,
    created_at TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    created_by INTEGER REFERENCES app_user (id),
    updated_at TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_by INTEGER REFERENCES app_user (id),
    deleted_at TEXT,
    deleted_by INTEGER REFERENCES app_user (id)
);

CREATE INDEX ix_exam_class_period ON exam_class (period_id);
CREATE INDEX ix_exam_class_date ON exam_class (exam_date);

-- Giáo viên của lớp. Gán lại thì thay dòng, nên bảng này cho phép xóa dòng gán.
CREATE TABLE class_teacher (
    class_id   INTEGER NOT NULL REFERENCES exam_class (id),
    user_id    INTEGER NOT NULL REFERENCES app_user (id),
    created_at TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    created_by INTEGER REFERENCES app_user (id),
    PRIMARY KEY (class_id, user_id)
);

CREATE INDEX ix_class_teacher_user ON class_teacher (user_id);

CREATE TABLE class_request (
    id                INTEGER PRIMARY KEY,
    class_id          INTEGER NOT NULL REFERENCES exam_class (id),
    type              TEXT    NOT NULL CHECK (type IN ('ADD', 'REMOVE')),
    user_id           INTEGER NOT NULL REFERENCES app_user (id),
    requested_by      INTEGER NOT NULL REFERENCES app_user (id),
    requested_unit_id INTEGER NOT NULL REFERENCES unit (id),
    status            TEXT    NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    decided_by        INTEGER REFERENCES app_user (id),
    decided_at        TEXT,
    decision_note     TEXT,
    created_at        TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    CHECK ((status = 'PENDING') = (decided_at IS NULL))
);

CREATE INDEX ix_class_request_status_class ON class_request (status, class_id);
-- Không để hai đề nghị đang chờ giống hệt nhau.
CREATE UNIQUE INDEX uq_class_request_pending ON class_request (class_id, user_id, type) WHERE status = 'PENDING';

CREATE TABLE class_participant (
    id                 INTEGER PRIMARY KEY,
    class_id           INTEGER NOT NULL REFERENCES exam_class (id),
    user_id            INTEGER NOT NULL REFERENCES app_user (id),
    -- Đơn vị của người đó tại thời điểm vào lớp, dùng cho thống kê theo đơn vị.
    unit_id_snapshot   INTEGER NOT NULL REFERENCES unit (id),
    absence_reason_id  INTEGER REFERENCES absence_reason (id),
    absence_note       TEXT,
    removed_at         TEXT,
    removed_by         INTEGER REFERENCES app_user (id),
    removed_reason     TEXT,
    added_via          TEXT    NOT NULL DEFAULT 'DIRECT' CHECK (added_via IN ('DIRECT', 'REQUEST')),
    request_id         INTEGER REFERENCES class_request (id),
    created_at         TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    created_by         INTEGER REFERENCES app_user (id),
    updated_at         TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_by         INTEGER REFERENCES app_user (id),
    CHECK (added_via <> 'REQUEST' OR request_id IS NOT NULL),
    -- Đã gỡ thì phải có ai gỡ. Chưa gỡ thì không có thông tin gỡ.
    CHECK ((removed_at IS NULL AND removed_by IS NULL AND removed_reason IS NULL)
        OR (removed_at IS NOT NULL AND removed_by IS NOT NULL))
);

-- Một người chỉ vào một lớp một lần, nhưng thêm lại được sau khi bị gỡ.
CREATE UNIQUE INDEX uq_class_participant_active ON class_participant (class_id, user_id) WHERE removed_at IS NULL;
CREATE INDEX ix_class_participant_class ON class_participant (class_id);
CREATE INDEX ix_class_participant_user ON class_participant (user_id);
CREATE INDEX ix_class_participant_snapshot ON class_participant (unit_id_snapshot);

-- ======================================================================
-- Điểm
-- ======================================================================

-- Điểm lưu dạng số nguyên nhân 10 (6.5 lưu là 65). Xếp loại là cột tính tự động, không bao giờ lệch điểm.
-- participant_id UNIQUE bảo đảm mỗi người học chỉ có một điểm, kể cả khi hai giám khảo lưu cùng lúc.
CREATE TABLE result (
    id             INTEGER PRIMARY KEY,
    participant_id INTEGER NOT NULL UNIQUE REFERENCES class_participant (id),
    score_x10      INTEGER NOT NULL CHECK (score_x10 BETWEEN 0 AND 100),
    grade          TEXT GENERATED ALWAYS AS (
        CASE
            WHEN score_x10 < 50 THEN 'FAIL'
            WHEN score_x10 <= 65 THEN 'PASS'
            WHEN score_x10 <= 79 THEN 'GOOD'
            ELSE 'EXCELLENT'
        END
    ) VIRTUAL,
    graded_by      INTEGER NOT NULL REFERENCES app_user (id),
    graded_at      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    version        INTEGER NOT NULL DEFAULT 0,
    updated_at     TEXT,
    updated_by     INTEGER REFERENCES app_user (id)
);

CREATE INDEX ix_result_grade ON result (grade);

CREATE TABLE result_history (
    id            INTEGER PRIMARY KEY,
    result_id     INTEGER NOT NULL REFERENCES result (id),
    old_score_x10 INTEGER NOT NULL CHECK (old_score_x10 BETWEEN 0 AND 100),
    new_score_x10 INTEGER NOT NULL CHECK (new_score_x10 BETWEEN 0 AND 100),
    reason        TEXT    NOT NULL CHECK (length(trim(reason)) > 0),
    changed_by    INTEGER NOT NULL REFERENCES app_user (id),
    changed_at    TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX ix_result_history_result ON result_history (result_id);

-- ======================================================================
-- Hệ thống
-- ======================================================================

CREATE TABLE notification (
    id           INTEGER PRIMARY KEY,
    recipient_id INTEGER NOT NULL REFERENCES app_user (id),
    type         TEXT    NOT NULL,
    title        TEXT    NOT NULL,
    body         TEXT,
    ref_type     TEXT,
    ref_id       INTEGER,
    created_at   TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    read_at      TEXT
);

CREATE INDEX ix_notification_recipient ON notification (recipient_id, read_at);

-- Nhật ký chỉ thêm, không sửa, không xóa. Không đặt khóa ngoại tới app_user để dòng log không bao giờ bị chặn ghi
-- (đăng nhập thất bại với tài khoản không tồn tại vẫn phải ghi được). actor_username lưu bản sao tên.
CREATE TABLE audit_log (
    id             INTEGER PRIMARY KEY,
    at             TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    actor_id       INTEGER,
    actor_username TEXT,
    action         TEXT    NOT NULL,
    entity_type    TEXT,
    entity_id      INTEGER,
    unit_code      TEXT,
    before_json    TEXT,
    after_json     TEXT,
    ip             TEXT
);

CREATE INDEX ix_audit_log_at ON audit_log (at);
CREATE INDEX ix_audit_log_actor ON audit_log (actor_id, at);
CREATE INDEX ix_audit_log_action ON audit_log (action, at);

CREATE TABLE app_setting (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

-- ======================================================================
-- Chặn xóa cứng ở tầng DB (lớp bảo vệ thứ hai, sau quy tắc "chỉ xóa mềm" trong code)
-- ======================================================================

CREATE TRIGGER trg_unit_no_delete BEFORE DELETE ON unit
BEGIN SELECT RAISE(ABORT, 'Khong duoc xoa cung du lieu'); END;

CREATE TRIGGER trg_rank_no_delete BEFORE DELETE ON rank
BEGIN SELECT RAISE(ABORT, 'Khong duoc xoa cung du lieu'); END;

CREATE TRIGGER trg_position_no_delete BEFORE DELETE ON position
BEGIN SELECT RAISE(ABORT, 'Khong duoc xoa cung du lieu'); END;

CREATE TRIGGER trg_specialty_no_delete BEFORE DELETE ON specialty
BEGIN SELECT RAISE(ABORT, 'Khong duoc xoa cung du lieu'); END;

CREATE TRIGGER trg_absence_reason_no_delete BEFORE DELETE ON absence_reason
BEGIN SELECT RAISE(ABORT, 'Khong duoc xoa cung du lieu'); END;

CREATE TRIGGER trg_app_user_no_delete BEFORE DELETE ON app_user
BEGIN SELECT RAISE(ABORT, 'Khong duoc xoa cung du lieu'); END;

CREATE TRIGGER trg_exam_period_no_delete BEFORE DELETE ON exam_period
BEGIN SELECT RAISE(ABORT, 'Khong duoc xoa cung du lieu'); END;

CREATE TRIGGER trg_exam_class_no_delete BEFORE DELETE ON exam_class
BEGIN SELECT RAISE(ABORT, 'Khong duoc xoa cung du lieu'); END;

CREATE TRIGGER trg_class_request_no_delete BEFORE DELETE ON class_request
BEGIN SELECT RAISE(ABORT, 'Khong duoc xoa cung du lieu'); END;

CREATE TRIGGER trg_class_participant_no_delete BEFORE DELETE ON class_participant
BEGIN SELECT RAISE(ABORT, 'Khong duoc xoa cung du lieu'); END;

CREATE TRIGGER trg_result_no_delete BEFORE DELETE ON result
BEGIN SELECT RAISE(ABORT, 'Khong duoc xoa cung du lieu'); END;

CREATE TRIGGER trg_notification_no_delete BEFORE DELETE ON notification
BEGIN SELECT RAISE(ABORT, 'Khong duoc xoa cung du lieu'); END;

-- Bảng chỉ thêm: không sửa, không xóa.
CREATE TRIGGER trg_result_history_no_update BEFORE UPDATE ON result_history
BEGIN SELECT RAISE(ABORT, 'Bang chi duoc them, khong duoc sua'); END;

CREATE TRIGGER trg_result_history_no_delete BEFORE DELETE ON result_history
BEGIN SELECT RAISE(ABORT, 'Bang chi duoc them, khong duoc xoa'); END;

CREATE TRIGGER trg_audit_log_no_update BEFORE UPDATE ON audit_log
BEGIN SELECT RAISE(ABORT, 'Nhat ky chi duoc them, khong duoc sua'); END;

CREATE TRIGGER trg_audit_log_no_delete BEFORE DELETE ON audit_log
BEGIN SELECT RAISE(ABORT, 'Nhat ky chi duoc them, khong duoc xoa'); END;

-- ======================================================================
-- Dữ liệu ban đầu: danh mục (cấp bậc, chức vụ, chuyên ngành, lý do không kiểm tra)
-- ======================================================================

-- Dữ liệu danh mục theo danh sách ban đầu của chủ dự án. Lý do không kiểm tra do đề xuất, danh sách ban đầu chưa liệt kê.

INSERT INTO rank (name, symbol, sort_order) VALUES
    ('Đại tá', '4//', 1),
    ('Thượng tá', '3//', 2),
    ('Trung tá', '2//', 3),
    ('Thiếu tá', '1//', 4),
    ('Đại úy', '4/', 5),
    ('Thượng úy', '3/', 6),
    ('Trung úy', '2/', 7),
    ('Thiếu úy', '1/', 8),
    ('Thượng tá QNCN', '3//CN', 9),
    ('Trung tá QNCN', '2//CN', 10),
    ('Thiếu tá QNCN', '1//CN', 11),
    ('Đại úy QNCN', '4/CN', 12),
    ('Thượng úy QNCN', '3/CN', 13),
    ('Trung úy QNCN', '2/CN', 14),
    ('Thiếu úy QNCN', '1/CN', 15),
    ('Thượng sỹ', 'H3', 16),
    ('Trung sỹ', 'H2', 17),
    ('Hạ sỹ', 'H1', 18),
    ('Binh nhất', 'B1', 19),
    ('Binh nhì', 'B2', 20);

INSERT INTO position (name, sort_order) VALUES
    ('Lữ đoàn trưởng', 1),
    ('Chính ủy', 2),
    ('Phó Lữ đoàn trưởng - Tham mưu trưởng', 3),
    ('Phó Lữ đoàn trưởng', 4),
    ('Phó Chính ủy', 5),
    ('Phó Tham mưu trưởng', 6),
    ('Chủ nhiệm Chính trị', 7),
    ('Phó Chủ nhiệm Chính trị', 8),
    ('Chủ nhiệm Hậu cần - Kỹ thuật', 9),
    ('Phó Chủ nhiệm Hậu cần - Kỹ thuật', 10),
    ('Trưởng ban', 11),
    ('Thuyền trưởng', 12),
    ('Phó Thuyền trưởng', 13),
    ('Hải đội trưởng', 14),
    ('Phó Hải đội trưởng', 15),
    ('Chính trị viên', 16),
    ('Chính trị viên phó', 17),
    ('Trưởng ngành', 18),
    ('Phó Trưởng ngành', 19),
    ('Tập sự Trưởng ngành', 20),
    ('Nghiệp vụ', 21),
    ('Nghiệp vụ trưởng', 22),
    ('Trợ lý', 23),
    ('Tiểu đội trưởng', 24),
    ('Nhân viên', 25),
    ('Chiến sỹ', 26);

INSERT INTO specialty (name, sort_order) VALUES
    ('Hàng hải', 1),
    ('Tên lửa', 2),
    ('Pháo tàu', 3),
    ('Ngư lôi', 4),
    ('Thông tin', 5),
    ('Radar', 6),
    ('Sonar', 7),
    ('Không quân', 8),
    ('Hóa học', 9),
    ('Máy tàu', 10),
    ('Điện tàu', 11),
    ('Công nghệ thông tin', 12),
    ('Tài chính', 13),
    ('Xăng dầu', 14),
    ('Doanh trại', 15),
    ('Quân nhu', 16),
    ('Xe máy', 17),
    ('Quân y', 18),
    ('Tuyên huấn', 19),
    ('Cán bộ', 20),
    ('Tổ chức', 21),
    ('Chính trị', 22),
    ('Tham mưu', 23),
    ('Hậu cần - Kỹ thuật', 24);

INSERT INTO absence_reason (name, sort_order) VALUES
    ('Ốm', 1),
    ('Công tác', 2),
    ('Nghỉ phép', 3),
    ('Khác', 4);

-- ======================================================================
-- Dữ liệu ban đầu: cây đơn vị
-- ======================================================================

-- Cây đơn vị theo danh sách ban đầu của chủ dự án. Cấp 0 (Super Admin) không có đơn vị.
-- Mã đơn vị là mã tạm do hệ thống đánh số theo thứ tự trong danh sách: Cấp 1 là [xxxx], Cấp 2 là [mã cha].[xxxx].
-- Mã không đổi được sau khi triển khai thật. Nếu đơn vị có hệ mã chính thức thì sửa file này TRƯỚC khi triển khai.

INSERT INTO unit (code, name, parent_id, level, sort_order) VALUES
    ('0001', 'Thủ trưởng Lữ đoàn', NULL, 1, 1),
    ('0002', 'Phòng Tham mưu', NULL, 1, 2),
    ('0003', 'Phòng Chính trị', NULL, 1, 8),
    ('0004', 'Phòng Hậu cần - Kỹ thuật', NULL, 1, 9),
    ('0005', 'Tàu 011', NULL, 1, 10),
    ('0006', 'Tàu 012', NULL, 1, 11),
    ('0007', 'Tàu 015', NULL, 1, 12),
    ('0008', 'Tàu 016', NULL, 1, 13),
    ('0009', 'Tàu 26', NULL, 1, 14),
    ('0010', 'Hải đội 412', NULL, 1, 15),
    ('0011', 'Hải đội 414', NULL, 1, 20);

INSERT INTO unit (code, name, parent_id, level, sort_order) VALUES
    ('0002.0001', 'Ban Quân huấn', (SELECT id FROM unit WHERE code = '0002'), 2, 3),
    ('0002.0002', 'Ban Tác chiến', (SELECT id FROM unit WHERE code = '0002'), 2, 4),
    ('0002.0003', 'Ban Hành chính', (SELECT id FROM unit WHERE code = '0002'), 2, 5),
    ('0002.0004', 'Trung đội Thông tin', (SELECT id FROM unit WHERE code = '0002'), 2, 6),
    ('0002.0005', 'Cảng vụ', (SELECT id FROM unit WHERE code = '0002'), 2, 7),
    ('0010.0001', 'Tàu 375', (SELECT id FROM unit WHERE code = '0010'), 2, 16),
    ('0010.0002', 'Tàu 376', (SELECT id FROM unit WHERE code = '0010'), 2, 17),
    ('0010.0003', 'Tàu 381', (SELECT id FROM unit WHERE code = '0010'), 2, 18),
    ('0010.0004', 'Hải đội bộ', (SELECT id FROM unit WHERE code = '0010'), 2, 19),
    ('0011.0001', 'Tàu 371', (SELECT id FROM unit WHERE code = '0011'), 2, 21),
    ('0011.0002', 'Tàu 372', (SELECT id FROM unit WHERE code = '0011'), 2, 22),
    ('0011.0003', 'Tàu 373', (SELECT id FROM unit WHERE code = '0011'), 2, 23),
    ('0011.0004', 'Tàu 374', (SELECT id FROM unit WHERE code = '0011'), 2, 24),
    ('0011.0005', 'Hải đội bộ', (SELECT id FROM unit WHERE code = '0011'), 2, 25);

-- ======================================================================
-- Dữ liệu ban đầu: cấu hình mặc định
-- ======================================================================

-- Cấu hình mặc định, Super Admin có thể đổi sau. Đọc qua vn.trainingstats.settings.AppSettings.
INSERT INTO app_setting (key, value) VALUES
    ('password.min-length', '8'),
    ('session.timeout-minutes', '30'),
    ('backup.time', '02:00'),
    ('backup.keep', '5'),
    -- Rỗng nghĩa là dùng thư mục mặc định <thư mục dữ liệu>/../backups.
    ('backup.dir', '');
