# Audio mẫu tham chiếu (test 1-2 câu thoại)

Đặt vào đây 1-2 file `.wav` (PCM 16-bit, mono, 44100Hz hoặc 16000Hz) trích từ
game, ví dụ:

- you_defeated_enemy.wav ("You have defeated an enemy")
- you_have_been_defeated.wav ("You have been defeated")

Nếu file gốc là định dạng khác (ogg, mp3...), cần convert sang .wav PCM mono
trước (dùng ffmpeg trên máy tính, hoặc công cụ online), vì bản test này đọc
thẳng WAV cho đơn giản, chưa decode các định dạng nén.

App sẽ tự liệt kê danh sách file trong thư mục này, tính sẵn "vân tay" âm
thanh (RMS envelope) lúc bắt đầu ghi hình, rồi so khớp liên tục với audio hệ
thống capture được trong lúc chơi.
