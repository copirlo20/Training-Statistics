# PRD: Ứng dụng quản lý và thống kê huấn luyện

Tài liệu yêu cầu sản phẩm. Các điểm chưa rõ trong yêu cầu ban đầu đã được chốt qua hai vòng hỏi đáp với chủ dự án (kết quả nằm ở mục "Quyết định đã chốt"). Thiết kế kỹ thuật ở [ARCHITECTURE.md](ARCHITECTURE.md), kế hoạch thực hiện và tình trạng ở [PLAN.md](PLAN.md).

> **Thuật ngữ.** Trên giao diện và trong thông báo, "lớp" gọi là **nội dung kiểm tra** và "giáo viên" gọi là **giám khảo**. Trong mã, API, bảng và các mục kỹ thuật vẫn dùng `class`/`exam_class`/`classes` và `teacher`/`class_teacher`, nên "lớp" và "giáo viên" ở các mục kỹ thuật chính là hai khái niệm này. Hệ thống không có trường "Môn kiểm tra": tên nội dung kiểm tra đã đủ nghĩa.

## Mục tiêu

- Số hóa quản lý nhân sự, nội dung kiểm tra, chấm điểm và xếp loại huấn luyện của Lữ đoàn.
- Phân quyền chặt theo cây đơn vị. Mỗi admin chỉ thấy và quản lý đúng nhánh của mình.
- Có thống kê và báo cáo in được theo đơn vị, nội dung kiểm tra, đợt và thời gian.
- Triển khai dễ trên một máy chủ Windows trong mạng LAN, dữ liệu nằm trong một file SQLite.

## Đối tượng người dùng

| Vai trò | Quyền chính |
|---|---|
| Super Admin (Cấp 0, không thuộc đơn vị) | Toàn quyền: quản lý cây đơn vị, tạo đợt và nội dung kiểm tra, chấm và sửa điểm, xem toàn bộ, sao lưu |
| Admin Cấp 1/Cấp 2 (cấp suy ra từ đơn vị; mỗi đơn vị có nhiều admin được) | CRUD user, tìm kiếm, thống kê, báo cáo trong đơn vị mình và cấp dưới cùng nhánh. Không sửa điểm. Đề nghị thêm/xóa người học. Admin "Thủ trưởng Lữ đoàn" không có quyền toàn Lữ đoàn |
| Giám khảo (vai trò gán theo từng nội dung kiểm tra, không phải vai trò tài khoản) | Quản lý nội dung kiểm tra được gán. Thêm trực tiếp người học (tìm trên toàn Lữ đoàn, chỉ thấy tên, cấp bậc, đơn vị, mã). Xác nhận hoặc hủy đề nghị. Chấm điểm một lần cho mỗi người học |
| User thường | Đăng nhập, xem thông tin cá nhân, các nội dung kiểm tra đã tham gia và điểm của chính mình |

## Phạm vi chức năng

1. **Đăng nhập, đăng xuất, đổi mật khẩu.** Mật khẩu mã hóa BCrypt, chỉ yêu cầu độ dài tối thiểu. Tài khoản có mật khẩu do người khác đặt (mới cấp hoặc vừa được đặt lại) phải đặt mật khẩu mới ở lần đăng nhập đầu rồi mới dùng được các chức năng khác. Không khóa tài khoản và không trì hoãn khi nhập sai. Chạy bằng HTTP, không có HTTPS. Chủ dự án đã chấp nhận các rủi ro này (xem ARCHITECTURE.md, R4).
2. **Cây đơn vị.** Seed sẵn trong `V1__init.sql` theo danh sách đơn vị ban đầu của chủ dự án. Cấp 1 có mã `[xxxx]`, Cấp 2 có mã `[mã cha].[xxxx]`. Chỉ Super Admin thêm, sửa, khóa đơn vị.
3. **Quản lý user.**
   - Thông tin gồm họ đệm, tên, **mã định danh (duy nhất, hệ thống tự cấp dạng `ND000001` nếu để trống)**, **đối tượng** (SQ, QNCN, HSQ-CS; bắt buộc khi thêm người mới), cấp bậc, chức vụ, đơn vị, tài khoản, quyền, trạng thái và chuyên ngành (chọn nhiều bằng ô đánh dấu).
   - Admin được cấp quyền admin cho user trong nhánh mình, không vượt phạm vi.
   - Cấp bậc hiển thị bằng ký hiệu trong form và danh sách, bằng tên đầy đủ khi in thông tin cá nhân.
   - Trạng thái gồm activate, deactivate, delete (xóa mềm).
   - Có thể chuyển đơn vị. Quyền của admin thay đổi theo nhánh mới.
   - Tài khoản đăng nhập cấp ở trang chi tiết người dùng (không ở form sửa). Tài khoản mới có mật khẩu mặc định `123456789`, người dùng phải đổi ở lần đăng nhập đầu.
   - Trường "Loại" (cá nhân hoặc đơn vị) nằm trong bảng user. User loại **đơn vị** liên kết tới một đơn vị có sẵn trong cây, không có họ tên, cấp bậc, mã định danh hay tài khoản. Hệ thống tự tạo khi lần đầu đưa đơn vị đó vào nội dung kiểm tra.
   - Danh sách người dùng lọc nhiều giá trị cùng lúc ở mọi tiêu chí (đơn vị, đối tượng, cấp bậc, chức vụ, chuyên ngành, trạng thái, quyền).
