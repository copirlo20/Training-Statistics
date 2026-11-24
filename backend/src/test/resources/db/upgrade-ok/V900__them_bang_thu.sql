-- Migration giả lập cho UpgradeBackupTest (không nằm trong db/migration nên không chạy ở ứng dụng).
CREATE TABLE upgrade_probe (id INTEGER PRIMARY KEY, note TEXT);
