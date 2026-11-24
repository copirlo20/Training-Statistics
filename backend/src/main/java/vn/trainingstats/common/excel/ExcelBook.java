package vn.trainingstats.common.excel;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.multipart.MultipartFile;
import vn.trainingstats.common.ApiException;
import vn.trainingstats.common.VietnameseText;

/**
 * File Excel tải lên để nhập (R7). Chỉ nhận .xlsx, tối đa {@link #MAX_BYTES} và {@link #MAX_ROWS} dòng dữ liệu mỗi
 * sheet. Dòng tiêu đề cột là dòng đầu tiên (trong {@link #HEADER_SCAN_ROWS} dòng đầu) có đủ các cột bắt buộc, nên file
 * mẫu có khối tiêu đề, thông tin phía trên vẫn đọc được; cột tìm theo tên tiêu đề (không phân biệt hoa thường, dấu, dấu *), nên đổi thứ tự cột
 * hay thêm cột ghi chú vẫn đọc được. Đọc file là việc chậm: luôn mở và đọc xong TRƯỚC khi vào giao dịch ghi.
 */
public final class ExcelBook implements AutoCloseable {

    public static final long MAX_BYTES = 5L * 1024 * 1024;
    public static final int MAX_ROWS = 5000;
    /** Số dòng đầu được dò để tìm dòng tiêu đề cột (file mẫu có khối tiêu đề và thông tin phía trên). */
    static final int HEADER_SCAN_ROWS = 15;

    /** Cột cần đọc. {@code required}: file phải có cột này (giá trị từng ô bắt buộc hay không do service kiểm). */
    public record Column(String key, String header, boolean required) {
    }

    /** Một dòng dữ liệu. {@code number} là số dòng như Excel hiển thị (dòng tiêu đề là 1). */
    public record DataRow(int number, Map<String, String> cells) {
        public String get(String key) {
            return cells.get(key);
        }
    }

    private static final byte[] ZIP_MAGIC = {'P', 'K', 3, 4};
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final Pattern DMY = Pattern.compile("(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4})");

    private final Workbook workbook;
    private final String fileName;

    private ExcelBook(Workbook workbook, String fileName) {
        this.workbook = workbook;
        this.fileName = fileName;
    }

    public static ExcelBook open(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("FILE_REQUIRED", "Chọn file Excel (.xlsx) để nhập.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw ApiException.badRequest("FILE_TOO_LARGE", "File quá lớn. Tối đa 5 MB.");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw invalidFile();
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw invalidFile();
        }
        if (bytes.length < ZIP_MAGIC.length) {
            throw invalidFile();
        }
        for (int i = 0; i < ZIP_MAGIC.length; i++) {
            if (bytes[i] != ZIP_MAGIC[i]) {
                throw invalidFile();
            }
        }
        try {
            // POI tự chặn file nén bất thường (zip bomb) qua ZipSecureFile.
            return new ExcelBook(new XSSFWorkbook(new ByteArrayInputStream(bytes)), name);
        } catch (Exception | OutOfMemoryError e) {
            throw invalidFile();
        }
    }

    public String fileName() {
        return fileName;
    }

    public boolean hasSheet(String name) {
        return workbook.getSheet(name) != null;
    }