4. **Phân quyền theo nhánh.** Kiểm tra ở backend cho mọi API.
5. **Danh mục.** Gồm cấp bậc, chức vụ, chuyên ngành, lý do không kiểm tra. Super Admin quản lý.
6. **Đợt kiểm tra và nội dung kiểm tra.** Cấu trúc Đợt → Nội dung kiểm tra, mỗi nội dung có tên, ngày và địa điểm kiểm tra. Thêm, sửa nội dung bằng hộp thoại, gán giám khảo ngay trong hộp thoại (tìm theo tên, mã hoặc tài khoản).
   - Super Admin tạo nội dung kiểm tra, kể cả qua Excel. Giám khảo mặc định là Super Admin, có thể gán nhiều người.
   - Danh sách người học gồm cá nhân và đơn vị. Super Admin và giám khảo của nội dung đều được thêm người trực tiếp, mỗi lần thêm có ghi nhật ký.
   - Người đã có điểm chỉ Super Admin được gỡ khỏi nội dung kiểm tra, kèm lý do. Điểm vẫn được giữ trong dữ liệu nhưng không tính vào thống kê.
   - Người có tên trong nội dung kiểm tra mà không kiểm tra thì chọn lý do từ danh sách và có ô ghi chú.
   - Khóa (đóng) đợt thì mọi nội dung của đợt cũng đóng; mở lại đợt thì nội dung trở về trạng thái riêng (quyết định 35).
   - Nội dung còn người chưa có điểm (và chưa ghi lý do vắng) được làm nổi bật kèm số người còn lại, có bộ lọc "Chưa chấm xong" (quyết định 34).
7. **Luồng đề nghị.** Admin đề nghị thêm/xóa người học. Giám khảo hoặc Super Admin xác nhận hoặc hủy. Có thông báo trong app (chuông, số đếm, danh sách chờ). Bấm thông báo đề nghị mới thì mở cả danh sách đề nghị đang chờ, đánh dấu nội dung liên quan.
   - **Bảng phân công** (ma trận người × nội dung kiểm tra) là giao diện chính để thêm người: hàng là người của một đơn vị, cột là các nội dung kiểm tra của một đợt, bấm ô để chọn. Có bộ lọc đơn vị, đối tượng, chuyên ngành, tìm tên hoặc mã định danh. Super Admin và giám khảo xem toàn bộ danh sách người và thêm ngay (giám khảo chỉ với nội dung mình được gán); Admin chỉ xem người thuộc cấp quản lý của mình và gửi đề nghị (một thông báo gộp cho mỗi nội dung). Có thể phân công hàng loạt bằng Excel (quyết định 37).
8. **Chấm điểm.** Nhập trực tiếp hoặc qua Excel.
   - Mỗi người học chỉ có một điểm cuối, một giám khảo nhập một lần, hệ thống lưu người nhập.
   - Điểm có 1 chữ số thập phân.
   - Xếp loại tự tính: <5.0 Không đạt, 5.0–6.5 Đạt, 6.6–7.9 Khá, ≥8.0 Giỏi.
   - Chỉ Super Admin được sửa điểm, mỗi lần sửa đều ghi log kèm lý do.
   - Super Admin chấm được mọi nội dung của một đợt bằng một file Excel, bố cục như file phân công: mỗi đơn vị một sheet, cột là các nội dung của đợt, ô là điểm (quyết định 41).
