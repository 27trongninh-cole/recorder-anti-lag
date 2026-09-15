# Game Replay Recorder (Android)

Quay màn hình game bằng **rolling buffer / instant replay**, điều khiển toàn
bộ qua **1 bong bóng nổi 4 cử chỉ** — không mở giao diện, không menu, không
che tay khi đang combat. Video chỉ thực sự được ghi ra file khi bạn **kết
thúc phiên** (vuốt phải) — trong lúc chơi, bạn chỉ "đánh dấu khoảnh khắc",
app tự batch-export tất cả khoảnh khắc đó thành các file `.mp4` riêng biệt
lúc dừng.

## Bong bóng — 4 cử chỉnh chính thức

| Thao tác | Khi đang IDLE (chưa quay) | Khi đang RECORDING | Khi đang PAUSED |
|---|---|---|---|
| **Chạm** | Bắt đầu quay (đọc đúng độ phân giải/hướng màn hình *ngay lúc chạm* — quan trọng để tránh video bị kẹp dọc/ngang) | Đánh dấu khoảnh khắc (bookmark, chưa ghi file) | Không làm gì (buffer đang đứng) |
| **Vuốt trái** (fling) | — | Tạm dừng buffer | Tiếp tục buffer |
| **Vuốt phải** (fling) | Đóng bong bóng (chưa quay gì thì không có gì để xuất) | **Dừng quay** → xuất tất cả khoảnh khắc đã đánh dấu ra Movies/GameReplay | Dừng quay (như trên) |
| **Vuốt lên** (fling) | — | Hiện/ẩn thanh chọn thời lượng lưu: **15 / 30 / 60 / 90 giây** | Hiện/ẩn thanh chọn |
| **Vuốt xuống** (fling) | Dính bong bóng vào cạnh màn hình gần nhất | (như trên) | (như trên) |
| **Kéo chậm** (không phải vuốt nhanh) | Di chuyển bong bóng tự do — tránh đè nút/tướng | (như trên) | (như trên) |

Bong bóng đổi màu theo trạng thái: xanh dương = sẵn sàng (IDLE), đỏ = đang
quay (RECORDING), vàng = tạm dừng (PAUSED), xám mờ = đang dính cạnh (DOCKED).
Mỗi hành động có rung phản hồi riêng (1 rung ngắn = hành động thường, 2 rung
ngắn = đã đánh dấu, 1 rung dài = dừng quay) để không cần nhìn màn hình xác
nhận.

**Phân biệt "kéo" và "vuốt":** dựa vào **có nhấn giữ trước hay không** — chạm
và giữ yên khoảng ~0.5s (bong bóng rung nhẹ xác nhận "đã bắt"), rồi mới kéo
thì bong bóng di chuyển theo tay. Nếu chạm và di chuyển ngay (không giữ yên
trước) thì **toả ra menu cánh quạt 4 nút hướng** quanh bong bóng (trên/dưới/
trái/phải) — di chuyển ngón tay qua lại giữa các hướng để đổi ý trước khi
nhấc tay, nút đang được chọn sẽ sáng lên; nhấc tay ở đâu thì chốt hành động
theo hướng đang sáng lúc đó (nhấc tay ở vùng giữa, chưa đủ xa hướng nào →
tính là chạm). Ngưỡng thời gian giữ (`LONG_PRESS_MS`) và khoảng cách tối
thiểu để tính là vuốt (`SWIPE_MIN_DISTANCE_DP`) nằm ở đầu file
`ScreenCaptureService.kt`.

## Kiến trúc

```
MediaProjection -> VirtualDisplay -> Surface (MediaCodec, HW encoder)
    -> luồng riêng đọc output encoder -> RollingBuffer (RAM, N giây theo lựa chọn)

Chạm (khi đang quay) -> MomentStore.addMark(): dump snapshot buffer ra file
    tạm trong cacheDir (nhanh, không ảnh hưởng encoder)

Vuốt phải (dừng) -> MomentStore.exportAll(): đọc lại từng file tạm, mux
    thành từng .mp4 riêng trong Movies/GameReplay, xoá file tạm
```

- `BubbleView.kt` — vẽ hình tròn bong bóng, đổi màu/icon theo trạng thái.
- `PetalMenuView.kt` — menu cánh quạt 4 hướng, hiện ra ngay khi phát hiện
  đang vuốt (trước khi nhấc tay), highlight hướng đang chọn theo vị trí ngón
  tay hiện tại — cho phép đổi hướng giữa chừng mà không bị hiểu nhầm là kéo
  bong bóng (2 trạng thái "đang kéo" và "đang chọn hướng vuốt" tách biệt
  hoàn toàn ngay từ lúc nhận diện cử chỉ).
- `RollingBuffer.kt` — hàng đợi frame đã encode trong RAM, cửa sổ thời gian
  có thể đổi động (theo lựa chọn 15/30/60/90s).
- `MomentStore.kt` — dump khoảnh khắc ra file tạm lúc đánh dấu, batch-export
  tất cả thành mp4 lúc kết thúc phiên.
- `ScreenCaptureService.kt` — foreground service: pipeline quay + toàn bộ
  logic bắt cử chỉ trên bong bóng (state machine IDLE/RECORDING/PAUSED/DOCKED).
