package vn.trainingstats.common.excel;

import org.springframework.transaction.support.TransactionTemplate;

/**
 * Quy trình chung của mọi lần nhập Excel: kiểm tra (dryRun) chỉ lập kế hoạch; ghi thật thì lập kế hoạch rồi ghi trong
 * một giao dịch. Có lỗi ở bước nào (lập kế hoạch hoặc ghi) thì hủy cả giao dịch: ghi tất cả hoặc không ghi gì.
 * Đọc file làm trước, ngoài giao dịch ghi.
 */
public final class ImportRunner {

    /** Kiểm từng dòng, ghi lỗi và dòng xem trước vào báo cáo, trả kế hoạch ghi. */
    @FunctionalInterface
    public interface Planner<P> {
        P plan(ImportReport.Builder report);
    }

    /** Ghi kế hoạch trong giao dịch đang mở. Lỗi phát sinh khi ghi thì thêm vào báo cáo, người chạy hủy giao dịch. */
    @FunctionalInterface
    public interface Writer<P> {
        void write(P planned, ImportReport.Builder report);
    }

    private ImportRunner() {
    }

    public static <P> ImportReport run(TransactionTemplate tx, boolean dryRun, Planner<P> planner, Writer<P> writer) {
        if (dryRun) {
            ImportReport.Builder report = new ImportReport.Builder();
            planner.plan(report);
            return report.build(true, false);
        }
        return tx.execute(status -> {
            ImportReport.Builder report = new ImportReport.Builder();
            P planned = planner.plan(report);
            if (!report.hasErrors()) {
                writer.write(planned, report);
            }
            if (report.hasErrors()) {
                status.setRollbackOnly();
                return report.build(false, false);
            }
            return report.build(false, true);
        });
    }
}