9. **Trang cá nhân.** User xem thông tin của mình, các nội dung kiểm tra đã tham gia và điểm.
10. **Tìm kiếm và thống kê** trong phạm vi quyền, biểu đồ vẽ bằng SVG:
    - Tỷ lệ xếp loại theo đơn vị (kèm điểm trung bình theo đơn vị).
    - Thống kê điểm theo nội dung kiểm tra.
    - Danh sách chưa kiểm tra hoặc không kiểm tra, kèm lý do.
    - Lịch sử điểm của một người, lọc được theo khoảng thời gian kiểm tra, đợt, tình trạng, xếp loại.
    - Có bộ lọc theo đơn vị (chọn nhiều), đợt, nội dung kiểm tra và thời gian.
    - Trang chủ của Admin, Super Admin có biểu đồ tổng quan, trong đó kết quả cá nhân và kết quả tập thể đơn vị là hai biểu đồ riêng.
    - Thống kê theo đơn vị tính theo đơn vị **tại thời điểm tham gia**, không đổi khi người đó chuyển đơn vị về sau.
    - Điểm cá nhân và điểm đơn vị được thống kê riêng, không trộn. Thành viên của một đơn vị không xem được điểm của đơn vị mình.
11. **Nhập, xuất Excel (.xlsx).** Dùng Apache POI cho user, nội dung kiểm tra, phân công và điểm. Mọi file mẫu có sheet hướng dẫn đứng đầu và báo lỗi theo từng dòng. Nhập user cấp được tài khoản và quyền (Người dùng hoặc Admin, mặc định Người dùng; Admin thiếu tài khoản thì báo lỗi ngay khi kiểm tra). Xuất được điểm của một nội dung kiểm tra ra Excel cùng định dạng với các file mẫu, chỉ gồm người học trong phạm vi người xuất.
12. **In ấn.** In bằng CSS print trên trình duyệt (khổ A4, có thể lưu PDF từ trình duyệt): thông tin cá nhân và kết quả một nội dung kiểm tra, cùng bố cục với file Excel, đen trắng. In đúng theo bộ lọc đang chọn ở trang nguồn (quyết định 42). Trang thống kê không có bản in.
13. **Xóa mềm tuyệt đối.** Không xóa cứng dữ liệu nào.
14. **Audit log.** Ghi log thao tác quan trọng: sửa điểm, đổi quyền, đổi đơn vị, xóa mềm, thêm hoặc gỡ người học, nhập Excel, đăng nhập thành công và thất bại, sao lưu. Nhật ký chỉ thêm, không sửa, không xóa.
15. **Sao lưu.** Tự động hằng ngày (giờ, số bản giữ và thư mục đổi được), mặc định giữ 5 bản gần nhất, thêm một bản trước mỗi lần nâng cấp. Super Admin tạo và tải bản sao lưu thủ công, xem tình trạng hệ thống ở trang Hệ thống. Thư mục sao lưu cấu hình được để trỏ sang ổ đĩa khác.
16. **Đóng gói.** Bộ cài Windows kèm JRE, chạy như Windows Service. Tự khởi tạo DB và tài khoản Super Admin lần đầu. Frontend không phụ thuộc CDN hay font ngoài.
17. **Giao diện responsive, phong cách Hải quân.**
    - Hiển thị tốt trên điện thoại, máy tính bảng, laptop và màn hình lớn. Thiết kế ưu tiên điện thoại trước. Không có thanh cuộn ngang toàn trang ở mọi độ rộng từ 360 px trở lên.
    - Mọi chức năng đều dùng được trên điện thoại. Các việc được tối ưu riêng cho điện thoại: đăng nhập, xem hồ sơ và điểm của mình, thông báo, xem nội dung kiểm tra, chấm điểm, duyệt đề nghị. Nhập Excel, thống kê chi tiết và quản lý hàng loạt vẫn dùng được nhưng tối ưu cho máy tính bảng và máy tính.
    - Màu Hải quân Việt Nam (xanh hải quân sẫm, trắng và vàng kim, sọc xanh trắng kiểu cổ áo thủy thủ ở trang đăng nhập) theo kiểu dashboard quản trị hiện đại, mật độ cao (tham khảo AdminKit, Hope UI, Admindek): thanh bên xanh sẫm chia nhóm, đầu trang gọn, bảng dòng thấp có nút thao tác ngay trên dòng, thẻ trắng bo góc, đổ bóng nhẹ, trang chủ có thẻ số liệu và biểu đồ, chữ thường dễ đọc, độ tương phản cao. Không dùng quốc huy, quân hiệu, cấp hiệu hay hải quân kỳ khi chưa được cấp phép (biểu tượng là khiên và mỏ neo tự vẽ).
    - Điện thoại truy cập qua Wi-Fi hoặc mạng nội bộ cùng với máy chủ, không cần Internet.

