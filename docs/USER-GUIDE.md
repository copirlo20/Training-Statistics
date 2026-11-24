# Hướng dẫn sử dụng

Hướng dẫn dành cho người dùng cuối của hệ thống quản lý và thống kê huấn luyện, chia theo vai trò: **Người dùng**, **Giám khảo**, **Admin** và **Super Admin**. Cài đặt, sao lưu và xử lý sự cố máy chủ nằm ở [OPERATIONS-GUIDE.md](OPERATIONS-GUIDE.md); phân quyền chi tiết ở [ARCHITECTURE.md](ARCHITECTURE.md) mục 5.

## Mục lục

1. [Làm quen](#1-làm-quen)
2. [Khái niệm cần biết](#2-khái-niệm-cần-biết)
3. [Người dùng](#3-người-dùng)
4. [Giám khảo](#4-giám-khảo)
5. [Admin](#5-admin)
6. [Super Admin](#6-super-admin)
7. [Nhập dữ liệu bằng Excel](#7-nhập-dữ-liệu-bằng-excel)
8. [In báo cáo](#8-in-báo-cáo)
9. [Quy trình mẫu cho một đợt kiểm tra](#9-quy-trình-mẫu-cho-một-đợt-kiểm-tra)
10. [Thông báo và câu hỏi thường gặp](#10-thông-báo-và-câu-hỏi-thường-gặp)
11. [Bảng tóm tắt quyền](#11-bảng-tóm-tắt-quyền)

---

## 1. Làm quen

### 1.1 Ai làm được gì

| Vai trò | Dùng hệ thống để |
|---|---|
| **Người dùng** | Xem thông tin cá nhân, các nội dung kiểm tra mình đã tham gia và điểm của mình |
| **Giám khảo** | Quản lý các nội dung kiểm tra mình được giao: thêm người học, chấm điểm, ghi lý do vắng, duyệt đề nghị |
| **Admin** (Cấp 1, Cấp 2) | Quản lý nhân sự trong đơn vị mình và đơn vị con; theo dõi kết quả, thống kê; đề nghị thêm hoặc gỡ người học |
| **Super Admin** | Toàn quyền: đơn vị, danh mục, đợt và nội dung kiểm tra, chấm và sửa điểm, nhật ký, sao lưu |

**Giám khảo không phải là một vai trò tài khoản.** Một người dùng thường hoặc một Admin đều có thể được giao làm giám khảo của một nội dung kiểm tra; khi đó họ có thêm quyền giám khảo đúng với nội dung đó, cộng với quyền vốn có. Phần "Giám khảo" ở mục 4 áp dụng cho bất kỳ ai được giao.

Mỗi người chỉ thấy những gì thuộc phạm vi của mình. Admin của một đơn vị chỉ thấy người và kết quả của đơn vị đó và các đơn vị con. Những gì ngoài phạm vi sẽ báo "không tìm thấy", không phải "không có quyền".

### 1.2 Đăng nhập, đăng xuất

1. Mở địa chỉ máy chủ trong trình duyệt (ví dụ `http://192.168.0.10:8080/`). Dùng được trên máy tính, máy tính bảng và điện thoại cùng mạng nội bộ; không cần Internet.
2. Nhập **tài khoản** và **mật khẩu** do quản trị cấp, bấm đăng nhập. Sai tài khoản hoặc mật khẩu thì báo "Tài khoản hoặc mật khẩu không đúng." ngay trên form (không cho biết sai ở đâu, để tránh dò tài khoản).
   **Lần đăng nhập đầu** (tài khoản mới cấp hoặc vừa được Admin đặt lại mật khẩu), hệ thống không vào ngay mà hiện form **đặt mật khẩu mới**: nhập mật khẩu mới hai lần (tối thiểu 8 ký tự, khác mật khẩu đang dùng) → **Đổi mật khẩu và tiếp tục**. Chưa đổi thì không dùng được chức năng nào khác; muốn để lúc khác thì bấm **Đăng xuất**.
3. Đăng xuất: bấm tên hoặc ảnh đại diện ở góc trên bên phải → **Đăng xuất**. Trên điện thoại: thanh tab đáy → **Thêm** → Đăng xuất.
4. Không thao tác trong khoảng 30 phút thì phiên tự hết hạn, hệ thống đưa về trang đăng nhập.

Người chưa được cấp tài khoản không đăng nhập được: nhờ Admin hoặc Super Admin cấp.

### 1.3 Mật khẩu

- **Tài khoản mới cấp có mật khẩu mặc định `123456789`.** Ở lần đăng nhập đầu, hệ thống bắt bạn đặt mật khẩu mới (mục 1.2). Ai biết tên tài khoản của bạn đều đăng nhập được bằng mật khẩu mặc định cho tới lúc đó, nên **đăng nhập và đổi mật khẩu ngay khi được cấp**.
- Đổi mật khẩu: **Đổi mật khẩu** (menu Cá nhân, hoặc menu ảnh đại diện) → nhập mật khẩu hiện tại, mật khẩu mới (tối thiểu 8 ký tự, tối đa 72 byte) → lưu. Thấy "Đã đổi mật khẩu." là xong.
- Quên mật khẩu: nhờ Admin trong nhánh của bạn đặt lại ở trang chi tiết người dùng. Nếu là Super Admin quên mật khẩu, người quản trị máy chủ đặt lại bằng công cụ trên máy chủ (OPERATIONS-GUIDE mục 10).
- Hệ thống **không khóa tài khoản khi nhập sai**, nhưng ghi lại mọi lần đăng nhập thất bại. Chọn mật khẩu khó đoán, đừng chia sẻ.

### 1.4 Màn hình

**Máy tính (từ 1024 px):** thanh bên trái chia nhóm *Tổng quan, Huấn luyện, Quản trị, Cá nhân*; nút ☰ thu gọn thanh bên thành các biểu tượng (trình duyệt nhớ lựa chọn). Thanh trên có đường dẫn trang, **chuông thông báo** và **ảnh đại diện** (mở menu Hồ sơ, Đổi mật khẩu, Đăng xuất).

**Điện thoại:** thanh tab ở đáy với các mục chính (Trang chủ, Thống kê hoặc Nội dung, Hồ sơ...), các mục còn lại nằm trong **Thêm**. Bảng tự chuyển thành danh sách thẻ: nhãn bên trái, giá trị bên phải. Mọi chức năng đều dùng được trên điện thoại; chấm điểm, duyệt đề nghị, xem điểm, thông báo được thiết kế riêng cho điện thoại.

**Các mục menu theo vai trò:**

| Mục | Người dùng | Giám khảo | Admin | Super Admin |
|---|---|---|---|---|
| Trang chủ, Thông báo, Hồ sơ, Đổi mật khẩu | ✅ | ✅ | ✅ | ✅ |
| Nội dung kiểm tra | nội dung mình tham gia | nội dung mình dạy | tất cả (người học chỉ trong nhánh) | tất cả |
| Phân công, Đề nghị | không có | ✅ | ✅ | ✅ |
| Thống kê | không có | thống kê nội dung mình | ✅ | ✅ |
| Người dùng | không có | không có | ✅ | ✅ |
| Đợt kiểm tra, Đơn vị, Danh mục, Hệ thống, Nhật ký | không có | không có | không có | ✅ |

Mục nào không thấy trong menu là bạn không có quyền với mục đó.

### 1.5 Thao tác chung trên danh sách

- **Tìm kiếm** không cần gõ dấu: "nguyen van" tìm ra "Nguyễn Văn ...". Tìm được theo họ tên, mã định danh, tài khoản.
- **Bộ lọc** chọn nhiều giá trị cùng lúc (ô đánh dấu, có ô tìm nhanh); lọc ngay khi chọn; nút **Bỏ lọc** trả lại đầy đủ. Trong cùng một tiêu chí là "hoặc", giữa các tiêu chí là "và".
- **Sắp xếp:** bấm tiêu đề cột trên máy tính.
- **Nút trên dòng:** biểu tượng Xem, Sửa, Khóa, Xóa... nằm ngay ở cột Thao tác. Khóa và xóa luôn có hộp thoại xác nhận.
- **Không có xóa vĩnh viễn:** "Xóa" là xóa mềm, dữ liệu không mất và quản trị khôi phục được.

## 2. Khái niệm cần biết

| Khái niệm | Ý nghĩa |
|---|---|
| **Đơn vị** | Cây hai cấp. Cấp 1 là phòng, ban lớn hoặc tàu, hải đội; Cấp 2 là đơn vị con (mã dạng `0002.0001`). Admin quản lý đơn vị của mình và các đơn vị con |
| **Đợt kiểm tra** | Một đợt gồm nhiều nội dung kiểm tra (ví dụ "Kiểm tra quý IV"). Đợt có thể **đóng (khóa)**: khi đó mọi nội dung của đợt cũng đóng |
| **Nội dung kiểm tra** | Một buổi hoặc một môn kiểm tra cụ thể trong đợt, có tên, ngày, địa điểm, danh sách giám khảo và người học |
| **Người học** | Người (hoặc cả đơn vị) có tên trong một nội dung kiểm tra. Mỗi người học chỉ có **một điểm** |
| **Đối tượng kiểm tra** | **Cá nhân** hoặc **tập thể đơn vị**. Dòng "Cả đơn vị" là điểm tập thể của một đơn vị. Điểm cá nhân và điểm tập thể được thống kê riêng, không trộn |
| **Đối tượng (SQ, QNCN, HSQ-CS)** | Phân loại của mỗi người: sĩ quan, quân nhân chuyên nghiệp, hạ sĩ quan - chiến sĩ |
| **Mã định danh** | Số hiệu duy nhất của mỗi người. Để trống khi thêm người thì hệ thống tự cấp (`ND000001`...) |
| **Giám khảo** | Người được giao một nội dung: thêm người học, chấm điểm, duyệt đề nghị cho đúng nội dung đó |
| **Đề nghị** | Admin không thêm trực tiếp người vào nội dung kiểm tra mà gửi đề nghị thêm hoặc gỡ; giám khảo hoặc Super Admin duyệt |

**Tình trạng của một người học:**

| Tình trạng | Nghĩa |
|---|---|
| **Đã có điểm** | Đã được chấm |
| **Không kiểm tra** | Có lý do vắng (Ốm, Công tác, Nghỉ phép, Khác) kèm ghi chú |
| **Chưa kiểm tra** | Chưa có điểm và chưa ghi lý do |

**Xếp loại** tự tính từ điểm (thang 10, một chữ số thập phân), luôn hiện bằng chữ kèm màu:

| Điểm | Xếp loại |
|---|---|
| Dưới 5.0 | Không đạt |
| 5.0 đến 6.5 | Đạt |
| 6.6 đến 7.9 | Khá |
| Từ 8.0 | Giỏi |

**Trạng thái tài khoản:** *Hoạt động* (đăng nhập được), *Đã khóa* (không đăng nhập được, dữ liệu còn), *Đã xóa* (xóa mềm, không hiện trong danh sách mặc định nhưng kết quả cũ vẫn nằm trong thống kê lịch sử).

## 3. Người dùng

Dành cho người có tài khoản thường, chỉ xem dữ liệu của chính mình.

### 3.1 Trang chủ

Hiển thị các thẻ số liệu của bạn: số nội dung kiểm tra đã tham gia, số đã có điểm, số chưa kiểm tra, thông báo chưa đọc; danh sách "Nội dung kiểm tra của tôi" kèm điểm; thông báo mới.

### 3.2 Hồ sơ và điểm của tôi

Mở **Hồ sơ** (menu Cá nhân). Trang gồm:

- **Thông tin cá nhân:** họ tên, mã định danh, đối tượng, cấp bậc, chức vụ, đơn vị, chuyên ngành, ngày sinh, điện thoại. Sai thông tin thì báo Admin của đơn vị để sửa.
- **Tài khoản:** tên tài khoản và nút **Đổi mật khẩu**.
- **Nội dung kiểm tra đã tham gia và điểm:** gom theo đợt (đợt mới nhất trước); mỗi đợt có số nội dung, số đã có điểm và điểm trung bình. Mỗi dòng gồm tên nội dung, ngày kiểm tra, kết quả (điểm kèm xếp loại, hoặc "Chưa kiểm tra", hoặc "Không kiểm tra" kèm lý do vắng; nhãn **Đã sửa** nếu điểm từng được sửa).

Lọc kết quả: tìm theo tên nội dung; lọc theo khoảng ngày kiểm tra (từ ngày, đến ngày); chọn nhiều đợt, tình trạng, xếp loại. Nội dung chưa đặt ngày bị loại khi bạn đã chọn khoảng ngày.

Bạn **không thấy** điểm tập thể của đơn vị mình và không thấy nội dung mà bạn đã bị gỡ khỏi.

### 3.3 In thông tin cá nhân

Bấm **In thông tin cá nhân** ở trang Hồ sơ. Nếu đang lọc kết quả thì bản in chỉ gồm phần đang lọc và ghi rõ dòng "Bộ lọc"; bấm **In tất cả (bỏ lọc)** để in đầy đủ. Dùng hộp thoại in của trình duyệt để in giấy A4 hoặc lưu PDF (xem mục 8).

### 3.4 Thông báo

Biểu tượng **chuông** ở thanh trên hiện số thông báo chưa đọc. Bấm chuông xem nhanh 5 thông báo mới; mở trang **Thông báo** để xem hết, lọc "Tất cả" hoặc "Chưa đọc", bấm **Đánh dấu tất cả đã đọc**. Bấm một thông báo để đi tới nội dung liên quan.

## 4. Giám khảo

Áp dụng cho bất kỳ ai được Super Admin giao làm giám khảo của một nội dung kiểm tra. Bạn thấy thêm menu **Nội dung kiểm tra** (các nội dung bạn dạy), **Phân công** và **Đề nghị**.

### 4.1 Mở nội dung kiểm tra của mình

**Nội dung kiểm tra** → danh sách các nội dung bạn là giám khảo hoặc học. Nút **Chỉ nội dung tôi làm giám khảo** lọc riêng nội dung của bạn. Cột **Tiến độ chấm** cho biết còn bao nhiêu người chưa chấm: nhãn cam "Còn N chưa chấm" hoặc xanh "Đã chấm xong", kèm thanh và số "x/y"; dòng còn người chưa chấm được tô nền cam. Bộ lọc **Chưa chấm xong** chỉ giữ các nội dung còn việc. Trang chủ cũng nhắc "N nội dung kiểm tra chưa chấm xong".

Bấm vào tên nội dung (hoặc **Xem chi tiết**) để mở trang chi tiết gồm: thông tin chung, danh sách giám khảo, và bảng **Người học trong nội dung kiểm tra** với hai tab **Đang tham gia** và **Đã gỡ**.

### 4.2 Tìm và lọc người học

Ở bảng người học: ô **Tìm nhanh** (họ tên, mã định danh hoặc đơn vị, không cần dấu), lọc nhiều **đơn vị lúc tham gia** (chọn đơn vị cha thì gồm đơn vị con), **Tình trạng**, **Xếp loại**, **Đối tượng**. Nhãn các lựa chọn kèm số người. Nút **Bỏ lọc** xóa mọi điều kiện.

### 4.3 Thêm người học

1. Ở trang chi tiết nội dung, bấm **Thêm người**.
2. Gõ ít nhất 2 ký tự (họ tên hoặc mã). Hệ thống tìm trên **toàn Lữ đoàn** nhưng chỉ hiện tên, cấp bậc, đơn vị và mã định danh.
3. Bấm **Thêm** ở người cần thêm. Dòng mới có tình trạng "Chưa kiểm tra".

Thêm cả một đơn vị làm đối tượng kiểm tra (điểm tập thể): bấm **Thêm đơn vị**, tìm tên đơn vị, bấm Thêm. Danh sách có dòng "Cả đơn vị".

Mỗi lần thêm đều được ghi nhật ký. Nội dung đã đóng (hoặc đợt đã khóa) thì không thêm được: báo "đóng".

Muốn thêm nhiều người cùng lúc theo đơn vị, dùng **Phân công** (mục 4.9).

### 4.4 Ghi lý do không kiểm tra

Với người chưa có điểm: bấm **Lý do vắng** ở dòng người đó, chọn lý do (Ốm, Công tác, Nghỉ phép, Khác), ghi chú nếu cần, lưu. Dòng chuyển sang "Không kiểm tra". Sửa hoặc xóa lý do bằng **Sửa lý do vắng**. Làm được cả khi nội dung đã đóng. Một người không thể vừa có điểm vừa có lý do vắng: khi chấm điểm, lý do vắng tự bị xóa.

### 4.5 Gỡ người khỏi nội dung

- Người **chưa có điểm**: bấm **Gỡ** (lý do không bắt buộc). Người đó chuyển sang tab **Đã gỡ** (chỉ Super Admin thấy tab này).
- Người **đã có điểm**: **giám khảo không gỡ được**, chỉ Super Admin gỡ kèm lý do. Điểm vẫn giữ trong hệ thống nhưng không còn tính vào thống kê.

### 4.6 Chấm điểm

**Chấm điểm** (nút ở trang chi tiết nội dung, hoặc biểu tượng ở danh sách) mở trang chấm điểm.

1. Trang gom người học **theo đơn vị lúc tham gia**, mỗi người một hàng gọn: "cấp bậc họ tên · mã định danh", ô điểm và ô xếp loại. Đầu mỗi nhóm ghi "x/y đã có điểm". Phía trên có ô tìm, lọc nhiều đơn vị, nút **Chưa có điểm / Tất cả** và thanh tiến độ "Đã có điểm x/y".
2. Gõ điểm vào ô của người học: số từ 0 đến 10, **tối đa một chữ số thập phân**, dùng dấu chấm hoặc dấu phẩy (6,5 hoặc 6.5). **Xếp loại hiện ngay** để bạn kiểm tra. Bấm Enter để nhảy sang người kế tiếp. Trên điện thoại ô điểm dùng bàn phím số.
3. Gõ sai định dạng (ví dụ `6.55`, `11`, chữ) thì ô báo lỗi ngay và không lưu được.
4. Bấm **Lưu điểm** ở thanh dính đáy. Hệ thống hiện bảng **xác nhận** gồm tên, đơn vị, điểm và xếp loại sẽ ghi của tất cả người vừa nhập. Kiểm kỹ rồi xác nhận.
5. Sau khi lưu, các dòng chuyển sang "đã lưu", **khóa ô nhập** và hiện điểm máy chủ đã ghi để bạn kiểm lại ngay. Điểm nào lỗi thì giữ lại trong ô và được liệt kê.

**Quy tắc quan trọng khi chấm điểm:**

- **Mỗi người chỉ được chấm một lần.** Lưu rồi thì giám khảo không sửa được; chỉ Super Admin sửa, kèm lý do. Vì vậy hãy dùng bước xác nhận để kiểm tra trước khi lưu.
- **Không chấm cho chính mình:** nếu bạn đồng thời là người học của nội dung, thẻ của bạn báo "Không tự chấm cho mình"; nhờ Super Admin chấm.
- Hai giám khảo lưu cùng lúc cho cùng một người thì chỉ một bên thành công, bên kia nhận thông báo người đó đã có điểm.
- **Nội dung đã đóng vẫn chấm điểm được** (đóng chỉ chặn thêm người và đề nghị mới).

Chấm điểm trên điện thoại đã được tối ưu: ô nhập lớn, bàn phím số, màn hình cuộn tới ô đang nhập khi bàn phím mở, bước xác nhận chống nhập nhầm.

### 4.7 Nhập điểm bằng Excel (một nội dung)

Phù hợp khi có nhiều người. Ở trang chi tiết nội dung, bấm **Nhập điểm Excel** → làm theo ba bước ở mục 7. File mẫu là danh sách đầy đủ của nội dung: người đã có điểm hiện sẵn điểm và bị khóa ô, dòng của chính bạn (nếu bạn là người học) bị khóa. Điền điểm cho người chưa có điểm; ô để trống thì bỏ qua.

### 4.8 Xuất điểm, in, thống kê

- **Xuất điểm Excel:** tải file `.xlsx` kết quả của nội dung (mã, họ tên, cấp bậc, đối tượng, đơn vị lúc tham gia, điểm, xếp loại, tình trạng, người và thời gian chấm), kèm dòng tổng hợp sĩ số và xếp loại.
- **In kết quả:** trang in khổ A4 ngang, cùng bố cục với file Excel, theo bộ lọc đang chọn (mục 8).
- **Thống kê:** thống kê của riêng nội dung này (vòng xếp loại, phân bố điểm theo từng khoảng 1 điểm, bảng theo đơn vị). Giám khảo chỉ xem được thống kê của nội dung mình.

### 4.9 Phân công (thêm nhiều người cùng lúc)

**Phân công** là bảng ma trận: hàng là người, cột là các nội dung của một đợt, bấm vào ô để chọn.

1. Chọn **Đợt kiểm tra**.
2. Lọc: **Đơn vị** (chọn nhiều, có tìm nhanh), **Đối tượng**, **Chuyên ngành**, ô tìm tên hoặc mã, nút **Đang mở / Tất cả** (có hiện cả nội dung đã đóng hay không). Bảng xem **từng đơn vị một**; dùng **Trước** và **Sau** ở dưới bảng để chuyển trang, rồi sang đơn vị kế.
3. Bấm ô của người ở cột nội dung để chọn: ô hiện "Sẽ thêm"; bấm ô đang tham gia để chọn "Sẽ gỡ". Số ở dòng đơn vị cho phép chọn cả đơn vị. Các lựa chọn được giữ khi bạn đổi trang hoặc đơn vị.
4. Bấm **Áp dụng** ở thanh dưới. Với giám khảo, thay đổi **có hiệu lực ngay**. Hệ thống hỏi xác nhận và báo kết quả từng thay đổi (thay đổi lỗi được liệt kê riêng, các thay đổi còn lại vẫn áp dụng).

Bạn chỉ thao tác được ở **cột nội dung mình là giám khảo**; các cột khác chỉ xem. Ô của người đã có điểm hiện khóa (gỡ ở trang nội dung, chỉ Super Admin). Ô "đang chờ duyệt thêm/gỡ" là đề nghị chưa xử lý. Bảng chỉ cho thấy tên, cấp bậc, đơn vị, mã, chuyên ngành, đối tượng.

**Phân công bằng Excel:** nút **Phân công bằng Excel** → chọn đợt đang mở → tải file mẫu: mỗi đơn vị một sheet, mỗi nội dung bạn thao tác được một cột; ghi `x` để thêm, xóa `x` để gỡ → tải lên → kiểm tra → ghi (mục 7).

### 4.10 Duyệt đề nghị

Khi Admin đề nghị thêm hoặc gỡ người học vào nội dung của bạn, chuông thông báo hiện số mới.

1. Bấm thông báo (hoặc menu **Đề nghị**). Trang mở tab **Đang chờ**, các đề nghị **gom theo nội dung kiểm tra**; nhóm liên quan tới thông báo bạn bấm được viền đậm.
2. Đánh dấu từng đề nghị hoặc **Chọn tất cả** của một nội dung.
3. **Duyệt đề nghị đã chọn** (ghi chú tùy chọn) hoặc **Từ chối đề nghị đã chọn** (**bắt buộc ghi lý do**, dùng chung cho các đề nghị đã chọn). Đề nghị nào lỗi được liệt kê và giữ lại.
4. Người đề nghị nhận thông báo kết quả.

Lưu ý: duyệt đề nghị **thêm** vào nội dung đã đóng sẽ bị từ chối ("đóng"). Duyệt đề nghị **gỡ** người đã có điểm chỉ Super Admin làm được. Bạn không tự duyệt đề nghị của chính mình.

## 5. Admin

Admin quản lý nhân sự trong **đơn vị mình và các đơn vị con** (Admin Cấp 1 thấy cả nhánh; Admin Cấp 2 chỉ thấy đơn vị Cấp 2 của mình). Admin **không** chấm điểm, không sửa điểm, không thêm trực tiếp người vào nội dung kiểm tra; Admin xem kết quả, thống kê và gửi đề nghị. Nếu bạn còn được giao làm giám khảo thì có thêm quyền ở mục 4 cho nội dung đó.

### 5.1 Trang chủ

Thẻ số liệu: người dùng, nội dung kiểm tra, đề nghị chờ duyệt, thông báo chưa đọc. Dòng nhắc "N nội dung kiểm tra chưa chấm xong". Sáu biểu đồ tổng quan: hai vòng xếp loại riêng cho **điểm cá nhân** và **điểm tập thể đơn vị**, xếp loại theo đơn vị, điểm trung bình theo đợt, xếp loại theo đợt, tỷ lệ đạt theo nội dung kiểm tra. Số liệu chỉ tính trong phạm vi quản lý của bạn. Nút nhanh: **Thêm người dùng**, **Tạo đề nghị**.

### 5.2 Quản lý người dùng

Menu **Người dùng**: danh sách người trong phạm vi.

**Tìm và lọc:** ô tìm (họ tên, mã định danh, tài khoản); bộ lọc chọn nhiều theo đơn vị (gồm đơn vị con), đối tượng, cấp bậc, chức vụ, chuyên ngành, trạng thái, quyền. Mặc định không hiện người đã xóa; chọn trạng thái "Đã xóa" để xem.

**Thêm người dùng** (**Thêm người dùng**):

1. Nhập họ đệm, tên (bắt buộc).
2. Chọn **đơn vị** (bắt buộc, chỉ trong phạm vi của bạn), **đối tượng** (SQ, QNCN, HSQ-CS; bắt buộc), cấp bậc, chức vụ, chuyên ngành (chọn nhiều bằng ô đánh dấu), ngày sinh, điện thoại, ghi chú.
3. **Mã định danh:** để trống thì hệ thống tự cấp; nhập số hiệu chính thức nếu có (không được trùng).
4. Cấp tài khoản ngay nếu cần (tên tài khoản, quyền Người dùng hoặc Admin); không nhập mật khẩu thì dùng mật khẩu mặc định `123456789`. Dù nhập hay để mặc định, người dùng đều phải đặt mật khẩu mới ở lần đăng nhập đầu. Admin bắt buộc có tài khoản.
5. Lưu, hệ thống mở trang chi tiết của người vừa tạo.

**Sửa thông tin:** nút **Sửa** ở dòng hoặc ở trang chi tiết. Form sửa chỉ gồm hồ sơ; tài khoản, đơn vị, quyền, trạng thái và mật khẩu có nút riêng (bên dưới). Nếu báo lỗi sai phiên bản, nghĩa là người khác vừa sửa bản ghi này: tải lại trang rồi sửa lại.

**Thao tác trên dòng:** Xem chi tiết, Sửa, **Khóa** hoặc **Mở khóa**, **Xóa** (có xác nhận), và **Thao tác khác** mở hộp thoại: Chuyển đơn vị, Đổi quyền, Cấp hoặc Đổi hoặc Thu hồi tài khoản, Đặt lại mật khẩu, In thông tin, Khóa, Mở khóa, Xóa. Cùng các thao tác này có ở trang chi tiết (nút **Thao tác khác**).

| Thao tác | Cách làm và lưu ý |
|---|---|
| **Cấp tài khoản** | Trang chi tiết → thẻ Tài khoản → **Cấp tài khoản** → nhập tên tài khoản; hộp thoại ghi rõ mật khẩu ban đầu là `123456789`; người dùng phải đặt mật khẩu mới ở lần đăng nhập đầu. **Đổi tài khoản** đổi tên (giữ mật khẩu); **Thu hồi** xóa tài khoản (chỉ với Người dùng, không với Admin) |
| **Đặt lại mật khẩu** | Thao tác khác → **Đặt lại mật khẩu** → nhập mật khẩu mới hai lần (tối thiểu 8 ký tự). Người dùng đăng nhập bằng mật khẩu đó rồi phải đặt mật khẩu mới; phiên đang mở của họ cũng bị chuyển về form đặt mật khẩu. Không dùng cho chính mình (dùng Đổi mật khẩu) và không áp dụng cho tài khoản Super Admin |
| **Đổi quyền** | **Đổi quyền** → chọn Người dùng hoặc Admin (cấp Admin theo cấp đơn vị của người đó). Lên Admin cần người đó đã có tài khoản và mật khẩu. Admin **không** cấp được Super Admin |
| **Khóa, mở khóa** | Người bị khóa không đăng nhập được; có hiệu lực ngay cả với phiên đang mở. Không tự khóa mình |
| **Xóa, khôi phục** | Xóa mềm: người đó biến khỏi danh sách mặc định, dữ liệu và kết quả cũ vẫn nằm trong thống kê lịch sử. **Khôi phục** (ở bộ lọc "Đã xóa") đưa trở lại. Không tự xóa mình |

> **Chuyển đơn vị cần cân nhắc.** **Chuyển đơn vị** → chọn đơn vị đích (bất kỳ đơn vị nào) → xác nhận. Nếu đơn vị đích **nằm ngoài phạm vi quản lý của bạn**: (1) người đó **biến khỏi danh sách của bạn** ngay sau khi chuyển và bạn không còn xem được họ; (2) nếu người đó đang là **Admin** thì bị **tự hạ về Người dùng**, Admin ở đơn vị đích phải cấp lại quyền. Hộp thoại cảnh báo điều này trước khi bạn xác nhận. Thống kê cũ của đơn vị trước **không đổi** (kết quả tính theo đơn vị lúc người đó tham gia).

**Thông tin một người:** bấm tên để mở trang chi tiết: thông tin cá nhân, tài khoản (có hay chưa có mật khẩu, lần đăng nhập gần nhất) và thẻ **Nội dung kiểm tra đã tham gia và điểm** của người đó (lọc và in được như ở mục 3.2).

### 5.3 Nhập người dùng bằng Excel

**Người dùng** → **Nhập Excel**. Tải file mẫu (sheet `HuongDan` nằm đầu), điền mỗi người một dòng: mã định danh (để trống thì tạo mới và tự cấp), họ đệm, tên, **đơn vị** (chọn từ danh sách trong ô), **đối tượng** (bắt buộc với người mới), cấp bậc, chức vụ, chuyên ngành 1 đến 3, ngày sinh, điện thoại, ghi chú, tài khoản và quyền (Người dùng hoặc Admin; để trống là Người dùng). Có mã định danh khớp một người sẵn có thì **cập nhật**, ô trống ở cột không bắt buộc **giữ giá trị cũ**. Mỗi dòng phải có đơn vị trong phạm vi của bạn. Làm tiếp theo mục 7.

### 5.4 Xem nội dung kiểm tra và kết quả

**Nội dung kiểm tra** hiện mọi nội dung (tên, đợt, ngày, giám khảo) để bạn biết lịch kiểm tra, kể cả nội dung chưa có người thuộc đơn vị mình. Trong trang chi tiết, bạn chỉ thấy **người học, điểm và thống kê của đơn vị mình**. Bạn có thể in kết quả, xuất điểm Excel và xem thống kê nội dung theo phạm vi đó.

### 5.5 Đề nghị thêm hoặc gỡ người học

Admin không thêm trực tiếp mà **gửi đề nghị**; giám khảo của nội dung (hoặc Super Admin) duyệt. Có ba cách gửi:

1. **Phân công** (khuyến nghị khi có nhiều người): chọn đợt, bấm ô người × nội dung, bấm **Gửi đề nghị**. Bạn thấy mọi nội dung đang mở của đợt nhưng chỉ người trong nhánh mình. Nhiều đề nghị cho cùng một nội dung chỉ tạo **một thông báo gộp** cho giám khảo. Ô sau khi gửi hiện "chờ duyệt".
2. **Đề nghị** → **Tạo đề nghị**: chọn nội dung kiểm tra đang mở, loại (**Đề nghị thêm** hoặc **Đề nghị gỡ**), đối tượng là **Một người** (tìm tên, tối thiểu 2 ký tự, trong đơn vị bạn quản lý) hoặc **Cả đơn vị** (chọn một hay nhiều đơn vị, mỗi đơn vị một đề nghị), rồi **Gửi đề nghị**.
3. Ở trang chi tiết nội dung: nút **Tạo đề nghị** (thêm) và nút **Đề nghị gỡ** trên dòng người học.

Theo dõi ở menu **Đề nghị**: các tab trạng thái *Đang chờ, Đã duyệt, Bị từ chối, Đã hủy*. Đề nghị còn "Đang chờ" bạn hủy được bằng **Hủy đề nghị**. Khi giám khảo duyệt hoặc từ chối, bạn nhận thông báo kèm lý do.

Điều kiện để gửi được: nội dung còn mở (đợt không khóa); đối tượng thuộc nhánh của bạn; không có đề nghị giống hệt đang chờ; không đề nghị thêm người đã có trong nội dung, hay gỡ người không có.

**Phân công bằng Excel** dùng được cho Admin: mục 7 (`x` thêm, bỏ `x` gỡ); mọi thay đổi trở thành đề nghị chờ duyệt.

### 5.6 Thống kê

**Thống kê** (menu Tổng quan). Chọn:

- **Thẻ thống kê:** *Xếp loại theo đơn vị* (số lượng và tỷ lệ, điểm trung bình, cao nhất, thấp nhất, có bảng số liệu và dòng tổng) hoặc *Chưa kiểm tra* (danh sách người chưa có điểm hoặc không kiểm tra kèm lý do vắng).
- **Loại điểm:** *Cá nhân* hoặc *Tập thể đơn vị*. Hai loại **không bao giờ gộp**; trang luôn ghi rõ đang xem loại nào.
- **Bộ lọc:** đợt (chọn trước), **nội dung kiểm tra** của đợt đã chọn, **đơn vị** (chọn nhiều, mặc định "Toàn bộ phạm vi"), khoảng ngày kiểm tra. Bộ lọc thu gọn được; **Bỏ lọc** để về mặc định.

Số liệu tính theo **đơn vị lúc người học tham gia** và bỏ người đã bị gỡ; vì vậy nếu một người đã chuyển khỏi nhánh của bạn, số liệu cũ của đơn vị vẫn giữ nguyên nhưng họ tên người đó không hiện trong danh sách "Chưa kiểm tra". Biểu đồ luôn kèm chú giải chữ và bảng số liệu; rê chuột hoặc chạm để xem chi tiết. Trang thống kê không có bản in.

## 6. Super Admin

Super Admin có toàn quyền, kể cả mọi việc của Admin (không giới hạn nhánh) và của giám khảo (mọi nội dung), cộng với các chức năng riêng dưới đây.

### 6.1 Đơn vị

**Đơn vị** (menu Quản trị): cây hai cấp. Thêm đơn vị con dưới một đơn vị Cấp 1 (mã tự đánh số tiếp trong cùng cấp), đổi tên, khóa hoặc mở. **Mã, cấp và đơn vị cha không đổi được sau khi tạo**; sai thì phải nhờ người phát triển. Khóa một đơn vị không xóa dữ liệu của nó.

### 6.2 Danh mục

**Danh mục**: cấp bậc (tên và ký hiệu), chức vụ, chuyên ngành, lý do vắng. Thêm, sửa, **khóa** (không xóa, để dữ liệu cũ vẫn hiển thị đúng). Mục đã khóa không chọn được cho dữ liệu mới.

### 6.3 Đợt kiểm tra

**Đợt kiểm tra**: **Thêm đợt kiểm tra** (tên, ngày bắt đầu, ngày kết thúc, ghi chú; ngày kết thúc phải sau ngày bắt đầu), **Sửa**, **Đóng đợt** hoặc **Mở lại đợt**, **Xóa** (chỉ khi đợt chưa có nội dung kiểm tra).

- **Đóng (khóa) đợt thì mọi nội dung của đợt cũng đóng:** không thêm người, không nhận đề nghị mới, không mở lại riêng từng nội dung. Chấm điểm vẫn làm được. Mở lại đợt thì nội dung trở về trạng thái riêng của nó. Đợt đã đóng không nhận nội dung mới.
- **Xem các nội dung kiểm tra** mở danh sách đã lọc theo đợt.
- **Chấm điểm cả đợt bằng Excel** (ở dòng của đợt): mục 7.3.

### 6.4 Nội dung kiểm tra

**Nội dung kiểm tra** → **Thêm nội dung kiểm tra**: mở hộp thoại ngay trên trang (cần ít nhất một đợt đang mở). Nhập đợt, tên, ngày, địa điểm, ghi chú; ô **Giám khảo**: gõ tên, mã định danh hoặc tài khoản, chọn trong gợi ý (chỉ người có tài khoản đang hoạt động), mỗi người thành một thẻ có nút bỏ; tối đa 20 giám khảo. **Lưu** một lần cho cả nội dung và giám khảo. Bạn mặc định là giám khảo của mọi nội dung.

Trang chi tiết có menu **Quản lý nội dung kiểm tra**: **Sửa nội dung và giám khảo**, **Gán giám khảo**, **Đóng nội dung kiểm tra** hoặc **Mở lại nội dung kiểm tra**, **Xóa nội dung kiểm tra**. Bỏ gán thì người đó mất quyền giám khảo ngay. **Không xóa được nội dung đã có điểm** (dùng Đóng thay thế). Xóa nội dung hủy các đề nghị đang chờ của nội dung đó.

**Nhập nhiều nội dung kiểm tra bằng Excel:** **Nhập Excel** ở trang danh sách (mục 7). Một file gồm sheet `NoiDung` (tên, đợt chọn từ danh sách, ngày, địa điểm, ghi chú) và sheet `NguoiHoc` (tên nội dung, mã định danh người học).

### 6.5 Thêm người, phân công, duyệt đề nghị

Bạn thêm người học **trực tiếp** như giám khảo (mục 4.3) ở mọi nội dung, dùng **Phân công** với "Áp dụng" có hiệu lực ngay (thấy toàn bộ danh sách người, mọi cột nội dung của đợt) và duyệt hoặc từ chối đề nghị của Admin (mục 4.10). Bạn là người duy nhất:

- **gỡ người đã có điểm** khỏi nội dung (kèm lý do bắt buộc; điểm còn lại trong hệ thống nhưng không tính thống kê). Xem lại ở tab **Đã gỡ**;
- duyệt đề nghị gỡ người đã có điểm.

### 6.6 Chấm điểm, sửa điểm

Chấm điểm như mục 4.6, ở mọi nội dung (kể cả chấm thay giám khảo là người học của nội dung).

**Sửa điểm** (chỉ Super Admin): ở bảng người học, bấm **Sửa điểm** ở dòng đã có điểm → nhập **Điểm mới (0 đến 10)** và **Lý do sửa** (bắt buộc) → lưu. Hộp thoại hiện điểm hiện tại và xếp loại sẽ ghi; điểm mới giống điểm cũ thì báo lỗi. Sau khi sửa, dòng hiện nhãn **Đã sửa**; mở lại hộp thoại thấy **Lịch sử sửa điểm** (ai sửa, lúc nào, điểm cũ → mới, lý do). Mỗi lần sửa còn được ghi vào nhật ký thao tác.

### 6.7 Nhật ký thao tác

**Nhật ký** (menu Quản trị): mọi thao tác quan trọng (đăng nhập thành công và thất bại kèm địa chỉ IP, đổi quyền, đổi đơn vị, xóa mềm, thêm hoặc gỡ người học, nhập và sửa điểm, nhập Excel, sao lưu...). Lọc theo người, hành động, khoảng ngày; bấm **Xem** để mở chi tiết (giá trị trước và sau). Nhật ký **chỉ xem**, không sửa và không xóa được. Dùng để phát hiện dò mật khẩu: nhiều "Đăng nhập thất bại" liên tiếp từ một IP là dấu hiệu bất thường.

### 6.8 Hệ thống và sao lưu

**Hệ thống** (menu Quản trị) cho biết tình trạng máy chủ (phiên bản ứng dụng và dữ liệu, dung lượng, ổ đĩa còn trống) và các **cảnh báo**: chưa có bản sao lưu tự động hoặc quá 2 ngày chưa sao lưu, thư mục sao lưu cùng ổ đĩa với dữ liệu, ổ sắp đầy.

- **Danh sách bản sao lưu** (Tự động, Thủ công, Trước nâng cấp) kèm thời điểm, dung lượng và nút tải về từng bản.
- **Tạo bản sao lưu ngay:** nên làm trước các thao tác lớn (nhập Excel số lượng lớn, chấm cả đợt).
- **Sao lưu tự động:** đặt giờ (mặc định 02:00), **số bản tự động giữ lại** (1 đến 30, mặc định 5) và **thư mục sao lưu** (để trống là mặc định; nên đặt ở ổ khác hoặc ổ mạng) rồi **Lưu cài đặt**. Máy tắt vào giờ hẹn thì sao lưu ngay khi bật lại trong ngày.
- Khối hướng dẫn khôi phục nằm ngay trên trang; các bước đầy đủ ở OPERATIONS-GUIDE mục 9. Khôi phục do người quản trị máy chủ làm, không có nút khôi phục trên giao diện.

## 7. Nhập dữ liệu bằng Excel

### 7.1 Ba bước chung

Mọi chức năng nhập Excel (người dùng, nội dung kiểm tra, điểm, phân công, chấm cả đợt) cùng một cách:

1. **Tải file mẫu.** File mẫu do hệ thống tạo theo dữ liệu và quyền hiện tại của bạn: các cột như đơn vị, cấp bậc, đợt kiểm tra, người học là **ô chọn từ danh sách**. Sheet `HuongDan` nằm đầu tiên, giải thích từng cột.
2. **Điền và lưu** dạng `.xlsx` (Excel 2007 trở lên); tối đa 5 MB và 5.000 dòng mỗi sheet. Không đổi tên cột; có thể đổi thứ tự cột hoặc thêm cột phụ.
3. **Chọn file → Kiểm tra file → Ghi dữ liệu.** Bước kiểm tra **không ghi gì**:
   - Có lỗi: hiện bảng lỗi theo **dòng và cột**, có nút **tải danh sách lỗi (CSV)**; không có nút ghi. Sửa trong file rồi kiểm tra lại.
   - Không lỗi: hiện bảng việc sẽ làm (thêm, cập nhật, bỏ qua) để bạn xem trước, rồi bấm **Ghi dữ liệu**.

Nguyên tắc: **ghi tất cả hoặc không ghi gì** (một dòng lỗi thì không dòng nào được ghi); không bao giờ ghi đè điểm đã có; mỗi lần nhập được ghi nhật ký. Ô cấp bậc, mã định danh nên chọn từ ô chọn hoặc để định dạng Text: gõ tay dễ bị Excel tự đổi thành ngày hoặc số khoa học.

### 7.2 Từng loại file

| Chức năng | Nơi mở | Ai | Điểm cần nhớ |
|---|---|---|---|
| Nhập người dùng | Người dùng → Nhập Excel | Admin, Super Admin | Khớp theo mã định danh; trống mã thì tạo mới và tự cấp; xem mục 5.3 |
| Nhập nội dung kiểm tra | Nội dung kiểm tra → Nhập Excel | Super Admin | Đợt chọn trong file theo từng nội dung (phải đang mở); kèm danh sách người học |
| Nhập điểm một nội dung | Chi tiết nội dung → Nhập điểm Excel | Giám khảo, Super Admin | File là danh sách đầy đủ của nội dung; chỉ điền người chưa có điểm |
| Phân công bằng Excel | Phân công → Phân công bằng Excel | Super Admin, giám khảo (thêm ngay), Admin (gửi đề nghị) | Mỗi đơn vị một sheet, mỗi nội dung một cột, ô `x` |
| Chấm điểm cả đợt | Đợt kiểm tra → Chấm điểm cả đợt bằng Excel | Super Admin | Xem mục 7.3 |

### 7.3 Chấm điểm cả đợt (Super Admin)

Chấm điểm nhiều nội dung của một đợt trong một file. Tải mẫu từ dòng của đợt ở trang **Đợt kiểm tra**: **mỗi đơn vị (lúc tham gia) một sheet**, dòng "Cả đơn vị" (điểm tập thể) đứng đầu, rồi từng người học; **mỗi nội dung của đợt là một cột** (kể cả nội dung đã đóng). Ba loại ô tô màu khác nhau, có dòng chú thích trên đầu bảng:

| Ô | Hiển thị | Ý nghĩa |
|---|---|---|
| **Được nhập** | Nền vàng nhạt | Điền điểm 0 đến 10, một chữ số thập phân |
| **Đã có điểm** | Nền xám, chữ nghiêng, khóa | Giữ nguyên; đổi giá trị này là lỗi (không ghi đè) |
| **Không tham gia** | Gạch chéo, khóa | Người đó không có trong nội dung này; điền vào là lỗi |

Ô để trống thì bỏ qua. Nhập điểm xóa lý do vắng của người đó. Phần còn lại như mục 7.1.

## 8. In báo cáo

Hai trang in, đều qua trình duyệt (Ctrl+P hoặc nút **In**), khổ A4, **đen trắng**, cùng bố cục với file Excel của hệ thống:

| Báo cáo | Mở từ | Khổ giấy | Nội dung |
|---|---|---|---|
| **Kết quả một nội dung kiểm tra** | Chi tiết nội dung → **In kết quả** | A4 ngang | Cùng các cột với "Xuất điểm Excel", cấp bậc bằng ký hiệu, theo phạm vi bạn được xem |
| **Thông tin cá nhân** | Hồ sơ hoặc chi tiết người dùng → **In thông tin cá nhân** | A4 dọc | Thông tin chung (cấp bậc tên đầy đủ) và kết quả kiểm tra qua các đợt |

**In theo bộ lọc:** bản in lấy đúng bộ lọc bạn đang chọn ở trang nguồn (tình trạng, xếp loại, đơn vị, đối tượng, tìm nhanh với kết quả; đợt, khoảng ngày, tình trạng, xếp loại, tên với thông tin cá nhân). Chỉ in phần khớp; số liệu tổng hợp tính trên phần được in; bản in ghi dòng **"Bộ lọc: ..."**. Bấm **In tất cả (bỏ lọc)** để in đầy đủ. Quay lại từ trang in vẫn giữ nguyên bộ lọc.

Để lưu PDF: ở hộp thoại in của trình duyệt chọn máy in "Lưu thành PDF" (Save as PDF), đừng bật "Đồ họa nền". Trang thống kê không có bản in.

## 9. Quy trình mẫu cho một đợt kiểm tra

| Bước | Ai | Việc làm | Chương |
|---|---|---|---|
| 1 | Admin | Bảo đảm nhân sự đã đủ và đúng đơn vị: thêm người, nhập Excel, chuyển đơn vị nếu cần | 5.2, 5.3 |
| 2 | Super Admin | Tạo **đợt kiểm tra** | 6.3 |
| 3 | Super Admin | Tạo các **nội dung kiểm tra** của đợt, gán **giám khảo** | 6.4 |
| 4 | Giám khảo, Super Admin | Đưa người học vào từng nội dung: Thêm người, hoặc **Phân công** | 4.3, 4.9 |
| 5 | Admin | Gửi **đề nghị** thêm hoặc gỡ người cho đơn vị mình (bảng Phân công, Tạo đề nghị) | 5.5 |
| 6 | Giám khảo | **Duyệt** đề nghị trên trang Đề nghị | 4.10 |
| 7 | Giám khảo | Tổ chức kiểm tra. Người vắng: ghi **lý do vắng** | 4.4 |
| 8 | Giám khảo | **Chấm điểm** (hoặc nhập điểm Excel) và kiểm tra bước xác nhận | 4.6, 4.7 |
| 9 | Super Admin | Nội dung nào lệch: **sửa điểm** kèm lý do. Nhiều nội dung cùng lúc: chấm cả đợt bằng Excel | 6.6, 7.3 |
| 10 | Admin, Super Admin | Theo dõi **thống kê**, danh sách chưa kiểm tra; trang chủ nhắc nội dung còn chưa chấm xong | 5.6 |
| 11 | Mọi người | **In** kết quả, **xuất** điểm Excel | 8, 4.8 |
| 12 | Super Admin | **Đóng nội dung hoặc đợt** khi hoàn tất; sao lưu | 6.3, 6.8 |

## 10. Thông báo và câu hỏi thường gặp

### 10.1 Thông báo

| Bạn nhận thông báo khi | Ai nhận |
|---|---|
| Có đề nghị thêm hoặc gỡ người học mới | Giám khảo của nội dung và mọi Super Admin (trừ người gửi). Một thông báo gộp cho mỗi nội dung khi gửi nhiều đề nghị cùng lúc |
| Đề nghị của bạn được duyệt | Người đề nghị |
| Đề nghị của bạn bị từ chối (kèm lý do) | Người đề nghị |

Bấm thông báo đề nghị mới sẽ mở **cả danh sách đề nghị đang chờ**, nhóm liên quan được viền đậm. Số trên chuông cập nhật khoảng 30 giây một lần khi tab đang mở và bạn có thao tác; để quên máy mở thì chuông ngừng hỏi và phiên hết hạn như bình thường. Chỉ bạn thấy thông báo của bạn.

### 10.2 Câu hỏi thường gặp

| Câu hỏi | Trả lời |
|---|---|
| Tôi không thấy menu hoặc nút cần dùng | Bạn không có quyền với chức năng đó. Menu chỉ hiện những gì vai trò của bạn được dùng (xem mục 1.4, 11) |
| Báo "không tìm thấy" dù người đó có tồn tại | Người hoặc nội dung nằm **ngoài phạm vi** của bạn (Admin chỉ thấy nhánh mình). Hệ thống cố ý không phân biệt để không lộ dữ liệu |
| Báo "người này đã có điểm" khi chấm | Mỗi người chỉ chấm một lần (hoặc giám khảo khác vừa chấm). Cần sửa thì nhờ Super Admin |
| Báo nội dung hoặc đợt "đã đóng" khi thêm người | Nội dung hoặc đợt đã đóng. Nhờ Super Admin mở lại; mở lại đợt trước nếu đợt đang khóa. Chấm điểm vẫn được |
| Tôi gõ điểm `6.55` bị báo lỗi | Điểm chỉ có một chữ số thập phân: gõ `6.5` hoặc `6.6` |
| Không chấm được cho chính mình | Giám khảo không tự chấm. Nhờ Super Admin |
| Tôi vừa chuyển một người sang đơn vị khác và họ biến mất | Đơn vị đích ngoài phạm vi của bạn; bạn không còn xem được người đó. Nếu họ là Admin thì đã bị hạ về Người dùng |
| Nhập Excel báo lỗi nhưng tôi nghĩ file đúng | Xem bảng lỗi: dòng, cột, nội dung. Lỗi hay gặp: sai tên trong ô chọn, đơn vị ngoài phạm vi, mã định danh trùng, ngày sai, điểm sai. Dùng đúng file mẫu mới tải |
| "File không đọc được" | File không phải `.xlsx` hoặc hỏng. Mở file mẫu, điền rồi lưu lại dạng Excel Workbook |
| Bị đưa về trang đăng nhập | Phiên hết hạn (không thao tác khoảng 30 phút), hoặc tài khoản vừa bị khóa. Đăng nhập lại |
| Quên mật khẩu | Nhờ Admin trong nhánh đặt lại; Super Admin xem mục 1.3 |
| Số trên thống kê khác số trong danh sách người | Thống kê tính theo **đơn vị lúc tham gia**, danh sách người theo **đơn vị hiện tại**; một người đã chuyển đơn vị làm hai số lệch nhau. Đây là thiết kế để số liệu cũ không đổi |
| Điểm của đơn vị tôi đâu? | Người dùng thường không xem điểm tập thể của đơn vị mình; chỉ Admin trong phạm vi và Super Admin xem được |
| Chữ trên điện thoại bị nhỏ hoặc phóng to khi chạm ô nhập | Ô nhập dùng cỡ chữ 16 px để tránh tự phóng to; nếu vẫn bị, báo quản trị kèm kiểu máy và trình duyệt |
| Trang lỗi, lỗi lạ khác | Chụp màn hình, ghi lại thao tác và giờ, báo người quản trị; sự cố máy chủ xem OPERATIONS-GUIDE mục 12 |

## 11. Bảng tóm tắt quyền

✅ được, ❌ không, 🔶 có điều kiện.

| Chức năng | Super Admin | Admin | Giám khảo | Người dùng |
|---|---|---|---|---|
| Đăng nhập, đổi mật khẩu của mình, xem hồ sơ và điểm của mình | ✅ | ✅ | ✅ | ✅ |
| Xem thông báo | ✅ | ✅ | ✅ | ✅ |
| Quản lý đơn vị, danh mục | ✅ | ❌ | ❌ | ❌ |
| Quản lý người dùng (thêm, sửa, khóa, xóa, chuyển đơn vị) | ✅ | 🔶 trong nhánh | ❌ | ❌ |
| Cấp quyền Admin | ✅ | 🔶 trong nhánh | ❌ | ❌ |
| Cấp quyền Super Admin | ✅ | ❌ | ❌ | ❌ |
| Cấp tài khoản, đặt lại mật khẩu | ✅ | 🔶 trong nhánh (không với Super Admin) | ❌ | ❌ |
| Nhập người dùng bằng Excel | ✅ | 🔶 từng dòng trong nhánh | ❌ | ❌ |
| Tạo, sửa, đóng, xóa đợt và nội dung kiểm tra; gán giám khảo | ✅ | ❌ | ❌ | ❌ |
| Xem danh sách nội dung kiểm tra | ✅ tất cả | ✅ tất cả | 🔶 nội dung mình dạy | 🔶 nội dung mình tham gia |
| Xem danh sách người học và điểm của nội dung | ✅ cả nội dung | 🔶 người trong nhánh | ✅ cả nội dung mình dạy | 🔶 chỉ mình |
| Thêm người học trực tiếp, Phân công "Áp dụng" | ✅ | ❌ | 🔶 nội dung mình dạy | ❌ |
| Gửi đề nghị thêm hoặc gỡ | không cần | ✅ người trong nhánh | ❌ | ❌ |
| Duyệt, từ chối đề nghị | ✅ | ❌ | 🔶 nội dung mình dạy | ❌ |
| Gỡ người chưa có điểm | ✅ | ❌ | 🔶 nội dung mình dạy | ❌ |
| Gỡ người **đã có điểm** (kèm lý do) | ✅ | ❌ | ❌ | ❌ |
| Ghi lý do không kiểm tra | ✅ | ❌ | 🔶 nội dung mình dạy | ❌ |
| Chấm điểm lần đầu | ✅ | ❌ | 🔶 nội dung mình dạy, không chấm cho mình | ❌ |
| Nhập điểm Excel một nội dung | ✅ | ❌ | 🔶 nội dung mình dạy | ❌ |
| Chấm điểm cả đợt bằng Excel | ✅ | ❌ | ❌ | ❌ |
| **Sửa điểm** | ✅ kèm lý do | ❌ | ❌ | ❌ |
| Thống kê tổng quan | ✅ | 🔶 trong nhánh | 🔶 chỉ nội dung mình dạy | ❌ |
| In kết quả, xuất điểm Excel | ✅ | 🔶 phạm vi nhánh | ✅ nội dung mình dạy | 🔶 chỉ dòng của mình |
| Nhật ký thao tác, Hệ thống, sao lưu | ✅ | ❌ | ❌ | ❌ |
