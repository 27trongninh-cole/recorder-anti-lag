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

## Build tự động

Mỗi lần push lên nhánh `main`, GitHub Actions sẽ tự build và tạo **Release** mới
kèm 2 file APK (`app-debug.apk` và `app-release.apk`) — không cần vào tab Actions
tải file zip. Cũng có thể bấm **Run workflow** thủ công trong tab Actions.

## Cấu trúc

- `MainActivity` — xin quyền overlay + bỏ giới hạn pin, có nút bật/tắt overlay
- `OverlayService` — vẽ 1 ô overlay đỏ (kéo thả được) dùng `TYPE_APPLICATION_OVERLAY`,
  chạy như foreground service để tránh bị hệ thống kill sớm