## Ngoài phạm vi

- Xuất Excel và PDF từ server cho báo cáo thống kê (hiện chỉ xuất điểm một nội dung kiểm tra ra Excel; in qua trình duyệt).
- Nhiều giám khảo chấm riêng rồi tính trung bình.
- Thống kê theo cấp bậc, chức vụ, chuyên ngành và so sánh giữa các kỳ.
- Quản lý danh mục qua giao diện đầy đủ (hiện chỉ Super Admin thêm, sửa, khóa mục).
- Khôi phục bản sao lưu bằng giao diện (khôi phục thủ công bằng cách thay file DB khi dừng dịch vụ, xem [OPERATIONS-GUIDE.md](OPERATIONS-GUIDE.md) mục 9).
- Khóa tài khoản hoặc trì hoãn khi nhập sai, chính sách mật khẩu nâng cao. Chủ dự án đã chọn không làm.
- Chức năng xóa nhật ký thao tác (quyết định 39).
- Bộ cài Linux/Docker.
- Ứng dụng di động riêng (native) hoặc PWA dùng ngoại tuyến. PWA cần HTTPS nên không khả thi khi hệ thống chạy HTTP. Chỉ cần web responsive.

## Tiêu chí hoàn thành

- Cài trên máy Windows sạch chỉ qua vài bước, không cần cài Java. Máy khác trong LAN truy cập được qua trình duyệt, chạy không cần Internet.
- Test phân quyền: admin nhánh A không đọc, sửa hay thấy được user, người học trong nội dung kiểm tra, điểm của nhánh khác hoặc cấp cao hơn, kể cả khi gọi API trực tiếp (thông tin chung của nội dung kiểm tra thì Admin xem được mọi nội dung, quyết định 33). Sau khi chuyển đơn vị, quyền thay đổi đúng như mô tả.
- Một người không thể bị chấm điểm lần hai, trừ khi Super Admin sửa. Mọi lần sửa đều có log: ai, lúc nào, giá trị cũ → mới.
- Xếp loại đúng ở các điểm biên 4.9, 5.0, 6.5, 6.6, 7.9, 8.0.
- Không có thao tác xóa cứng. Dữ liệu đã xóa mềm không hiện trong danh sách nhưng vẫn nằm trong thống kê lịch sử.
- Nhập Excel đúng với tập mẫu khoảng 1.000 user, khớp người theo mã định danh, báo lỗi theo từng dòng, ghi tất cả hoặc không ghi gì.
- Giám khảo thêm được người vào nội dung kiểm tra mình từ mọi đơn vị nhưng chỉ nhận về tên, cấp bậc, đơn vị, mã. Giám khảo không thêm được vào nội dung kiểm tra của người khác.
- Người đã có điểm bị gỡ khỏi nội dung kiểm tra thì không còn xuất hiện trong thống kê và lịch sử cá nhân, nhưng điểm vẫn nằm trong dữ liệu. Chỉ Super Admin gỡ được.
- Sau khi một user chuyển đơn vị, thống kê cũ của đơn vị trước đó không thay đổi.
- Sao lưu tự động chạy đúng lịch, chỉ giữ số bản đã cấu hình (mặc định 5 bản gần nhất), và một bản sao lưu mở lại được thành một DB nguyên vẹn.
- Thông báo đề nghị hiện đúng cho người có quyền xử lý, và mất số đếm khi đã xử lý.
- Frontend chỉ dùng HTML, CSS, JS thuần. Màn hình chính phản hồi dưới 2 giây với khoảng 2.000 user và 50 người truy cập cùng lúc.
- Kiểm tra ở độ rộng 360, 390, 768, 1024, 1366, 1920 px: không cuộn ngang toàn trang, không chữ bị cắt hoặc chồng lên nhau, mọi nút bấm được (vùng chạm tối thiểu 44×44 px).
- Trên điện thoại hoàn thành trọn vẹn bằng cảm ứng: đăng nhập, xem điểm của mình, đọc thông báo, duyệt đề nghị, chấm điểm.
- Chạy đúng trên Chrome, Edge, Firefox bản gần đây (Windows), Chrome (Android) và Safari (iOS 15 trở lên).
- Giao diện đúng phong cách đã thống nhất. Chữ đạt độ tương phản tối thiểu mức AA của WCAG. Xếp loại không chỉ phân biệt bằng màu mà còn có chữ.
- Bản in vẫn đen trắng, sạch, không dính màu nền của giao diện.

