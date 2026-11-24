package vn.trainingstats.result;

import java.time.LocalDate;
import java.util.List;
import vn.trainingstats.common.Times;
import vn.trainingstats.exam.ClassDtos.ClassView;
import vn.trainingstats.exam.ClassDtos.PersonRef;

/** Hai dòng thông tin đầu sheet của một nội dung kiểm tra (file mẫu nhập điểm, file xuất kết quả). */
final class ClassSheetInfo {

    private ClassSheetInfo() {
    }

    static List<String> lines(ClassView cls) {
        String examDate = cls.examDate() == null ? "chưa đặt" : LocalDate.parse(cls.examDate()).format(Times.DMY);
        String teachers = cls.teachers().isEmpty() ? "Super Admin"
                : String.join(", ", cls.teachers().stream().map(PersonRef::fullName).toList());
        return List.of("Nội dung: " + cls.name() + "   |   Đợt: " + cls.period().name(),
                "Ngày kiểm tra: " + examDate + (cls.location() == null ? "" : "   |   Địa điểm: " + cls.location())
                        + "   |   Giám khảo: " + teachers);
    }
}
