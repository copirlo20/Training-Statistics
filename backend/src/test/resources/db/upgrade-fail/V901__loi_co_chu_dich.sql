-- Migration lỗi có chủ đích cho UpgradeBackupTest.
CREATE TABLE upgrade_broken (id INTEGER PRIMARY KEY);
INSERT INTO bang_khong_ton_tai (x) VALUES (1);