## Quyết định đã chốt

| # | Chủ đề | Quyết định |
|---|---|---|
| 1 | Mã đơn vị | Cấp 1 chỉ có `[xxxx]`, Cấp 2 là `[mã cha].[xxxx]` |
| 2 | Admin Thủ trưởng Lữ đoàn | Chỉ quản lý đơn vị mình |
| 3 | Quản lý đơn vị | Chỉ Super Admin, mỗi đơn vị nhiều admin |
| 4 | Cấp admin | Tự suy ra từ đơn vị |
| 5 | Trường "Loại" | Đối tượng kiểm tra có thể là cá nhân hoặc đơn vị. Đặt trong bảng user, user loại đơn vị liên kết tới đơn vị có sẵn và do hệ thống tự tạo (xem 23, 24) |
| 6 | Cấu trúc kiểm tra | Đợt → Nội dung kiểm tra. Giao diện gọi "lớp" là nội dung kiểm tra, "giáo viên" là giám khảo; không có trường môn |
| 7 | Nhiều giám khảo | Một điểm cuối, một giám khảo nhập một lần |
| 8 | Thang điểm | 0–10, một chữ số thập phân |
| 9 | Lý do không kiểm tra | Chọn từ danh sách và ghi chú |
| 10 | Đề nghị | Có thông báo trong app. Bấm thông báo đề nghị mới mở cả danh sách đề nghị đang chờ |
| 11 | Thống kê | Lịch sử điểm, tỷ lệ xếp loại theo đơn vị (kèm điểm trung bình), danh sách chưa kiểm tra, thống kê theo nội dung kiểm tra |
| 12 | Báo cáo | In bằng CSS print |
| 13 | Máy chủ | Windows, LAN nội bộ không Internet |
| 14 | Quy mô | Dưới 2.000 user, dưới 50 người cùng lúc |
| 15 | File nhập | Apache POI, `.xlsx` |
| 16 | Audit và sao lưu | Log thao tác quan trọng. Sao lưu: xem 25 |
| 17 | Mật khẩu | Chỉ mật khẩu tối thiểu, không khóa. Tài khoản mới cấp (form, trang chi tiết, Excel) có mật khẩu mặc định `123456789`. Mật khẩu do người khác đặt (cấp tài khoản, nhập Excel, đặt lại mật khẩu) phải đổi ở lần đăng nhập đầu; trước khi đổi chỉ được đổi mật khẩu hoặc đăng xuất |
| 18 | Mã định danh | Mỗi user cá nhân có mã định danh duy nhất, dùng để khớp khi nhập Excel. Khi tạo, để trống thì hệ thống tự cấp `ND` + số thứ tự 6 chữ số (`ND000001`); vẫn cho nhập hoặc sửa thành số hiệu chính thức, không được trùng. Người đã có mã giữ nguyên |
| 19 | Cấp quyền admin | Admin được cấp quyền admin cho user trong nhánh mình, không vượt phạm vi |
| 20 | Giám khảo thêm người | Giám khảo được thêm trực tiếp người vào nội dung kiểm tra mình |
| 21 | Mức lộ thông tin khi tìm | Tìm toàn Lữ đoàn, chỉ hiện tên, cấp bậc, đơn vị, mã |
| 22 | Thống kê theo đơn vị | Theo đơn vị tại thời điểm tham gia |
| 23 | Điểm của đơn vị | User thường không xem điểm của đơn vị mình, chỉ admin trong phạm vi |
| 24 | User loại đơn vị | Liên kết tới đơn vị có sẵn, bỏ các trường cá nhân, hệ thống tự tạo |
| 25 | Sao lưu | Tự động hằng ngày, mặc định giữ 5 bản gần nhất, thêm bản trước nâng cấp |
| 26 | Chống dò mật khẩu | Không trì hoãn, không khóa. Chỉ ghi log các lần nhập sai |
| 27 | HTTPS | Không làm HTTPS |
| 28 | Gỡ người đã có điểm | Chỉ Super Admin, kèm lý do. Điểm giữ trong dữ liệu nhưng loại khỏi thống kê |
| 29 | Responsive | Web hiển thị tốt trên điện thoại, máy tính bảng, máy tính. Mọi chức năng dùng được trên điện thoại |
| 30 | Phong cách giao diện | Hải quân: xanh hải quân, trắng, vàng kim, sọc cổ áo thủy thủ. Hình khối kiểu dashboard quản trị hiện đại, mật độ cao (mẫu AdminKit, Hope UI, Admindek): bo góc, đổ bóng nhẹ, chữ thường, thanh bên chia nhóm, bảng dòng thấp có thao tác trên dòng, đầu trang gọn. Dashboard của Super Admin và Admin có biểu đồ (vòng, cột, thanh). HTML, CSS, JS thuần. Không dùng quân hiệu, cấp hiệu, hải quân kỳ thật |
| 31 | Trình duyệt hỗ trợ | Chrome, Edge, Firefox bản gần đây, Chrome Android, Safari iOS 15 trở lên (giả định, cần xác nhận) |
| 32 | Bảng phân công | Giao diện thêm người theo ma trận người x nội dung kiểm tra của một đợt. Phạm vi xem danh sách: Super Admin, giám khảo toàn bộ; Admin theo nhánh. Chỉ lộ tên, cấp bậc, đơn vị, mã, chuyên ngành |
| 33 | Admin xem nội dung kiểm tra | Admin xem được danh sách và thông tin chung của mọi nội dung kiểm tra, kể cả nội dung chưa có người thuộc đơn vị mình; trong chi tiết chỉ thấy người học, điểm, thống kê thuộc đơn vị mình quản lý |
| 34 | Theo dõi chấm điểm | Nội dung kiểm tra còn người chưa có điểm (và chưa ghi lý do vắng) được làm nổi bật ở danh sách nội dung và trang chủ, kèm số người còn lại và bộ lọc "Chưa chấm xong", để giám khảo theo dõi |
| 35 | Khóa đợt kiểm tra | Đóng (khóa) đợt thì mọi nội dung kiểm tra của đợt cũng đóng: không thêm người, không nhận đề nghị, không mở lại riêng từng nội dung. Mở lại đợt thì nội dung trở về trạng thái riêng |
| 36 | Đối tượng | Mỗi người có trường Đối tượng: SQ (sĩ quan), QNCN (quân nhân chuyên nghiệp), HSQ-CS (hạ sĩ quan - chiến sĩ), bắt buộc khi thêm người mới (form, Excel); lọc được ở danh sách người dùng và bảng phân công |
| 37 | Phân công theo đơn vị và bằng Excel | Bảng phân công xem từng đơn vị một (đơn vị nhỏ nhất). Phân công hàng loạt bằng file Excel: mỗi đơn vị một sheet, cột là nội dung kiểm tra đang mở, ghi x; quyền như bảng (Super Admin, giám khảo làm ngay; Admin gửi đề nghị; Admin chỉ có đơn vị mình quản lý; giám khảo chỉ có cột nội dung mình) |
| 38 | File Excel | Mọi file mẫu nhập Excel có sheet hướng dẫn là sheet đầu tiên. Xuất được điểm của một nội dung kiểm tra ra Excel cùng định dạng với các file mẫu; người xuất chỉ nhận người học trong phạm vi mình xem được |
| 39 | Chọn đơn vị và lọc | Chọn đơn vị dùng ô đánh dấu (chọn nhiều, có tìm nhanh) ở lọc thống kê, bảng phân công, tạo đề nghị cả đơn vị; chỉ nơi hợp lệ một đơn vị (thêm người dùng, đổi quyền, chuyển đơn vị) dùng radio. Bảng phân công không ghi số người của đơn vị trong lựa chọn. Danh sách người học của nội dung kiểm tra lọc được theo tình trạng, xếp loại, đối tượng. **Không làm chức năng xóa nhật ký**: nhật ký thao tác chỉ thêm, không sửa, không xóa |
| 40 | In ấn, sao lưu, quản trị | Trang in (thông tin cá nhân, kết quả một nội dung kiểm tra; thống kê không có bản in) cùng bố cục với file Excel của hệ thống, bản in đen trắng. Form thêm, sửa người dùng vừa một màn từ 1024 px. Super Admin có trang Hệ thống: tình trạng, sao lưu tự động hằng ngày (đổi được giờ, số bản giữ, thư mục), tạo và tải bản sao lưu, hướng dẫn khôi phục. Dịch vụ chạy bằng tài khoản ít quyền LocalService |
| 41 | Chấm điểm cả đợt bằng Excel | Chỉ Super Admin. File mẫu theo đợt: mỗi đơn vị (lúc tham gia) một sheet, dòng là người học (có dòng "Cả đơn vị" cho điểm tập thể), cột là mọi nội dung của đợt kể cả nội dung đã đóng, ô là điểm. Ô của người không có trong nội dung và ô đã có điểm bị khóa; không ghi đè điểm; ghi tất cả hoặc không |
| 42 | In theo bộ lọc | In kết quả một nội dung kiểm tra và in thông tin cá nhân theo đúng bộ lọc đang chọn ở trang nguồn (không lọc thì in tất cả). Bản in ghi rõ bộ lọc, số liệu tính trên phần được in; trang in có nút In tất cả |

