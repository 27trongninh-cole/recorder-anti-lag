# Overlay Test

App Android tối giản để kiểm tra xem quyền "Hiển thị trên ứng dụng khác" (overlay)
có hoạt động ổn định khi vào game full-screen (Liên Quân Mobile) hay không —
đặc biệt trên MIUI (Redmi), nơi hệ thống hay tự thu hồi quyền hoặc kill service nền.

## Cách dùng

1. Vào tab **Releases** của repo này, tải file `app-release.apk` mới nhất.
2. Cài vào máy (cần bật "Cài đặt từ nguồn không xác định" nếu máy chặn).
3. Mở app, bấm lần lượt:
   - **1. Cấp quyền hiển thị trên ứng dụng khác** → bật toggle trong Cài đặt hệ thống
   - **2. Bỏ giới hạn pin (nền)** → chọn "Không giới hạn" / "Allow"
   - **3. BẬT overlay test**
4. Trên MIUI, vào thêm: **Cài đặt > Ứng dụng > Quản lý ứng dụng > Overlay Test > Quyền khác**,
   bật thêm **"Hiển thị trên ứng dụng khác khi chạy nền"** và **"Hiển thị pop-up"** nếu có.
5. Thoát app (không tắt hẳn), mở Liên Quân, vào trận đấu.
6. Quan sát ô đỏ "OVERLAY OK #..." — nếu số vẫn tăng và ô vẫn hiện trong lúc chơi,
   nghĩa là overlay hoạt động đúng ngay cả trong game full-screen.
   Có thể kéo thả ô này sang vị trí không che tầm nhìn.
7. Bấm **4. Bắt đầu ghi hình (buffer)** → hệ thống hỏi "Entire screen" hay
   "Single app" → **luôn chọn "Entire screen"**. "Single app" hiện không được
   hỗ trợ trên nhiều bản Android 14+ khi capture chạy trong 1 service nền tách
   biệt (lỗi `SecurityException` từ chính hệ thống, không phải lỗi code) — vì
   mục đích ghi toàn bộ game nên không cần dùng "Single app".
8. Vào game chơi thoải mái ít nhất 20-30 giây (kiểm tra xem có giật/lag không
   so với lúc chưa bật ghi hình).
9. Bất kỳ lúc nào, quay lại app, nhập số giây N muốn cắt (mặc định 15), bấm
   **5. Giả lập bắt khoảnh khắc** — app sẽ cắt đúng N giây gần nhất từ buffer,
   ghép thành file MP4 (không cần re-encode, gần như tức thời) và lưu vào
   thư mục Movies riêng của app.
10. Dùng app quản lý file (hoặc kết nối máy tính) mở đường dẫn hiện trong app
    để xem clip: có mượt không, có đúng ~N giây không, có bị đứng hình/giật
    ở đoạn nào không.
11. Bấm **Dừng ghi hình** khi test xong.

## Vị trí file clip xuất ra

`Android/data/com.hoa.overlaytest/files/Movies/clip_<ngày giờ>.mp4`
(đường dẫn chính xác hiện ngay trong app sau khi cắt xong)

## Build tự động

Mỗi lần push lên nhánh `main`, GitHub Actions sẽ tự build và tạo **Release** mới
kèm 2 file APK (`app-debug.apk` và `app-release.apk`) — không cần vào tab Actions
tải file zip. Cũng có thể bấm **Run workflow** thủ công trong tab Actions.

## Cấu trúc

- `MainActivity` — xin quyền overlay + bỏ giới hạn pin, điều khiển overlay và
  ghi hình buffer, có ô nhập N giây và nút giả lập cắt clip
- `OverlayService` — vẽ 1 ô overlay đỏ (kéo thả được) dùng `TYPE_APPLICATION_OVERLAY`,
  chạy như foreground service để tránh bị hệ thống kill sớm
- `RecordingService` — ghi hình circular buffer thật:
  - Dùng `MediaCodec` ở chế độ Surface-input (GPU đổ thẳng vào encoder phần cứng,
    không copy raw frame qua CPU) để giảm tải, tránh làm giật game
  - Giữ ~40 giây gần nhất dưới dạng H.264 đã mã hoá (rất nhẹ, không phải raw
    video) trong 1 hàng đợi ở RAM
  - Khi "bắt được khoảnh khắc" (test bằng nút giả lập), chỉ mux lại đúng N giây
    gần nhất thành file MP4 — không re-encode nên gần như tức thời
  - Độ phân giải tự giảm về tối đa ~1280px cạnh dài để nhẹ tải hơn nữa
  - Vòng lặp đọc dữ liệu mã hoá chạy trên thread riêng với độ ưu tiên thấp
    (`THREAD_PRIORITY_BACKGROUND`) để không tranh CPU với game
