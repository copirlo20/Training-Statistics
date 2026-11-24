CREATE TABLE spike_unit (
    id   INTEGER PRIMARY KEY,
    code TEXT NOT NULL UNIQUE
);

CREATE TABLE spike_participant (
    id         INTEGER PRIMARY KEY,
    class_id   INTEGER NOT NULL,
    user_id    INTEGER NOT NULL,
    unit_id    INTEGER NOT NULL REFERENCES spike_unit (id) ON DELETE RESTRICT,
    removed_at TEXT
);

CREATE UNIQUE INDEX uq_spike_participant_active
    ON spike_participant (class_id, user_id)
    WHERE removed_at IS NULL;

CREATE TABLE spike_result (
    id             INTEGER PRIMARY KEY,
    participant_id INTEGER NOT NULL UNIQUE REFERENCES spike_participant (id),
    score_x10      INTEGER NOT NULL CHECK (score_x10 BETWEEN 0 AND 100),
    grade          TEXT GENERATED ALWAYS AS (
        CASE
            WHEN score_x10 < 50 THEN 'FAIL'
            WHEN score_x10 <= 65 THEN 'PASS'
            WHEN score_x10 <= 79 THEN 'GOOD'
            ELSE 'EXCELLENT'
        END
    ) VIRTUAL
);

CREATE INDEX ix_spike_result_grade ON spike_result (grade);

CREATE TRIGGER trg_spike_participant_no_delete
    BEFORE DELETE ON spike_participant
BEGIN
    SELECT RAISE(ABORT, 'Khong duoc xoa cung');
END;

CREATE TABLE spike_audit (
    id     INTEGER PRIMARY KEY,
    action TEXT NOT NULL
);

CREATE TRIGGER trg_spike_audit_no_update
    BEFORE UPDATE ON spike_audit
BEGIN
    SELECT RAISE(ABORT, 'audit chi duoc them');
END;

CREATE TRIGGER trg_spike_audit_no_delete
    BEFORE DELETE ON spike_audit
BEGIN
    SELECT RAISE(ABORT, 'audit chi duoc them');
END;
