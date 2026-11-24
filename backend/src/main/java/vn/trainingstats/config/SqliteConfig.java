package vn.trainingstats.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.trainingstats.common.SqliteExceptionTranslator;

/**
 * Hai pool kết nối tới cùng một file SQLite (xem docs/OPERATIONS-GUIDE.md phụ lục A, F1):
 * <ul>
 *   <li><b>Pool ghi</b>: đúng 1 kết nối, vì SQLite chỉ cho một người ghi tại một thời điểm. Các giao dịch ghi
 *       xếp hàng trong pool thay vì tranh khóa trong SQLite.</li>
 *   <li><b>Pool đọc</b>: vài kết nối mở ở chế độ chỉ đọc, đọc song song được nhờ WAL.</li>
 * </ul>
 * Giao dịch ghi (@Transactional không readOnly) đi pool ghi. Mọi thứ khác (giao dịch readOnly, truy vấn ngoài
 * giao dịch) đi pool đọc. Hệ quả có chủ đích: <b>ghi dữ liệu ngoài giao dịch ghi sẽ lỗi ngay</b>, không lặng lẽ chạy.
 */
@Configuration
public class SqliteConfig {

    /**
     * PRAGMA đặt trên URL để áp dụng cho mọi kết nối (SQLite đặt theo từng kết nối và mặc định tắt khóa ngoại).
     * transaction_mode=IMMEDIATE: giao dịch lấy khóa ghi ngay từ đầu, tránh SQLITE_BUSY khi "đọc rồi ghi".
     */
    private static final String WRITE_PRAGMAS =
            "?foreign_keys=on&journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL&transaction_mode=IMMEDIATE";

    /** open_mode=1 là SQLITE_OPEN_READONLY. */
    private static final String READ_PRAGMAS = "?open_mode=1&busy_timeout=5000";

    static final String WRITE = "write";
    static final String READ = "read";

    private static String dbUrl(String dataDir, String dbFile) throws IOException {
        Path dir = Path.of(dataDir).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        return "jdbc:sqlite:" + dir.resolve(dbFile).toString().replace('\\', '/');
    }

    @Bean(destroyMethod = "close")
    @FlywayDataSource
    public HikariDataSource writeDataSource(
            @Value("${app.data-dir}") String dataDir,
            @Value("${app.db-file}") String dbFile) throws IOException {
        HikariConfig config = new HikariConfig();
        config.setPoolName("sqlite-write");
        config.setJdbcUrl(dbUrl(dataDir, dbFile) + WRITE_PRAGMAS);
        config.setMaximumPoolSize(1);
        return new HikariDataSource(config);
    }

    @Bean(destroyMethod = "close")
    public HikariDataSource readDataSource(
            @Value("${app.data-dir}") String dataDir,
            @Value("${app.db-file}") String dbFile,
            @Value("${app.read-pool-size:4}") int size) throws IOException {
        HikariConfig config = new HikariConfig();
        config.setPoolName("sqlite-read");
        config.setJdbcUrl(dbUrl(dataDir, dbFile) + READ_PRAGMAS);
        config.setMaximumPoolSize(size);
        // sqlite-jdbc không cho đổi cờ read-only sau khi đã mở kết nối, nên Hikari phải đặt đúng giá trị của
        // open_mode, nếu không Hikari gọi setReadOnly(false) và kết nối bị từ chối.
        config.setReadOnly(true);
        // Không mở kết nối khi khởi động: file DB có thể chưa tồn tại cho tới khi pool ghi và Flyway tạo nó.
        config.setMinimumIdle(0);
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }

    @Bean
    @Primary
    public DataSource dataSource(@Qualifier("writeDataSource") DataSource write,
                                 @Qualifier("readDataSource") DataSource read) {
        AbstractRoutingDataSource routing = new AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
                boolean writeTx = TransactionSynchronizationManager.isActualTransactionActive()
                        && !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
                return writeTx ? WRITE : READ;
            }
        };
        routing.setTargetDataSources(Map.of(WRITE, write, READ, read));
        routing.setDefaultTargetDataSource(read);
        routing.afterPropertiesSet();
        // Lazy: chỉ lấy kết nối thật ở câu lệnh đầu tiên, lúc đó đã biết giao dịch có readOnly hay không.
        return new LazyConnectionDataSourceProxy(routing);
    }

    @Bean
    public SqliteExceptionTranslator sqliteExceptionTranslator() {
        return new SqliteExceptionTranslator();
    }

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource, SqliteExceptionTranslator translator) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setExceptionTranslator(translator);
        return template;
    }

    @Bean
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(JdbcTemplate jdbcTemplate) {
        return new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource, SqliteExceptionTranslator translator) {
        JdbcTransactionManager manager = new JdbcTransactionManager(dataSource);
        manager.setExceptionTranslator(translator);
        return manager;
    }
}