## Điểm còn cần xác nhận

- **Mật khẩu.** Với mức tối thiểu, admin tạo hoặc đặt lại mật khẩu cho user trong phạm vi quản lý khi cần. Tài khoản mới cấp có mật khẩu mặc định `123456789` và phải đổi ở lần đăng nhập đầu.
- **Danh mục ban đầu.** Danh sách "lý do không kiểm tra" đang seed bốn giá trị (Ốm, Công tác, Nghỉ phép, Khác); đơn vị cần xác nhận hoặc để Super Admin tự điều chỉnh khi vận hành.
- **Mã đơn vị.** Yêu cầu ban đầu chỉ cho định dạng `[xxxx]`, không cho mã thật. Mã trong seed là mã tạm đánh số theo thứ tự; vì mã đơn vị không đổi được, cần đơn vị xác nhận hệ mã chính thức trước khi triển khai thật (ARCHITECTURE 2.8, S8).
- **Bảng màu và logo.** Màu cụ thể (ARCHITECTURE 4.1) và logo của đơn vị cần được duyệt. Chưa có logo thì dùng khiên và mỏ neo tự vẽ.
- **Trình duyệt tối thiểu.** Giả định hỗ trợ Safari trên iOS 15 trở lên. Nếu máy của đơn vị cũ hơn thì cần cho biết để giảm bớt tính năng CSS hiện đại.

## Rủi ro đã được chấp nhận

- Không có HTTPS, không trì hoãn hay khóa khi nhập sai mật khẩu. Người trong mạng nội bộ có thể dò hoặc nghe lén mật khẩu. Biện pháp còn lại là ghi log mọi lần nhập sai và chọn mật khẩu Super Admin lúc cài đặt.
- Mọi tài khoản mới cấp có chung mật khẩu mặc định `123456789`. Hệ thống bắt đổi ở lần đăng nhập đầu, nhưng ai biết tên tài khoản của một người chưa đăng nhập lần nào thì đăng nhập (và đặt mật khẩu) trước được người đó.
- Giám khảo thấy được danh sách người của toàn Lữ đoàn khi tìm ứng viên (chỉ bốn trường: tên, cấp bậc, đơn vị, mã).
- Vì chạy HTTP nên không làm được PWA cài lên điện thoại, và một số tính năng trình duyệt chỉ có ở kết nối an toàn (HTTPS) sẽ không dùng được.
