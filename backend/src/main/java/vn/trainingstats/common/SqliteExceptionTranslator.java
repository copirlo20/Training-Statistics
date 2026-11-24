package vn.trainingstats.common;

import java.sql.SQLException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.lang.Nullable;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

/**
 * Spring không có bảng mã lỗi cho SQLite nên mặc định mọi lỗi ràng buộc thành
 * UncategorizedSQLException
 * (docs/OPERATIONS-GUIDE.md phụ lục A, F2). Lớp này đọc mã lỗi gốc của SQLite và dịch sang
 * ngoại lệ đúng loại, để code có thể
 * bắt DuplicateKeyException khi chấm điểm trùng, v.v.
 */
public class SqliteExceptionTranslator implements SQLExceptionTranslator {

    private final SQLExceptionTranslator fallback;

    public SqliteExceptionTranslator() {
        this.fallback = new SQLErrorCodeSQLExceptionTranslator();
    }

    @Override
    public DataAccessException translate(@Nullable String task, @Nullable String sql, SQLException ex) {
        SQLiteException sqlite = findSqlite(ex);
        if (sqlite != null) {
            DataAccessException translated = map(task, sql, ex, sqlite.getResultCode());
            if (translated != null) {
                return translated;
            }
        }
        return fallback.translate(task != null ? task : "", sql != null ? sql : "", ex);
    }

    private static DataAccessException map(String task, String sql, SQLException ex, SQLiteErrorCode code) {
        String message = task + "; SQL [" + sql + "]; " + ex.getMessage();
        String name = code.name();
        if (code == SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE || code == SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY) {
            return new DuplicateKeyException(message, ex);
        }
        if (name.startsWith("SQLITE_CONSTRAINT")) {
            return new DataIntegrityViolationException(message, ex);
        }
        if (name.startsWith("SQLITE_BUSY") || name.startsWith("SQLITE_LOCKED")) {
            return new CannotAcquireLockException(message, ex);
        }
        if (name.startsWith("SQLITE_READONLY")) {
            return new InvalidDataAccessApiUsageException(
                    "Ghi dữ liệu ngoài giao dịch ghi (@Transactional không readOnly): " + message, ex);
        }
        return null;
    }

    private static SQLiteException findSqlite(Throwable ex) {
        Throwable t = ex;
        while (t != null) {
            if (t instanceof SQLiteException s) {
                return s;
            }
            t = t.getCause();
        }
        return null;
    }
}