- `MainActivity.kt` — chỉ còn vai trò xin quyền (overlay, quay màn hình,
  thông báo) rồi bàn giao hoàn toàn cho bong bóng.

## Vì sao đọc kích thước màn hình lúc CHẠM lần đầu, không phải lúc mở app

Nếu đọc lúc mở app Game Replay Recorder (đang ở màn hình dọc), rồi mới
chuyển vào game (xoay ngang), pipeline sẽ bị cấu hình sai hướng ngay từ đầu
→ video ra bị kẹp dọc/ngang (lỗi đã gặp và sửa qua nhiều vòng test). Vì vậy
`startPipeline()` chỉ được gọi trong `onTap()` khi trạng thái đang là IDLE —
tức đúng lúc bạn chạm bong bóng lần đầu, mà theo thiết kế, bạn sẽ chạm lúc
đã ở trong game.

## Thông số mặc định (sửa trong `ScreenCaptureService.kt`)

| Thông số | Giá trị |
|---|---|
| Độ phân giải | Tự động theo màn hình thực tế lúc chạm bắt đầu |
| FPS | 60 |
| Bitrate | 10 Mbps |
| Keyframe interval | 2s |
| Thời lượng lưu mặc định | 30 giây (đổi nhanh qua vuốt lên: 15/30/60/90s) |

Máy yếu hơn/pin yếu: giảm `VIDEO_BITRATE` xuống 6-8Mbps.

## Cách dùng

1. Mở app → bấm **"Cấp quyền hiển thị nổi"**, cho phép overlay.
2. Bấm **"Cấp quyền quay màn hình"**, chọn **Share one app → Liên Quân
   Mobile** (game nên đang chạy sẵn). Bong bóng tròn màu xanh hiện lên,
   **chưa quay gì**.
3. Vào/chuyển hẳn vào game, chơi tới khi máy đã xoay ngang.
4. **Chạm bong bóng khi đang ở trong game** → bắt đầu quay, bong bóng chuyển
   đỏ.
5. Chơi bình thường. Thấy pha hay → **chạm** bong bóng để đánh dấu (rung 2
   lần xác nhận). Muốn đổi độ dài đoạn lưu → **vuốt lên** chọn 15/30/60/90s.
   Sắp vào loading/AFK → **vuốt trái** để tạm dừng, xong **vuốt trái** lần
   nữa để tiếp tục. Bong bóng che tầm nhìn → **kéo chậm** dời chỗ, hoặc
   **vuốt xuống** để dính gọn vào cạnh màn hình.
6. Hết trận → **vuốt phải** để dừng quay. App tự xuất toàn bộ khoảnh khắc đã
   đánh dấu thành các file `.mp4` riêng trong `Movies/GameReplay/`.
7. Kéo các file đó vào CapCut/editor để cắt/ghép rồi đăng TikTok.

## Giới hạn hiện tại / hướng mở rộng tiếp theo

- **Chưa có âm thanh.** Vẫn là hạn chế lớn nhất, nên làm tiếp theo (dùng
  `AudioPlaybackCaptureConfiguration`, Android 10+, tận dụng chung token
  MediaProjection đã có).
- **Thanh chọn thời lượng đang là 4 nút bấm** (15/30/60/90s), chưa phải kiểu
  "vuốt lên rồi kéo ngang để chọn" như thiết kế gốc — làm vậy nhanh hơn cho
  bản đầu, chức năng tương đương (chọn 1 trong 4 mốc), có thể nâng cấp thành
  slider kéo thật sau nếu muốn cảm giác mượt hơn.
- **Nhận diện âm báo game để tự động đánh dấu** ("Mega Kill", "Pentakill")
  — khả thi bằng audio fingerprinting (MFCC + so khớp mẫu, không cần
  ML nặng) trên cùng nguồn audio capture ở trên, nhưng chưa implement.
- **Không có watchdog khi bị OS kill nền** — nếu MIUI dọn RAM giữa trận,
  service chết thì mất buffer, không có cảnh báo. Nên xin thêm miễn trừ tối
  ưu hoá pin cho app.
- **Chưa xử lý xoay lại giữa phiên** — nếu đang RECORDING mà bạn thoát ra
  rồi xoay dọc rồi vào lại, pipeline vẫn giữ kích thước cũ (không tự phát
  hiện đổi hướng giữa chừng).
- Icon bong bóng đang là hình tròn đơn giản vẽ bằng code — có thể thay bằng
  icon/animation đẹp hơn khi vào giai đoạn hoàn thiện UI (theo đúng thứ tự
  bạn muốn: UX xong mới tới UI).

## Quyền cần cấp

- Quay màn hình (MediaProjection) — hỏi mỗi lần cấp quyền ở bước 2.
- Hiển thị đè lên ứng dụng khác (overlay) — cho bong bóng nổi.
- Thông báo (Android 13+) — bắt buộc với foreground service.
- Rung (VIBRATE) — phản hồi haptic cho từng cử chỉ.

## Build

Build qua GitHub Actions (`.github/workflows/build.yml`) — push lên nhánh
`main` hoặc chạy tay qua tab Actions, APK debug sẽ nằm trong **Artifacts**
của run đó.
