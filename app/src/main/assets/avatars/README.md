# Ảnh avatar tham chiếu (test 2-3 tướng)

Đặt vào đây 2-3 file ảnh avatar tướng (trích từ AssetBundle), đặt tên theo
dạng `hero_<tên>.png` hoặc `.jpg`, ví dụ:

- hero_lubu.png
- hero_valhein.png
- hero_zill.png

App sẽ tự liệt kê danh sách file trong thư mục này ở màn hình chọn tướng lúc
loading (nút "Chọn tướng đang chơi" trong MainActivity). Ảnh vuông hay tròn
đều được — app tự cắt tròn nội tiếp + resize chuẩn trước khi tính hash, nên
không cần xử lý trước.

Kích thước khuyến nghị: tối thiểu 128x128px để giữ đủ chi tiết sau khi resize
xuống kích thước chuẩn dùng để so khớp.
