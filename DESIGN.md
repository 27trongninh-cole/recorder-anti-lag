# Thiết kế: Tự động phát hiện & quay "khoảnh khắc xuất thần"

Tài liệu tham chiếu duy nhất cho toàn bộ logic phát hiện, để mở lại dự án ở
cuộc trò chuyện mới mà không cần đọc lại lịch sử chat.

## Nguyên tắc cốt lõi

1. **Không dùng toạ độ tuyệt đối cố định** cho bất kỳ vùng crop nào (avatar,
   vùng OCR). HUD co giãn theo độ dài chữ, theo skin — mọi vị trí crop phải
   tính **tương đối theo vật thể vừa phát hiện được** (bounding box chữ từ
   OCR, hoặc hình tròn phát hiện được), không phải toạ độ X,Y cố định.
2. **Thà quay dư còn hơn bỏ sót.** Khi không đủ thông tin để loại trừ chắc
   chắn, mặc định quay.
3. **Mỗi trận đảm bảo có ít nhất 1 thành phẩm** — kể cả trận không có combo
   kill nào, mỗi lần audio xác nhận "bản thân hạ gục" vẫn trigger quay 1 clip
   ngắn, không chỉ chờ combo lớn.

## Luồng phát hiện

### Lớp 1 — Audio (xác thực "ai vừa hạ gục")
- Capture audio hệ thống qua `AudioPlaybackCapture API` (Android 10+)
- So khớp với các file mẫu trích từ game (không STT giọng nói chung, mà so
  khớp trực tiếp vì các câu thoại này là audio cố định — gần như so hash)
- Kết quả: xác định 1 trong 4 trạng thái mỗi lần có kill xảy ra:
  - Bản thân hạ gục → mở "cửa sổ combo" (sự kiện gốc)
  - Bản thân bị hạ → đóng streak, không quay
  - Đồng minh/địch hạ gục (không phải mình) → bỏ qua
  - Không audio nào khớp (VD do skin đổi âm thanh) → coi như lớp 2 tự quyết

### Lớp 2 — OCR (bắt thời điểm HUD xuất hiện)
- ML Kit Text Recognition, chạy trên vài fps (không phải full FPS)
- Bắt chữ "Kill"/"Hạ", tên combo (Double/Triple/Quad/Mega Kill), tên chiến
  tích phụ (First Blood, Bloodbath, Aced...)
- Bounding box của chữ dùng làm **điểm neo** để tính vùng crop avatar theo
  tỷ lệ tương đối (không cố định)

### Lớp 3 — Avatar matching (xác định "có phải tướng mình không")
- Ảnh tham chiếu: tướng người dùng chọn lúc màn hình loading, cắt tròn nội
  tiếp + resize chuẩn, tính sẵn 1 lần lúc bắt đầu trận
- Ảnh crop từ HUD: cùng resize chuẩn, tính hash, so khoảng cách Hamming
- Dùng thuật toán **dHash** (difference hash, nhẹ, đủ chính xác cho icon nhỏ)
- Áp dụng cho case combo-kill (audio không phân biệt được ai), theo luật:
  - Avatar khớp tướng mình → quay
  - Avatar khớp tướng KHÁC (xác định rõ không phải mình) → loại, không quay
  - Không nhận diện được gì rõ ràng (skin động che avatar) → mặc định quay
    (giới hạn đã chấp nhận: có thể quay dư clip của đồng đội/địch trong case
    này, xác suất thấp vì cần cả 2 điều kiện: đang combo kill + đang dùng
    skin động che avatar)

## Xử lý combo kill (Double → Mega)

Chỉ tính hợp lệ nếu combo xuất hiện trong cửa sổ thời gian ngắn (~10s) ngay
sau 1 sự kiện gốc "bản thân hạ gục" đã được audio xác nhận. Nếu không có sự
kiện gốc nào trước đó (audio không khớp) → dùng avatar matching (Lớp 3) để
quyết định.

## Danh sách giới hạn đã biết

- Vài skin HUD có animation phức tạp, đổi cả cấu trúc hiển thị (không chỉ
  hiệu ứng nền) → nằm ngoài khả năng nhận diện hiện tại, xử lý sau
- Chiến tích phụ (Bloodbath, First Blood, Aced...) không kèm tên người chơi
  trên HUD → không phân biệt được phe, xử lý theo luật "mặc định quay" ở
  Lớp 3 hoặc gắn với sự kiện gốc gần nhất

## Trạng thái test hiện tại

- Overlay: ổn định trong game, kể cả khi MediaProjection đang chạy
- Ghi hình buffer: hoạt động, ~N giây gần nhất cắt chính xác, không lag
  (encode phần cứng qua Surface, độ phân giải 1920px cạnh dài, bitrate VBR
  14Mbps)
- Detection (audio + OCR + avatar): đang test với 2-3 avatar mẫu + 1-2 audio
  mẫu, xem `app/src/main/assets/avatars/README.md` và
  `app/src/main/assets/audio_samples/README.md` để biết cách nạp dữ liệu mẫu
