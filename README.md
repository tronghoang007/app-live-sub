# LiveSub – Phụ đề dịch trực tiếp trên điện thoại

App Android bắt âm thanh video đang phát (ví dụ phim trên Chrome), nhận dạng giọng nói, dịch rồi hiện phụ đề nổi trên mọi app. Phụ đề hiện cả trên TV khi chiếu màn hình (mirror).

**Mặc định mọi xử lý đều chạy trên điện thoại, không cần server và không tốn phí API.** Muốn bản dịch tự nhiên hơn thì có thể bật thêm [server dịch bằng LLM trên máy tính](#server-dịch-trên-máy-tính-tuỳ-chọn).

| Khâu | Công nghệ | Ghi chú |
|---|---|---|
| Bắt âm thanh | `AudioPlaybackCapture` + MediaProjection | PCM16, 16 kHz, mono, chunk 100 ms |
| Cắt câu, bỏ im lặng | Silero VAD (qua sherpa-onnx) | |
| Nhận dạng giọng nói + nhận diện ngôn ngữ | Qua [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 2.0.1, chọn một trong các model: Whisper int8 tiny/base/small (~100 ngôn ngữ), SenseVoice int8 (Nhật/Hàn/Trung/Anh/Quảng Đông, rất nhanh), ReazonSpeech k2-v2 (chuyên tiếng Nhật) | Tải model trong app lần đầu |
| Kiểm tra chéo ngôn ngữ | Google ML Kit Language ID | |
| Dịch | Google ML Kit Translation (on-device) | Miễn phí, offline sau khi tải gói ~30 MB/ngôn ngữ |

## Cấu trúc

```
android/                         Project Android Studio (Gradle Kotlin DSL)
  app/src/main/java/vn/livesub/
    audio/   PlaybackAudioCapture  – bắt âm thanh hệ thống
             SilenceMonitor        – phát hiện bị chặn capture / không có âm thanh
    asr/     ModelManager          – tải model Whisper + VAD (tải tiếp được khi rớt mạng)
             SpeechEngine          – interface + SenseVoice, ReazonSpeech (transducer)
             WhisperAsr            – Whisper qua OfflineRecognizer của sherpa-onnx
             TextCleaner           – bỏ từ đệm, thêm dấu kết câu tiếng Nhật trước khi dịch
             SpeechPipeline        – VAD → Whisper → partial/final
             LanguageTracker       – giữ ngôn ngữ nguồn ổn định cho cả phiên
             HallucinationFilter   – lọc câu Whisper "bịa" khi gặp nhạc/ồn
    translate/ SubtitleTranslator  – interface engine dịch
             MlKitTranslator       – dịch ML Kit, tự tải model khi cần
             ServerTranslator      – dịch câu final bằng server LLM trên máy tính, lỗi thì dùng ML Kit
             ServerDiscovery       – tự tìm server trong mạng qua mDNS (NsdManager)
             TextLanguageIdentifier – ML Kit Language ID
    overlay/ SubtitleOverlay       – cửa sổ phụ đề + tay nắm (khoá ngôn ngữ, kéo vị trí)
    service/ SubtitleService       – ForegroundService (mediaProjection) điều phối pipeline
    ui/      Màn hình chính, Cài đặt (Jetpack Compose)
  app/src/test/                  Unit test cho logic ASR, lọc câu, PhraseBook, chuẩn hoá địa chỉ server
server/                          Server dịch LLM (mlx-lm) chạy trên Mac, tuỳ chọn
```

## Build và cài đặt

Yêu cầu: Android Studio 2026.2 trở lên (project dùng AGP 9.4.1, Gradle 9.7.1, Kotlin 2.2.10 tích hợp trong AGP, compileSdk 36) và điện thoại Android 10+ (API 29).

1. Mở Android Studio, chọn **File → Open** rồi chọn thư mục `android/`.
2. Lần sync Gradle đầu tiên cần có mạng. Nếu `app/libs/` chưa có `sherpa-onnx-2.0.1.aar` (~49 MB), script `app/build.gradle.kts` sẽ tự tải file này từ GitHub Releases. Nếu Android Studio hỏi cài SDK Platform 36 hoặc hỏi chấp nhận giấy phép SDK, hãy đồng ý.
3. Bật **Tuỳ chọn nhà phát triển → Gỡ lỗi USB** trên điện thoại, cắm cáp, rồi bấm **Run ▶**.

Chạy bằng dòng lệnh (sau khi đã mở project trong Android Studio ít nhất một lần, để có `local.properties`). Dùng JDK đi kèm Android Studio:

```bash
cd android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest         # chạy unit test logic
./gradlew :app:installDebug              # build và cài lên máy đang cắm
```

APK nằm ở `android/app/build/outputs/apk/debug/app-debug.apk`. File AAR chứa thư viện native cho 4 ABI, nhưng app chỉ đóng gói `arm64-v8a`, `armeabi-v7a` và `x86_64` (cho emulator).

## Cấp quyền trên điện thoại

Khi mở app lần đầu:

1. **Tải model nhận dạng.** Chọn *Cân bằng (base)* (~160 MB) rồi bấm **Tải**. Máy yếu nên chọn *tiny*.
2. Bấm **Bắt đầu**. App sẽ lần lượt xin các quyền sau:
   - **Hiển thị trên ứng dụng khác:** app mở trang cài đặt hệ thống. Bật cho LiveSub rồi quay lại app và bấm Bắt đầu lần nữa.
   - **Ghi âm:** Android bắt buộc quyền này mới bắt được âm thanh hệ thống. App không dùng micro.
   - **Thông báo** (Android 13+): không bắt buộc, nhưng có nó thì dừng nhanh được từ thanh thông báo.
   - **Chia sẻ màn hình:** hỏi lại mỗi lần bắt đầu. Trên Android 14+ có thể chọn **Toàn bộ màn hình** hoặc **Một ứng dụng** (chọn app đang phát video, ví dụ Chrome). App chỉ dùng quyền này để bắt âm thanh.
3. Mở Chrome và phát video. Phụ đề hiện ở đáy màn hình.

Xiaomi/HyperOS, Oppo/ColorOS, Vivo… còn cần thêm hai bước:
- Cho phép **"Hiển thị cửa sổ bật lên khi chạy nền"**.
- Tắt **tối ưu pin** cho LiveSub.

Nếu thiếu hai bước này, hệ thống có thể tự dừng service.

### Dùng overlay

- Nhãn **`⋮⋮ JA`** cho biết ngôn ngữ đang nhận diện. Có dấu `?` nghĩa là chưa chốt. Giữ và kéo nhãn này để đổi vị trí phụ đề.
- Nút **🔓 Khoá ngôn ngữ** cố định ngôn ngữ hiện tại cho phiên. Bấm lại để mở khoá. Notification cũng có nút này cùng nút **Dừng**.
- Phụ đề tạm (partial) hiện màu xám, câu chốt (final) hiện màu trắng và thay thế câu tạm. Sau 3,5 giây im lặng, phụ đề tự xoá (chỉnh được trong Cài đặt).
- Phần phụ đề không nhận chạm, nên bạn vẫn chạm được vào video bên dưới.

## Chiếu lên TV

Overlay là một phần nội dung màn hình, nên khi mirror toàn màn hình, TV cũng hiện phụ đề:

- **Miracast / Smart View / Screen Cast:** kéo thanh thông báo xuống → *Smart View* / *Truyền màn hình* → chọn TV.
- **Google Cast:** dùng app Google Home → thiết bị → *Truyền màn hình*. Chỉ chế độ mirror mới có phụ đề. Nút Cast trong trình phát video gửi thẳng link lên TV, nên phụ đề của app sẽ không theo.

Lưu ý (giả định, chưa kiểm chứng trên mọi máy): khi mirror, một số máy chuyển âm thanh sang TV. Nếu lúc đó app báo không có âm thanh, hãy để âm thanh phát trên loa điện thoại, hoặc bắt đầu LiveSub trước rồi mới mirror.

## Server dịch trên máy tính (tuỳ chọn)

ML Kit dịch Nhật/Hàn → Việt qua tiếng Anh nên câu thường cứng và sai xưng hô. Thư mục `server/` chứa một server nhỏ chạy LLM ngay trên Mac Apple Silicon bằng [mlx-lm](https://github.com/ml-explore/mlx-lm). Server miễn phí, chạy offline và không cần API key.

- Điện thoại vẫn tự nhận dạng giọng nói, chỉ gửi **câu chữ đã nhận dạng** (không gửi âm thanh) kèm 4 câu trước làm ngữ cảnh lên máy tính trong mạng Wi-Fi.
- Chỉ câu chốt (final) được dịch bằng LLM. Phụ đề tạm (partial) vẫn dịch bằng ML Kit cho nhanh.
- Không kết nối được server thì app tự quay về ML Kit, hiện thông báo, và 15 giây sau thử gọi server lại.

Chạy server (lần đầu tự tạo venv, cài `mlx-lm` và tải model ~4,6 GB vào `~/.cache/huggingface`, **nên dùng Wi-Fi**):

```bash
cd server
./run.sh                                             # mặc định mlx-community/Qwen3-8B-4bit
./run.sh --model mlx-community/gemma-3-12b-it-4bit   # model khác trên HuggingFace mlx-community
```

Trên điện thoại, mở **Cài đặt → Server dịch trên máy tính** và bật **Dịch bằng server trên máy tính**. Không cần nhập IP: server tự quảng bá qua Bonjour/mDNS (`_livesub._tcp`, bằng `dns-sd` có sẵn trên macOS), app tự tìm thấy và hiện tên máy. Trong phiên phụ đề, app tìm server liên tục, nên bật server sau cũng được. Khi kết nối được, overlay hiện "Đã kết nối server dịch".

Nếu mạng chặn multicast (một số router, Wi-Fi công cộng) thì app không tự tìm được. Khi đó nhập `IP:8765` vào ô **Địa chỉ nhập tay**, IP do `run.sh` in ra. Bật/tắt có hiệu lực từ phiên phụ đề tiếp theo. Lần đầu macOS có thể hỏi có cho Python nhận kết nối mạng không: chọn **Cho phép**.

Lưu ý:
- Server không có xác thực, chỉ nên chạy trong mạng nhà. Khi tự tìm, app dùng server `_livesub._tcp` đầu tiên thấy trong mạng.
- Chưa thử tự tìm server khi Mac kết nối qua điểm phát Wi-Fi (hotspot) của chính điện thoại.
- Chưa đo độ trễ và chất lượng dịch trên máy thật. Ước tính 1–2 giây mỗi câu với model 8B trên M1.

## Đổi engine dịch

App dịch bằng **Google ML Kit on-device**: miễn phí, chạy offline. Đây là cùng model với chế độ offline của app Google Dịch.

Muốn thêm engine khác, hãy implement interface `translate/SubtitleTranslator` rồi thay `MlKitTranslator` trong `SubtitleService.runSession()` (xem `ServerTranslator` làm ví dụ). Nếu engine cần API key, cho người dùng nhập key trong Cài đặt và lưu bằng Android Keystore. Không hard-code key vào mã nguồn.

Để xem phim không có mạng: vào **Cài đặt → Tải trước model dịch**. App sẽ tải gói dịch EN/JA/KO/ZH/TH sang ngôn ngữ đích.

## Cách tự nhận diện ngôn ngữ hoạt động

Whisper trên máy chỉ trả về ngôn ngữ có khả năng cao nhất cho từng câu, không kèm xác suất. Vì vậy, `LanguageTracker` tính **độ tin cậy = tỉ lệ thời lượng giọng nói mà Whisper gán cho cùng một ngôn ngữ**.

1. **Lọc đầu vào:** chỉ các câu có giọng nói mới được bỏ phiếu. VAD đã bỏ đoạn im lặng. Câu "bịa" (thường xuất hiện khi có nhạc nền) bị `HallucinationFilter` loại. Câu mà ML Kit Language ID không xác định được ngôn ngữ cũng không được tính.
2. **Chốt ngôn ngữ:** khi đã gom đủ **5 giây** giọng nói và một ngôn ngữ chiếm **≥ 60%** thì chốt ngôn ngữ đó cho phiên. Nếu chưa đạt, app gom tiếp, mỗi lần xét cửa sổ trượt 10 giây gần nhất.
3. **Sau khi chốt:**
   - Câu nào Whisper đoán lệch sẽ được giải mã lại với ngôn ngữ của phiên, để phụ đề không nhảy lung tung.
   - App chỉ đổi ngôn ngữ khi **3 câu liên tiếp** (mỗi câu ≥ 1 giây) cùng ra một ngôn ngữ mới.
   - Nếu tỉ lệ đồng thuận trong 6 câu gần nhất xuống dưới 50%, app nhận diện lại từ đầu.
4. **Khoá cứng:** khoá trong app (mục *Ngôn ngữ trong video*) hoặc bằng nút trên overlay. Khi đã khoá, Whisper luôn dùng ngôn ngữ đó.
5. **Cùng ngôn ngữ:** nếu ngôn ngữ nguồn trùng ngôn ngữ đích thì app không dịch, chỉ hiện phụ đề gốc.

## Độ trễ và log

Xem thời gian từng bước bằng lệnh:

```bash
adb logcat -s SpeechPipeline SubtitleService
# final #12 2.3s asr=640ms lid+redecode=15ms lang=ja conf=1.00 text=...
# translate #12 ja->vi 38ms
```

Tổng độ trễ ≈ khoảng lặng để chốt câu (0,45 s) + thời gian ASR + thời gian dịch. Thời gian ASR phụ thuộc rất nhiều vào máy và model, nên hãy đo trên máy thật. Nếu máy không xử lý kịp, app sẽ báo "Máy xử lý không kịp" và bỏ bớt âm thanh (tối đa 8 giây tồn đọng) thay vì để phụ đề trễ dần. Khi đó, hãy chọn model nhỏ hơn hoặc tắt phụ đề tạm.

## Lỗi thường gặp

| Hiện tượng | Nguyên nhân và cách xử lý |
|---|---|
| "Có âm thanh đang phát nhưng không bắt được…" | App hoặc trang phát đang chặn ghi âm (DRM, hoặc đặt `allowAudioPlaybackCapture=false`). Các app như Netflix thường chặn. Hãy thử xem trên Chrome hoặc trình duyệt khác. Một số trang web có DRM vẫn có thể bị chặn. |
| "Không có âm thanh đang phát…" | Video đang tạm dừng, tab Chrome bị tắt tiếng (biểu tượng loa gạch chéo trên tab), hoặc âm lượng media đang ở 0. AudioPlaybackCapture lấy âm thanh sau bộ trộn, nên âm lượng media phải lớn hơn 0. |
| Không thấy phụ đề | Kiểm tra quyền "Hiển thị trên ứng dụng khác". Một số trình phát bảo vệ nội dung (FLAG_SECURE) che cả overlay khi mirror. |
| Phụ đề ra câu lạ khi có nhạc | Whisper "bịa" câu khi gặp nhạc. App đã lọc những mẫu phổ biến. Nếu vẫn sai ngôn ngữ, bấm **Khoá ngôn ngữ**. |
| Service tự tắt sau một lúc | Tắt tối ưu pin và bật quyền chạy nền (xem phần Cấp quyền). |
| Chọn "Một ứng dụng" khi chia sẻ màn hình (Android 14+) | Chưa kiểm chứng trên mọi máy là có bắt được âm thanh không. Nếu app báo không có âm thanh, hãy thử **Toàn bộ màn hình**. Đóng app đang được chia sẻ cũng làm phiên phụ đề dừng. |
| App bị dừng đột ngột | Mở lại app: màn hình chính hiện thẻ "Lần trước app bị dừng đột ngột" kèm chi tiết lỗi. Bấm **Sao chép** rồi gửi nội dung đó để sửa lỗi. |
| Tải model thất bại | Kiểm tra mạng rồi bấm **Tải** lại. Tải tiếp từ chỗ dừng. Nếu bật "Chỉ tải qua Wi-Fi" thì phải có Wi-Fi. |
| "Không nạp được model" | File model bị hỏng. Vào Cài đặt → xoá model → tải lại. |

## Giả định và khác biệt so với yêu cầu ban đầu

- **Server chỉ dùng để dịch, không nhận dạng giọng nói.** Nhận dạng vẫn chạy trên điện thoại. Server dịch (`server/`) là tuỳ chọn, chạy LLM local bằng mlx-lm trên Mac, không dùng FastAPI/Docker hay API trả phí.
- **Model nhỏ hơn.** Điện thoại không chạy được Whisper `large-v3` theo thời gian thực, nên app dùng tiny/base/small. Độ chính xác thấp hơn, nhất là khi có nhạc nền hoặc thoại chồng.
- **Engine dịch.** Thay cho lựa chọn "engine dịch", màn hình chính cho chọn **model nhận dạng** (Nhanh/Cân bằng/Chính xác), vì dịch chỉ còn ML Kit. ML Kit dịch kém tự nhiên hơn LLM, đặc biệt Nhật/Hàn → Việt (thường dịch qua tiếng Anh làm trung gian, không xử lý tốt xưng hô hay chủ ngữ bị lược).
- **Ngưỡng 0,6.** Độ tin cậy nhận diện ngôn ngữ là tỉ lệ phiếu theo thời lượng (xem mục trên), không phải `language_probability` của faster-whisper.
- **Partial.** Whisper không phải mô hình streaming. Partial được tạo bằng cách giải mã lại đoạn âm thanh từ đầu câu, mỗi ~1 giây, và tự bỏ qua khi máy đang tồn đọng âm thanh.
- **Kiểu foreground service.** Service khai báo `mediaProjection|microphone`. Kiểu `microphone` được thêm để AudioRecord không bị tắt tiếng khi app chạy nền trên một số ROM.
- **Chạm xuyên overlay.** Cửa sổ phụ đề đặt `alpha = 0.8`, vì Android 12+ chặn chạm đi xuyên qua overlay có độ đục lớn hơn 0.8. Do đó chữ hơi trong hơn một chút so với 100%.
