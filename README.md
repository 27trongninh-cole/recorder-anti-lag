# Game Replay Recorder (Android)

Quay màn hình game bằng **rolling buffer / instant replay**: bộ mã hóa (hardware
encoder) chạy liên tục nhưng chỉ giữ N giây gần nhất trong RAM, không ghi liên
tục ra ổ cứng. Khi bạn thấy pha highlight, chạm nút nổi **SAVE** để xuất đúng
đoạn đó ra file `.mp4` — nhờ vậy máy không bị giật/lag như khi quay cả trận
rồi cắt.

## Kiến trúc

```
MediaProjection -> VirtualDisplay -> Surface (MediaCodec, HW encoder)
    -> luồng riêng đọc output encoder -> RollingBuffer (RAM, mặc định 90s)
    -> chạm nút SAVE -> HighlightMuxer -> Movies/GameReplay/highlight_*.mp4
```

- `RollingBuffer.kt` — hàng đợi frame đã encode, tự xóa frame cũ hơn cửa sổ thời gian.
- `HighlightMuxer.kt` — ghi 1 file `.mp4` từ snapshot buffer, chỉ chạy khi bấm SAVE.
- `ScreenCaptureService.kt` — foreground service, làm toàn bộ việc capture + overlay nút.
- `MainActivity.kt` — xin quyền (overlay, notification, screen capture) và start service.

Không có bước copy qua `Bitmap`, không convert màu thừa, input của encoder là
`Surface` nhận trực tiếp từ `VirtualDisplay` — đúng theo kiến trúc "nhẹ nhất"
mà các app quay replay chuyên game vẫn dùng.

## Thông số mặc định (sửa trong `ScreenCaptureService.kt`)

| Thông số | Giá trị |
|---|---|
| Độ phân giải | Tự động lấy theo màn hình thực tế **tại thời điểm bấm "Bắt đầu quay"** (nên bấm quay khi game đã mở và đã xoay ngang) |
| FPS | 60 |
| Bitrate | 10 Mbps |
| Keyframe interval | 2s |
| Cửa sổ buffer | 90 giây |

Máy yếu hơn/pin yếu: giảm `VIDEO_BITRATE` xuống 6-8Mbps.

**Lưu ý quan trọng về thứ tự thao tác** (đúc kết từ thực tế test trên Redmi K70E):
1. Bấm "Cấp quyền quay màn hình" và chọn **"Share one app" → Liên Quân Mobile** (game nên đang chạy sẵn).
2. **Không quay ngay** — chỉ có nút nổi "BẮT ĐẦU" chờ sẵn.
3. Vào hẳn trong game, đợi máy xoay ngang.
4. Chạm "BẮT ĐẦU" **trong lúc đang ở trong game** — đây mới là lúc pipeline thực sự khởi tạo theo đúng kích thước ngang.

## Cách dùng

1. Mở app → bấm **"Cấp quyền hiển thị nổi"**, cho phép overlay.
2. Bấm **"Cấp quyền quay màn hình"**, cho phép khi hệ thống hỏi (chọn **Share one app → Liên Quân Mobile** — game phải đang chạy sẵn ở bước này nếu bạn đã mở nó trước).
3. Lúc này sẽ có **1 nút nổi "BẮT ĐẦU"** hiện trên màn hình — **chưa quay gì cả**, chỉ là chờ.
4. Mở/chuyển vào Liên Quân Mobile, chơi bình thường cho đến khi máy đã xoay ngang hẳn.
5. **Ngay lúc đang ở trong game**, chạm nút nổi **"BẮT ĐẦU"** — lúc này việc quay mới thực sự bắt đầu, dùng đúng kích thước màn hình ngang tại thời điểm đó. Nút đổi thành **"SAVE"**.
6. Khi vừa có pha hay (pentakill, outplay...), chạm **SAVE** → video 90 giây gần nhất
   được lưu vào `Movies/GameReplay/`.
7. Kéo file đó vào CapCut/editor để cắt/ghép rồi đăng TikTok.

**Vì sao phải bấm "BẮT ĐẦU" bên trong game thay vì tự động quay ngay khi cấp quyền?**
Vì lúc bạn đang ở app này để cấp quyền, máy còn đang dọc — nếu quay ngay lúc đó, video sẽ bị quay theo khung dọc rồi kẹt luôn dù sau đó bạn mới xoay ngang vào game. Tách thành 2 bước (cấp quyền trước, bấm "BẮT ĐẦU" sau khi đã ở trong game) đảm bảo lúc pipeline thực sự khởi tạo, màn hình đã đúng hướng ngang.

## Giới hạn hiện tại (có thể mở rộng thêm)

- Chưa ghi âm thanh (chỉ video) — có thể thêm `AudioRecord` + `AudioPlaybackCapture`
  (Android 10+, cần audio "playback capture" permission) và mux thêm track audio.
- Overlay dùng `Button` mặc định, có thể đổi UI đẹp hơn.
- Có thể thêm phím tắt double-tap volume qua `AccessibilityService` thay cho nút nổi,
  nếu muốn không che màn hình chút nào.
- Chưa test trên nhiều dòng máy — bitrate/resolution nên tinh chỉnh theo máy thật (Redmi K70E).

## Build

Repo này build qua GitHub Actions (`.github/workflows/build.yml`) — mỗi lần push
lên nhánh `main` (hoặc chạy tay qua tab Actions > Run workflow), APK debug sẽ được
build và đính kèm trong phần **Artifacts** của run đó.

Build local (cần Android Studio / Android SDK cài sẵn):

```bash
gradle assembleDebug
```

(Repo chưa kèm Gradle wrapper jar nên cần Gradle cài sẵn trên máy, hoặc mở
project bằng Android Studio — Android Studio sẽ tự tạo wrapper.)

## Quyền cần cấp

- Quay màn hình (MediaProjection) — hỏi mỗi lần bắt đầu quay, theo yêu cầu của Android.
- Hiển thị đè lên ứng dụng khác (overlay) — cho nút SAVE nổi.
- Thông báo (Android 13+) — để hiển thị thông báo "đang quay" (bắt buộc với foreground service).