    /** Tên các sheet đang hiện (bỏ sheet ẩn như danh mục), theo thứ tự trong file. */
    private List<String> visibleSheetNames() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            if (!workbook.isSheetHidden(i) && !workbook.isSheetVeryHidden(i)) {
                out.add(workbook.getSheetName(i));
            }
        }
        return out;
    }

    /** Dòng của mọi sheet dữ liệu trong một file mỗi đơn vị một sheet; {@code sheets} giữ thứ tự sheet trong file. */
    public record DataSheets(String fileName, Map<String, List<DataRow>> sheets) {
    }

    /** Mở file, đọc mọi sheet dữ liệu đang hiện (bỏ sheet hướng dẫn) rồi đóng file. */
    public static DataSheets readDataSheets(MultipartFile file, List<Column> columns) {
        try (ExcelBook book = open(file)) {
            return new DataSheets(book.fileName(), book.rowsOfDataSheets(columns));
        }
    }

    private Map<String, List<DataRow>> rowsOfDataSheets(List<Column> columns) {
        Map<String, List<DataRow>> out = new LinkedHashMap<>();
        for (String name : visibleSheetNames()) {
            if (!ExcelTemplate.GUIDE_SHEET.equalsIgnoreCase(name)) {
                out.put(name, rows(name, columns, false));
            }
        }
        return out;
    }

    /**
     * Đọc sheet theo tên; không có sheet tên đó thì đọc sheet dữ liệu đầu tiên (đang hiện, không phải sheet hướng dẫn
     * vì file mẫu đặt sheet hướng dẫn đứng đầu) nếu {@code fallbackToFirst}. Bỏ qua dòng trống.
     */
    public List<DataRow> rows(String sheetName, List<Column> columns, boolean fallbackToFirst) {
        Sheet sheet = findSheet(sheetName, fallbackToFirst);
        HeaderMatch header = findHeader(sheet, columns);
        List<DataRow> out = new ArrayList<>();
        for (int r = header.row() + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            Map<String, String> cells = row == null ? null : cells(row, header.index());
            if (cells == null) {
                continue;
            }
            if (out.size() >= MAX_ROWS) {
                throw ApiException.badRequest("TOO_MANY_ROWS", "Sheet \"" + sheet.getSheetName() + "\" có hơn "
                        + MAX_ROWS + " dòng. Hãy chia thành nhiều file.");
            }
            out.add(new DataRow(r + 1, cells));
        }
        return out;
    }

    private Sheet findSheet(String sheetName, boolean fallbackToFirst) {
        Sheet sheet = workbook.getSheet(sheetName);
        if (sheet == null && fallbackToFirst) {
            sheet = visibleSheetNames().stream().filter(n -> !ExcelTemplate.GUIDE_SHEET.equalsIgnoreCase(n))
                    .findFirst().map(workbook::getSheet).orElse(null);
        }
        if (sheet == null) {
            throw ApiException.badRequest("INVALID_TEMPLATE", "File không có sheet \"" + sheetName + "\". Hãy dùng file mẫu.");
        }
        return sheet;
    }

    /** Dòng tiêu đề (chỉ số 0) và vị trí cột của từng khóa. */
    private record HeaderMatch(int row, Map<String, Integer> index) {
    }

    /**
     * Dòng tiêu đề là dòng đầu tiên (trong {@link #HEADER_SCAN_ROWS} dòng đầu) có đủ các cột bắt buộc. Không có thì báo
     * thiếu cột theo dòng giống tiêu đề nhất (nhiều cột khớp nhất).
     */
    private static HeaderMatch findHeader(Sheet sheet, List<Column> columns) {
        List<String> missing = new ArrayList<>();
        int last = Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + HEADER_SCAN_ROWS - 1);
        for (int r = Math.max(0, sheet.getFirstRowNum()); r <= last; r++) {
            Row candidate = sheet.getRow(r);
            if (candidate == null) {
                continue;
            }
            Map<String, Integer> byHeader = new HashMap<>();
            for (Cell c : candidate) {
                String text = text(c);
                if (text != null) {
                    byHeader.putIfAbsent(headerKey(text), c.getColumnIndex());
                }
            }
            Map<String, Integer> found = new LinkedHashMap<>();
            List<String> notFound = new ArrayList<>();
            for (Column col : columns) {
                Integer i = byHeader.get(headerKey(col.header()));
                if (i != null) {
                    found.put(col.key(), i);
                } else if (col.required()) {
                    notFound.add(col.header());
                }
            }
            if (notFound.isEmpty()) {
                return new HeaderMatch(r, found);
            }
            if (missing.isEmpty() || found.size() > columns.size() - missing.size()) {
                missing = notFound;
            }
        }
        if (missing.isEmpty()) {
            missing = columns.stream().filter(Column::required).map(Column::header).toList();
        }
        throw ApiException.badRequest("INVALID_TEMPLATE", "Sheet \"" + sheet.getSheetName() + "\" thiếu cột: "
                + String.join(", ", missing) + ". Hãy dùng file mẫu.");
    }

    /** Giá trị các cột của một dòng, hoặc null nếu dòng trống. */
    private static Map<String, String> cells(Row row, Map<String, Integer> index) {
        Map<String, String> cells = new LinkedHashMap<>();
        boolean any = false;
        for (Map.Entry<String, Integer> e : index.entrySet()) {
            String v = text(row.getCell(e.getValue()));
            cells.put(e.getKey(), v);
            any |= v != null;
        }
        return any ? cells : null;
    }

    @Override
    public void close() {
        try {
            workbook.close();
        } catch (IOException ignored) {
            // Workbook đọc từ bộ nhớ, đóng lỗi cũng không giữ tài nguyên nào.
        }
    }

    // ================================================================== chuyển giá trị ô

    /**
     * Giá trị ô dạng chuỗi đã cắt khoảng trắng, null nếu trống. Ô ngày thành yyyy-MM-dd. Ô số giữ đúng chữ số người
     * dùng thấy (6.5 thành "6.5", 912345678 thành "912345678", không có dạng 9.12E8).
     */
    static String text(Cell c) {
        if (c == null) {
            return null;
        }
        CellType type = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
        String v = switch (type) {
            case STRING -> c.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(c)
                    ? c.getLocalDateTimeCellValue().toLocalDate().format(ISO)
                    : BigDecimal.valueOf(c.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            default -> null;
        };
        if (v == null) {
            return null;
        }
        String t = v.replace(' ', ' ').trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Ngày người dùng gõ: yyyy-MM-dd (cũng là dạng ô ngày đã đổi), hoặc dd/mm/yyyy, dd-mm-yyyy, dd.mm.yyyy.
     * Trả yyyy-MM-dd, hoặc null nếu không đọc được (người gọi báo lỗi).
     */
    public static String isoDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value, ISO).format(ISO);
        } catch (DateTimeParseException ignored) {
            // thử dạng ngày/tháng/năm
        }
        Matcher m = DMY.matcher(value);
        if (!m.matches()) {
            return null;
        }
        try {
            return LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)))
                    .format(ISO);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String headerKey(String header) {
        return VietnameseText.searchKey(header.replace("*", ""));
    }

    private static ApiException invalidFile() {
        return ApiException.badRequest("INVALID_FILE",
                "File không đọc được. Chỉ nhận file Excel .xlsx (Excel 2007 trở lên), hãy lưu lại từ file mẫu.");
    }
}
