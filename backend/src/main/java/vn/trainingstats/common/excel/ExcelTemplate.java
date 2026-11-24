package vn.trainingstats.common.excel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.ConditionalFormattingRule;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationConstraint.OperatorType;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.PatternFormatting;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetConditionalFormatting;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Dựng file Excel mẫu để tải về (R7, R8, ARCHITECTURE E3, E9). Hai mục tiêu:
 * <ol>
 *   <li><b>Chặn nhập sai ngay trong Excel.</b> Mỗi cột có ràng buộc dữ liệu kiểu "Stop" (Excel từ chối giá trị sai)
 *       và hộp nhắc định dạng: chọn từ danh sách (danh mục theo TÊN, sheet ẩn {@value #LIST_SHEET}), ngày thật trong
 *       khoảng hợp lệ, điểm 0–10 một chữ số thập phân, độ dài chữ, mã không trùng trong file. Cột mã, điện thoại định
 *       dạng Text để Excel không bỏ số 0 đầu hay đổi mã dài thành 1.2E+10. Cột điền sẵn (file mẫu điểm) bị khóa.
 *       Máy chủ vẫn kiểm lại mọi giá trị: người dùng có thể dán dữ liệu, tắt bảo vệ, hoặc dùng phần mềm khác.</li>
 *   <li><b>Bố cục như văn bản của đơn vị.</b> Khối tiêu đề nền xanh hải quân, dòng thông tin (người tạo, phạm vi,
 *       ngày tạo), tiêu đề cột phân biệt cột bắt buộc, bảng kẻ viền và sọc, bộ lọc, cố định dòng tiêu đề, in ngang vừa
 *       khổ giấy có số trang, sheet {@value #GUIDE_SHEET} giải thích từng cột kèm ví dụ.</li>
 * </ol>
 * Dòng tiêu đề cột không ở dòng 1: {@link ExcelBook} tự tìm dòng có đủ các cột.
 */
public final class ExcelTemplate implements AutoCloseable {

    public static final String LIST_SHEET = "DanhMuc";
    public static final String GUIDE_SHEET = "HuongDan";
    /** Số dòng được kẻ bảng sẵn. Ràng buộc dữ liệu áp cho đủ {@link ExcelBook#MAX_ROWS} dòng. */
    static final int STYLED_ROWS = 200;
    private static final int MAX_SHEET_NAME = 31;
    private static final String APP_NAME = "HỆ THỐNG QUẢN LÝ HUẤN LUYỆN";
    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    // Màu theo bảng màu Hải quân của giao diện (tokens.css).
    private static final byte[] NAVY = rgb(0x0F2A4A);
    private static final byte[] NAVY_700 = rgb(0x1F4E8C);
    private static final byte[] GOLD = rgb(0xC9A227);
    private static final byte[] MIST = rgb(0xDCE4EE);
    private static final byte[] STRIPE = rgb(0xF4F6FA);
    private static final byte[] LOCKED = rgb(0xEEF2F7);
    private static final byte[] BORDER = rgb(0xC5D0DC);
    private static final byte[] MUTED = rgb(0x4A5868);
    private static final byte[] WHITE = rgb(0xFFFFFF);
    private static final byte[] INPUT = rgb(0xFFF4C7);
    private static final byte[] DONE = rgb(0xD5DCE5);
    private static final byte[] HATCH = rgb(0x8C99A8);
    private static final byte[] HATCH_BG = rgb(0xF2F4F7);

    /**
     * Trạng thái một ô của bảng điền sẵn, tô khác hẳn nhau để người điền thấy ngay ô nào được nhập:
     * {@code INPUT} nền vàng nhạt, mở; {@code DONE} nền xám, chữ mờ, khóa (đã có giá trị); {@code NOT_APPLICABLE} gạch
     * chéo, khóa (không áp dụng cho dòng này).
     */
    public enum CellState { INPUT, DONE, NOT_APPLICABLE }

    /**
     * Kiểu dữ liệu của cột: quyết định định dạng ô và ràng buộc dữ liệu. {@code VALUE}, {@code VALUE_SCORE}: cột của
     * file xuất (chỉ để xem, in), không ràng buộc, không khóa, không tô xám.
     */
    public enum Type { TEXT, CODE, LIST, DATE, PAST_DATE, PHONE, SCORE, READ_ONLY, VALUE, VALUE_SCORE }

    /**
     * Một cột của file mẫu.
     *
     * @param maxLength   độ dài tối đa (TEXT, CODE)
     * @param listSource  tham chiếu danh sách cho LIST: null thì dùng {@code options} (ghi vào sheet ẩn)
     * @param format      mô tả định dạng, giá trị hợp lệ (hộp nhắc trong Excel và sheet hướng dẫn)
     * @param example     ví dụ ở sheet hướng dẫn
     */
    public record Field(ExcelBook.Column column, Type type, int width, int maxLength, List<String> options,
                        String listSource, String format, String example) {

        public static Field text(ExcelBook.Column c, int width, int maxLength, String format, String example) {
            return new Field(c, Type.TEXT, width, maxLength, List.of(), null, format, example);
        }

        /** Mã: định dạng Text, không trùng trong file. */
        public static Field code(ExcelBook.Column c, int width, int maxLength, String format, String example) {
            return new Field(c, Type.CODE, width, maxLength, List.of(), null, format, example);
        }

        public static Field list(ExcelBook.Column c, int width, List<String> options, String format, String example) {
            return new Field(c, Type.LIST, width, 0, options, null, format, example);
        }

        /** Danh sách lấy từ một vùng có sẵn trong file (ví dụ tên lớp ở sheet khác). */
        public static Field listFrom(ExcelBook.Column c, int width, String source, String format, String example) {
            return new Field(c, Type.LIST, width, 0, List.of(), source, format, example);
        }

        /** Ngày thật trong khoảng 1900–2100; {@code pastOnly} thì không quá hôm nay (ngày sinh). */
        public static Field date(ExcelBook.Column c, int width, boolean pastOnly, String format, String example) {
            return new Field(c, pastOnly ? Type.PAST_DATE : Type.DATE, width, 0, List.of(), null, format, example);
        }

        public static Field phone(ExcelBook.Column c, int width, String example) {
            return new Field(c, Type.PHONE, width, 20, List.of(), null,
                    "Chữ số, 6 đến 20 ký tự (được có dấu cách, dấu chấm, +).", example);
        }

        public static Field score(ExcelBook.Column c, int width) {
            return new Field(c, Type.SCORE, width, 0, List.of(), null,
                    "Số từ 0 đến 10, tối đa một chữ số thập phân. Để trống nếu chưa có điểm.", "6.5");
        }

        /** Cột điền sẵn, bị khóa, chỉ để khớp hoặc đối chiếu. */
        public static Field readOnly(ExcelBook.Column c, int width, String format) {
            return new Field(c, Type.READ_ONLY, width, 0, List.of(), null, format, "");
        }

        /** Cột của file xuất. */
        public static Field value(ExcelBook.Column c, int width) {
            return new Field(c, Type.VALUE, width, 0, List.of(), null, "", "");
        }

        /** Cột điểm của file xuất: số một chữ số thập phân, căn giữa. */
        public static Field scoreValue(ExcelBook.Column c, int width) {
            return new Field(c, Type.VALUE_SCORE, width, 0, List.of(), null, "", "");
        }

        boolean required() {
            return column.header().trim().endsWith("*");
        }
    }

    /** Sheet dữ liệu đã dựng. {@code headerRow} là chỉ số (0) của dòng tiêu đề cột. */
    public record DataSheet(XSSFSheet sheet, List<Field> fields, int headerRow, int rowCount) {
        int firstDataRow() {
            return headerRow + 1;
        }

        /** Vùng cột tuyệt đối của dữ liệu, ví dụ {@code Lop!$A$7:$A$5006} (để tham chiếu từ sheet khác). */
        public String columnRange(int column) {
            String letter = CellReference.convertNumToColString(column);
            return "'" + sheet.getSheetName() + "'!$" + letter + "$" + (firstDataRow() + 1) + ":$" + letter + "$"
                    + (firstDataRow() + rowCount);
        }
    }

    private final XSSFWorkbook wb = new XSSFWorkbook();
    private final Map<String, XSSFCellStyle> styles = new HashMap<>();
    private Sheet lists;
    private int listColumns;
    /** Cùng một danh sách (ví dụ ba cột chuyên ngành) chỉ ghi một lần vào sheet ẩn. */
    private final Map<List<String>, String> listRanges = new HashMap<>();
    private final Set<String> usedSheetNames = new HashSet<>(Set.of(GUIDE_SHEET.toLowerCase(), LIST_SHEET.toLowerCase()));
    private boolean guideAdded;

    public ExcelTemplate(String documentTitle) {
        var props = wb.getProperties().getCoreProperties();
        props.setTitle(documentTitle);
        props.setCreator(APP_NAME);
    }

    /**
     * Tên sheet cho một đơn vị (file mỗi đơn vị một sheet): tên đơn vị, thêm mã khi {@code nameRepeats} (hai đơn vị
     * cùng tên); bỏ ký tự Excel không cho phép, tối đa {@value #MAX_SHEET_NAME} ký tự, không trùng sheet đã đặt.
     */
    public String unitSheetName(String unitName, String unitCode, boolean nameRepeats) {
        String base = unitName.replaceAll("[\\[\\]:*?/\\\\']", " ").trim();
        String name = fitSheetName(base, nameRepeats ? " " + unitCode : "");
        int n = 2;
        while (!usedSheetNames.add(name.toLowerCase())) {
            name = fitSheetName(base, " " + unitCode + " (" + n++ + ")");
        }
        return name;
    }

    private static String fitSheetName(String base, String suffix) {
        int room = MAX_SHEET_NAME - suffix.length();
        return (base.length() > room ? base.substring(0, room).trim() : base) + suffix;
    }

    // ================================================================== sheet dữ liệu

    /**
     * Sheet dữ liệu: khối tiêu đề, các dòng thông tin, chú thích, rồi dòng tiêu đề cột và bảng nhập.
     *
     * @param info các dòng "Nhãn: giá trị" dưới tiêu đề (người tạo, phạm vi, lớp...)
     */
    public DataSheet dataSheet(String name, String title, List<String> info, List<Field> fields) {
        return dataSheet(name, title, info, fields,
                "Cột tiêu đề màu xanh đậm có dấu * là bắt buộc. Excel từ chối giá trị sai. Định dạng từng cột xem ở sheet "
                        + GUIDE_SHEET + " trước khi điền.", -1);
    }

    /**
     * @param note      dòng chú thích ngay trên bảng
     * @param fixedRows số dòng cố định (file mẫu điền sẵn): chỉ kẻ và mở đúng ngần ấy dòng, phần dưới bị khóa khi
     *                  bảo vệ sheet. -1 là bảng mở, người dùng điền thêm bao nhiêu dòng cũng được
     */
    public DataSheet dataSheet(String name, String title, List<String> info, List<Field> fields, String note,
                               int fixedRows) {
        boolean fixed = fixedRows >= 0;
        int styledRows = fixed ? Math.max(1, fixedRows) : STYLED_ROWS;
        XSSFSheet s = wb.createSheet(name);
        int lastCol = fields.size() - 1;
        int r = 0;

        Row app = s.createRow(r);
        app.setHeightInPoints(18);
        fillRow(app, lastCol, style("app"), APP_NAME);
        merge(s, r++, lastCol);

        Row head = s.createRow(r);
        head.setHeightInPoints(30);
        fillRow(head, lastCol, style("title"), title.toUpperCase());
        merge(s, r++, lastCol);

        for (String line : info) {
            Row row = s.createRow(r);
            fillRow(row, lastCol, style("info"), line);
            merge(s, r++, lastCol);
        }
        int totalWidth = fields.stream().mapToInt(Field::width).sum();
        Row noteRow = s.createRow(r);
        fillRow(noteRow, lastCol, style("note"), note);
        fitHeight(noteRow, note, totalWidth);
        merge(s, r++, lastCol);
        r++; // một dòng trống trước bảng

        int headerRow = r;
        Row header = s.createRow(headerRow);
        header.setHeightInPoints(34);
        for (int c = 0; c < fields.size(); c++) {
            Field f = fields.get(c);
            Cell cell = header.createCell(c);
            cell.setCellValue(f.column().header());
            boolean strong = f.required() || f.type() == Type.SCORE || f.type() == Type.VALUE
                    || f.type() == Type.VALUE_SCORE;
            cell.setCellStyle(style(f.type() == Type.READ_ONLY ? "head-locked" : strong ? "head-required" : "head"));
            s.setColumnWidth(c, f.width() * 256);
        }
        DataSheet ds = new DataSheet(s, fields, headerRow, fixed ? styledRows : ExcelBook.MAX_ROWS);

        int first = ds.firstDataRow();
        for (int i = 0; i < styledRows; i++) {
            Row row = s.createRow(first + i);
            for (int c = 0; c < fields.size(); c++) {
                row.createCell(c).setCellStyle(cellStyle(fields.get(c)));
            }
        }
        for (int c = 0; c < fields.size(); c++) {
            // Dòng ngoài vùng kẻ sẵn vẫn đúng định dạng (Text, ngày, điểm) khi người dùng gõ thêm.
            // Bảng cố định: mọi ô ngoài các dòng điền sẵn đều khóa (kiểu mặc định của cột là khóa).
            s.setDefaultColumnStyle(c, fixed ? lockedFormat(fields.get(c)) : formatOnly(fields.get(c)));
            validate(ds, c);
        }
        stripes(s, first, first + styledRows - 1, fields);

        s.createFreezePane(0, first);
        s.setAutoFilter(new CellRangeAddress(headerRow, first + styledRows - 1, 0, lastCol));
        s.setRepeatingRows(new CellRangeAddress(headerRow, headerRow, -1, -1));
        printA4(s, true);
        s.getHeader().setLeft(APP_NAME);
        s.getHeader().setRight(title);
        s.setDisplayGridlines(false);
        return ds;
    }

    /**
     * Điền sẵn các dòng (file mẫu điểm): mỗi phần tử là giá trị các cột theo thứ tự (chuỗi hoặc số), null là để
     * trống.
     */
    public void fill(DataSheet ds, List<Object[]> rows) {
        for (int i = 0; i < rows.size(); i++) {
            int index = ds.firstDataRow() + i;
            Row row = ds.sheet().getRow(index) != null ? ds.sheet().getRow(index) : ds.sheet().createRow(index);
            Object[] values = rows.get(i);
            for (int c = 0; c < ds.fields().size(); c++) {
                Cell cell = row.getCell(c) != null ? row.getCell(c) : row.createCell(c);
                cell.setCellStyle(cellStyle(ds.fields().get(c)));
                Object v = c < values.length ? values[c] : null;
                if (v instanceof Number n) {
                    cell.setCellValue(n.doubleValue());
                } else if (v != null) {
                    cell.setCellValue(v.toString());
                }
            }
        }
    }

    /** Khóa một ô của dòng điền sẵn thứ {@code index} (ví dụ ô điểm của người đã có điểm), tô xám như cột khóa. */
    public void lock(DataSheet ds, int index, int column) {
        Row row = ds.sheet().getRow(ds.firstDataRow() + index);
        Field f = ds.fields().get(column);
        Cell cell = row.getCell(column) != null ? row.getCell(column) : row.createCell(column);
        cell.setCellStyle(cellStyle(new Field(f.column(), Type.READ_ONLY, f.width(), f.maxLength(), f.options(),
                f.listSource(), f.format(), f.example()), dataFormat(f)));
    }

    /** Tô và khóa (hoặc mở) một ô của dòng điền sẵn thứ {@code index} theo trạng thái. */
    public void mark(DataSheet ds, int index, int column, CellState state) {
        Row row = ds.sheet().getRow(ds.firstDataRow() + index);
        Cell cell = row.getCell(column) != null ? row.getCell(column) : row.createCell(column);
        cell.setCellStyle(stateStyle(state, dataFormat(ds.fields().get(column))));
    }

    /**
     * Dòng chú thích ngay trên tiêu đề cột (dòng trống của {@link #dataSheet}): mỗi mục là một ô mẫu tô đúng như trạng
     * thái, ghi ý nghĩa bên trong. Mỗi mục chiếm một hoặc vài cột liền nhau cho đủ rộng, hết cột thì dừng.
     */
    public void legend(DataSheet ds, Map<CellState, String> items) {
        XSSFSheet s = ds.sheet();
        Row row = s.getRow(ds.headerRow() - 1) != null ? s.getRow(ds.headerRow() - 1) : s.createRow(ds.headerRow() - 1);
        row.setHeightInPoints(32);
        int lastCol = ds.fields().size() - 1;
        int c = 0;
        for (Map.Entry<CellState, String> item : items.entrySet()) {
            if (c > lastCol) {
                break;
            }
            int end = c;
            int width = ds.fields().get(c).width();
            while (width < 22 && end < lastCol) {
                width += ds.fields().get(++end).width();
            }
            XSSFCellStyle st = legendStyle(item.getKey());
            for (int i = c; i <= end; i++) {
                Cell cell = row.createCell(i);
                cell.setCellStyle(st);
                if (i == c) {
                    cell.setCellValue(item.getValue());
                }
            }
            if (end > c) {
                s.addMergedRegion(new CellRangeAddress(row.getRowNum(), row.getRowNum(), c, end));
            }
            c = end + 1;
        }
    }

    private XSSFCellStyle stateStyle(CellState state, String format) {
        return styles.computeIfAbsent("state|" + state + "|" + format, k -> {
            XSSFCellStyle st = wb.createCellStyle();
            st.setDataFormat(wb.createDataFormat().getFormat(format));
            paintState(st, state);
            st.setAlignment(HorizontalAlignment.CENTER);
            return st;
        });
    }

    private XSSFCellStyle legendStyle(CellState state) {
        return styles.computeIfAbsent("legend|" + state, k -> {
            XSSFCellStyle st = wb.createCellStyle();
            paintState(st, state);
            st.setAlignment(HorizontalAlignment.CENTER);
            st.setWrapText(true);
            XSSFFont font = wb.createFont();
            font.setBold(true);
            font.setFontHeightInPoints((short) 9);
            font.setColor(new XSSFColor(state == CellState.INPUT ? NAVY : MUTED, null));
            st.setFont(font);
            return st;
        });
    }

    private void paintState(XSSFCellStyle st, CellState state) {
        borders(st);
        st.setVerticalAlignment(VerticalAlignment.CENTER);
        st.setLocked(state != CellState.INPUT);
        switch (state) {
            case INPUT -> {
                st.setFillForegroundColor(new XSSFColor(INPUT, null));
                st.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
            case DONE -> {
                st.setFillForegroundColor(new XSSFColor(DONE, null));
                st.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                XSSFFont font = wb.createFont();
                font.setColor(new XSSFColor(MUTED, null));
                font.setItalic(true);
                st.setFont(font);
            }
            case NOT_APPLICABLE -> {
                st.setFillForegroundColor(new XSSFColor(HATCH, null));
                st.setFillBackgroundColor(new XSSFColor(HATCH_BG, null));
                st.setFillPattern(FillPatternType.THIN_FORWARD_DIAG);
            }
            default -> throw new IllegalArgumentException(state.name());
        }
    }

    /**
     * Khóa sheet, chỉ để mở các ô không phải READ_ONLY. Không đặt mật khẩu: mục đích là tránh sửa nhầm cột khớp, máy
     * chủ vẫn kiểm lại. Vẫn cho lọc, sắp xếp, đổi độ rộng cột.
     */
    public void protect(DataSheet ds) {
        XSSFSheet s = ds.sheet();
        s.enableLocking();
        s.lockAutoFilter(false);
        s.lockSort(false);
        s.lockFormatColumns(false);
        s.lockFormatRows(false);
        s.lockSelectLockedCells(false);
        s.lockSelectUnlockedCells(false);
    }

    // ================================================================== sheet hướng dẫn

    /** Một nhóm cột trong sheet hướng dẫn (mỗi sheet dữ liệu một nhóm). */
    public record GuideSection(String heading, List<Field> fields) {
    }

    public void guide(String title, List<String> intro, List<GuideSection> sections, List<String> notes) {
        XSSFSheet g = wb.createSheet(GUIDE_SHEET);
        g.setDisplayGridlines(false);
        int[] widths = {26, 11, 64, 26};
        for (int i = 0; i < widths.length; i++) {
            g.setColumnWidth(i, widths[i] * 256);
        }
        int last = widths.length - 1;
        int r = 0;
        Row app = g.createRow(r);
        fillRow(app, last, style("app"), APP_NAME);
        merge(g, r++, last);
        Row head = g.createRow(r);
        head.setHeightInPoints(30);
        fillRow(head, last, style("title"), title.toUpperCase());
        merge(g, r++, last);
        int total = Arrays.stream(widths).sum();
        for (String line : intro) {
            r = wideRow(g, r, last, "info-wrap", line, total);
        }
        for (GuideSection section : sections) {
            r = guideSection(g, r + 1, last, section, widths[2]);
        }
        r = wideRow(g, r + 1, last, "section", "Lưu ý", 0);
        for (int n = 0; n < notes.size(); n++) {
            r = wideRow(g, r, last, "info-wrap", (n + 1) + ". " + notes.get(n), total);
        }
        printA4(g, false);
        guideAdded = true;
    }

    /** Bảng các cột của một loại sheet trong sheet hướng dẫn. Trả dòng kế tiếp. */
    private int guideSection(XSSFSheet g, int r, int last, GuideSection section, int formatWidth) {
        int next = wideRow(g, r, last, "section", section.heading(), 0);
        Row th = g.createRow(next++);
        th.setHeightInPoints(22);
        String[] labels = {"Cột", "Bắt buộc", "Định dạng, giá trị hợp lệ", "Ví dụ"};
        for (int c = 0; c < labels.length; c++) {
            Cell cell = th.createCell(c);
            cell.setCellValue(labels[c]);
            cell.setCellStyle(style("head-required"));
        }
        for (Field f : section.fields()) {
            Row row = g.createRow(next++);
            String header = f.column().header().replace("*", "").trim();
            String required = f.type() == Type.READ_ONLY ? "Điền sẵn" : f.required() ? "Có" : "Không";
            String[] values = {header, required, f.format(), f.example() == null ? "" : f.example()};
            for (int c = 0; c < values.length; c++) {
                Cell cell = row.createCell(c);
                cell.setCellValue(values[c]);
                cell.setCellStyle(style(c == 0 ? "guide-key" : "guide-cell"));
            }
            fitHeight(row, values[2], formatWidth);
        }
        return next;
    }

    /** Một dòng chữ gộp hết bề ngang; widthChars > 0 thì cao vừa chữ (tiêu đề mục để 0). Trả dòng kế tiếp. */
    private int wideRow(XSSFSheet g, int r, int last, String styleName, String text, int widthChars) {
        Row row = g.createRow(r);
        fillRow(row, last, style(styleName), text);
        if (widthChars > 0) {
            fitHeight(row, text, widthChars);
        }
        merge(g, r, last);
        return r + 1;
    }

    /** In khổ A4, vừa một trang theo chiều ngang, số trang ở chân trang. */
    private static void printA4(XSSFSheet s, boolean landscape) {
        var print = s.getPrintSetup();
        print.setLandscape(landscape);
        print.setPaperSize(PrintSetup.A4_PAPERSIZE);
        print.setFitWidth((short) 1);
        print.setFitHeight((short) 0);
        s.setFitToPage(true);
        usePageSettings(s);
        s.getFooter().setCenter("Trang &P / &N");
    }

    // ================================================================== xuất file

    public byte[] toBytes() {
        if (guideAdded) {
            // Mọi file mẫu thống nhất: sheet hướng dẫn đứng đầu và mở ra trước (yêu cầu ngày 2026-11-21).
            wb.setSheetOrder(GUIDE_SHEET, 0);
        }
        if (lists != null) {
            // Ẩn hẳn (không hiện trong "Unhide") để người dùng không sửa nhầm danh mục.
            wb.setSheetVisibility(wb.getSheetIndex(lists), SheetVisibility.VERY_HIDDEN);
        }
        wb.setActiveSheet(0);
        wb.setFirstVisibleTab(0);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Không tạo được file mẫu", e);
        }
    }

    @Override
    public void close() {
        try {
            wb.close();
        } catch (IOException ignored) {
            // workbook trong bộ nhớ
        }
    }

    /** Phản hồi tải file .xlsx với tên file (tên ASCII để mọi trình duyệt hiển thị đúng). */
    public static ResponseEntity<byte[]> download(String fileName, byte[] bytes) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"; filename*=UTF-8''" + encoded)
                .body(bytes);
    }

    // ================================================================== ràng buộc dữ liệu

    private void validate(DataSheet ds, int column) {
        Field f = ds.fields().get(column);
        XSSFSheet s = ds.sheet();
        DataValidationHelper helper = s.getDataValidationHelper();
        int first = ds.firstDataRow();
        int last = Math.min(first + ds.rowCount() - 1, SpreadsheetVersion.EXCEL2007.getLastRowIndex());
        String cell = CellReference.convertNumToColString(column) + (first + 1);
        String col = CellReference.convertNumToColString(column);
        String range = "$" + col + "$" + (first + 1) + ":$" + col + "$" + (last + 1);

        DataValidationConstraint constraint;
        String error;
        switch (f.type()) {
            case TEXT -> {
                constraint = helper.createTextLengthConstraint(OperatorType.LESS_OR_EQUAL, String.valueOf(f.maxLength()), null);
                error = "Tối đa " + f.maxLength() + " ký tự.";
            }
            case CODE -> {
                constraint = helper.createCustomConstraint(
                        "AND(LEN(" + cell + ")<=" + f.maxLength() + ",COUNTIF(" + range + "," + cell + ")<=1)");
                error = "Tối đa " + f.maxLength() + " ký tự và không trùng với dòng khác trong file.";
            }
            case LIST -> {
                String source = f.listSource() != null ? f.listSource() : listRange(f.options());
                if (source == null) {
                    return;
                }
                constraint = helper.createFormulaListConstraint(source);
                error = "Chọn một giá trị trong danh sách (bấm mũi tên ở cạnh ô).";
            }
            case DATE -> {
                constraint = helper.createDateConstraint(OperatorType.BETWEEN, "=DATE(1900,1,1)", "=DATE(2100,12,31)", null);
                error = "Nhập ngày theo dạng ngày/tháng/năm, ví dụ 25/11/2026.";
            }
            case PAST_DATE -> {
                constraint = helper.createDateConstraint(OperatorType.BETWEEN, "=DATE(1900,1,1)", "=TODAY()", null);
                error = "Nhập ngày theo dạng ngày/tháng/năm, không sau hôm nay. Ví dụ 05/09/1995.";
            }
            case PHONE -> {
                constraint = helper.createTextLengthConstraint(OperatorType.BETWEEN, "6", String.valueOf(f.maxLength()));
                error = "Số điện thoại từ 6 đến " + f.maxLength() + " ký tự.";
            }
            case SCORE -> {
                constraint = helper.createCustomConstraint(
                        "AND(ISNUMBER(" + cell + ")," + cell + ">=0," + cell + "<=10,ROUND(" + cell + ",1)=" + cell + ")");
                error = "Điểm là số từ 0 đến 10, tối đa một chữ số thập phân (ví dụ 6.5).";
            }
            default -> {
                return;
            }
        }
        DataValidation dv = helper.createValidation(constraint, new CellRangeAddressList(first, last, column, column));
        dv.setErrorStyle(DataValidation.ErrorStyle.STOP);
        dv.createErrorBox("Giá trị không hợp lệ", error);
        dv.setShowErrorBox(true);
        // Không bật hộp gợi ý khi chọn ô: nó che các ô bên cạnh khi nhập liên tục. Hướng dẫn nằm ở sheet HuongDan.
        dv.setShowPromptBox(false);
        if (f.type() == Type.LIST) {
            // Với .xlsx, cờ này true thì Excel MỚI hiện mũi tên chọn (ngược tên gọi, đặc thù của POI).
            dv.setSuppressDropDownArrow(true);
        }
        dv.setEmptyCellAllowed(true);
        s.addValidationData(dv);
    }

    /** Ghi danh sách vào sheet ẩn và trả vùng tham chiếu, hoặc null nếu danh sách rỗng. */
    private String listRange(List<String> values) {
        if (values.isEmpty()) {
            return null;
        }
        String cached = listRanges.get(values);
        if (cached != null) {
            return cached;
        }
        if (lists == null) {
            lists = wb.createSheet(LIST_SHEET);
        }
        int col = listColumns++;
        for (int i = 0; i < values.size(); i++) {
            Row r = lists.getRow(i) == null ? lists.createRow(i) : lists.getRow(i);
            r.createCell(col).setCellValue(values.get(i));
        }
        String letter = CellReference.convertNumToColString(col);
        String range = LIST_SHEET + "!$" + letter + "$1:$" + letter + "$" + values.size();
        listRanges.put(List.copyOf(values), range);
        return range;
    }

    // ================================================================== kiểu ô

    /**
     * Sọc xen kẽ bằng định dạng có điều kiện. Không phủ cột điểm: định dạng có điều kiện đè lên màu nền của ô, làm ô khóa
     * (xám) và ô nhập trông như nhau.
     */
    private void stripes(Sheet s, int first, int last, List<Field> fields) {
        List<CellRangeAddress> ranges = new ArrayList<>();
        int start = -1;
        for (int c = 0; c <= fields.size(); c++) {
            boolean striped = c < fields.size() && fields.get(c).type() != Type.SCORE;
            if (striped && start < 0) {
                start = c;
            } else if (!striped && start >= 0) {
                ranges.add(new CellRangeAddress(first, last, start, c - 1));
                start = -1;
            }
        }
        if (ranges.isEmpty()) {
            return;
        }
        SheetConditionalFormatting cf = s.getSheetConditionalFormatting();
        ConditionalFormattingRule rule = cf.createConditionalFormattingRule("MOD(ROW(),2)=0");
        PatternFormatting fill = rule.createPatternFormatting();
        fill.setFillBackgroundColor(new XSSFColor(STRIPE, null));
        fill.setFillPattern(PatternFormatting.SOLID_FOREGROUND);
        cf.addConditionalFormatting(ranges.toArray(new CellRangeAddress[0]), rule);
    }

    private static void fillRow(Row row, int lastCol, XSSFCellStyle style, String text) {
        for (int c = 0; c <= lastCol; c++) {
            Cell cell = row.createCell(c);
            cell.setCellStyle(style);
            if (c == 0) {
                cell.setCellValue(text);
            }
        }
    }

    /** Ô gộp không tự giãn chiều cao theo chữ: ước lượng số dòng theo độ rộng (ký tự) rồi đặt chiều cao. */
    private static void fitHeight(Row row, String text, int widthChars) {
        int lines = Math.max(1, (int) Math.ceil(text.length() * 1.1 / Math.max(10, widthChars)));
        row.setHeightInPoints(Math.max(row.getHeightInPoints(), 15f * lines + 3));
    }

    /**
     * POI ghi usePrinterDefaults="false" vào pageSetup, và Excel khi đó bỏ qua khổ giấy, chiều giấy (đã kiểm bằng
     * Excel 2016: file mở ra vẫn khổ dọc, giấy Letter). Bỏ thuộc tính này thì Excel dùng đúng A4 khổ ngang.
     */
    private static void usePageSettings(XSSFSheet s) {
        var setup = s.getCTWorksheet().getPageSetup();
        if (setup != null && setup.isSetUsePrinterDefaults()) {
            setup.unsetUsePrinterDefaults();
        }
    }

    private static void merge(Sheet s, int row, int lastCol) {
        if (lastCol > 0) {
            s.addMergedRegion(new CellRangeAddress(row, row, 0, lastCol));
        }
    }

    private static String dataFormat(Field f) {
        return switch (f.type()) {
            case DATE, PAST_DATE -> "dd/mm/yyyy";
            case SCORE, VALUE_SCORE -> "0.0";
            case LIST -> "General";
            default -> "@";
        };
    }

    private XSSFCellStyle cellStyle(Field f) {
        return cellStyle(f, dataFormat(f));
    }

    private XSSFCellStyle cellStyle(Field f, String format) {
        boolean locked = f.type() == Type.READ_ONLY;
        String key = "cell|" + format + "|" + locked + "|" + (f.type() == Type.SCORE);
        return styles.computeIfAbsent(key, k -> {
            XSSFCellStyle st = wb.createCellStyle();
            st.setDataFormat(wb.createDataFormat().getFormat(format));
            borders(st);
            st.setVerticalAlignment(VerticalAlignment.CENTER);
            st.setLocked(locked);
            if (locked) {
                st.setFillForegroundColor(new XSSFColor(LOCKED, null));
                st.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
            if (f.type() == Type.SCORE || "0.0".equals(format)) {
                st.setAlignment(HorizontalAlignment.CENTER);
            }
            return st;
        });
    }

    private XSSFCellStyle formatOnly(Field f) {
        return styles.computeIfAbsent("col|" + dataFormat(f) + "|" + (f.type() == Type.READ_ONLY), k -> {
            XSSFCellStyle st = wb.createCellStyle();
            st.setDataFormat(wb.createDataFormat().getFormat(dataFormat(f)));
            st.setLocked(f.type() == Type.READ_ONLY);
            return st;
        });
    }

    private XSSFCellStyle lockedFormat(Field f) {
        return styles.computeIfAbsent("col-locked|" + dataFormat(f), k -> {
            XSSFCellStyle st = wb.createCellStyle();
            st.setDataFormat(wb.createDataFormat().getFormat(dataFormat(f)));
            st.setLocked(true);
            return st;
        });
    }

    private void borders(XSSFCellStyle st) {
        XSSFColor c = new XSSFColor(BORDER, null);
        st.setBorderTop(BorderStyle.THIN);
        st.setBorderBottom(BorderStyle.THIN);
        st.setBorderLeft(BorderStyle.THIN);
        st.setBorderRight(BorderStyle.THIN);
        st.setTopBorderColor(c);
        st.setBottomBorderColor(c);
        st.setLeftBorderColor(c);
        st.setRightBorderColor(c);
    }

    private XSSFCellStyle style(String name) {
        return styles.computeIfAbsent(name, k -> {
            XSSFCellStyle st = wb.createCellStyle();
            XSSFFont font = wb.createFont();
            st.setVerticalAlignment(VerticalAlignment.CENTER);
            switch (name) {
                case "app" -> {
                    solid(st, NAVY);
                    font.setColor(new XSSFColor(GOLD, null));
                    font.setBold(true);
                    font.setFontHeightInPoints((short) 9);
                    st.setIndention((short) 1);
                }
                case "title" -> {
                    solid(st, NAVY);
                    font.setColor(new XSSFColor(WHITE, null));
                    font.setBold(true);
                    font.setFontHeightInPoints((short) 15);
                    st.setIndention((short) 1);
                    st.setBorderBottom(BorderStyle.THICK);
                    st.setBottomBorderColor(new XSSFColor(GOLD, null));
                }
                case "info", "info-wrap" -> {
                    font.setColor(new XSSFColor(MUTED, null));
                    font.setFontHeightInPoints((short) 10);
                    st.setWrapText("info-wrap".equals(name));
                    st.setVerticalAlignment(VerticalAlignment.TOP);
                }
                case "note" -> {
                    st.setWrapText(true);
                    font.setItalic(true);
                    font.setColor(new XSSFColor(NAVY_700, null));
                    font.setFontHeightInPoints((short) 10);
                }
                case "section" -> {
                    font.setBold(true);
                    font.setColor(new XSSFColor(NAVY, null));
                    font.setFontHeightInPoints((short) 12);
                    st.setBorderBottom(BorderStyle.MEDIUM);
                    st.setBottomBorderColor(new XSSFColor(NAVY, null));
                }
                case "head-required", "head", "head-locked" -> {
                    solid(st, "head-required".equals(name) ? NAVY : "head".equals(name) ? MIST : MUTED);
                    font.setBold(true);
                    font.setColor(new XSSFColor("head".equals(name) ? NAVY : WHITE, null));
                    st.setAlignment(HorizontalAlignment.CENTER);
                    st.setWrapText(true);
                    borders(st);
                }
                case "guide-key", "guide-cell" -> {
                    borders(st);
                    st.setWrapText(true);
                    st.setVerticalAlignment(VerticalAlignment.TOP);
                    font.setBold("guide-key".equals(name));
                    font.setColor(new XSSFColor(NAVY, null));
                }
                default -> throw new IllegalArgumentException(name);
            }
            st.setFont(font);
            return st;
        });
    }

    private static void solid(XSSFCellStyle st, byte[] color) {
        st.setFillForegroundColor(new XSSFColor(color, null));
        st.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    }

    private static byte[] rgb(int hex) {
        return new byte[] {(byte) (hex >> 16), (byte) (hex >> 8), (byte) hex};
    }
}
