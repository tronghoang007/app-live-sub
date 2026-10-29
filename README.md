# LiveSub – Phụ đề dịch trực tiếp trên điện thoại

App Android bắt âm thanh video đang phát (ví dụ phim trên Chrome), nhận dạng giọng nói, dịch rồi hiện phụ đề nổi trên mọi app. Phụ đề hiện cả trên TV khi chiếu màn hình (mirror).

**Mặc định mọi xử lý đều chạy trên điện thoại, không cần server và không tốn phí API.** Muốn bản dịch tự nhiên hơn thì có thể bật thêm [server dịch bằng LLM trên máy tính](#server-dịch-trên-máy-tính-tuỳ-chọn).

| Khâu | Công nghệ | Ghi chú |
|---|---|---|
| Bắt âm thanh | `AudioPlaybackCapture` + MediaProjection | PCM16, 16 kHz, mono, chunk 100 ms |
| Cắt câu, bỏ im lặng | Silero VAD (qua sherpa-onnx) | VAD chốt từng đoạn; ghép đoạn thành câu theo dấu kết câu của STT (xem [Cách ngắt câu](#cách-ngắt-câu-hoạt-động)) |
| Nhận dạng giọng nói + nhận diện ngôn ngữ | Qua [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 2.0.1, chọn một trong các model: Whisper int8 small (~100 ngôn ngữ), ReazonSpeech k2-v2 (chuyên tiếng Nhật) | Tải model trong app lần đầu |
| Kiểm tra chéo ngôn ngữ | Google ML Kit Language ID | |
| Dịch | Google ML Kit Translation (on-device) | Miễn phí, offline sau khi tải gói ~30 MB/ngôn ngữ |

## Cấu trúc

```
android/                         Project Android Studio (Gradle Kotlin DSL)
  app/src/main/java/vn/livesub/
    audio/   PlaybackAudioCapture  – bắt âm thanh hệ thống
             SilenceMonitor        – phát hiện bị chặn capture / không có âm thanh
             QuietBoost            – khuếch đại đoạn âm nhỏ khi bật "Bắt cả nói thầm"
             MusicDetector         – nhận ra đang có nhạc (tỉ lệ khung âm thấp) để tạm tắt chế độ nói thầm
    asr/     ModelManager          – tải model Whisper + VAD (tải tiếp được khi rớt mạng)
             SpeechEngine          – interface + ReazonSpeech (transducer)
             WhisperAsr            – Whisper qua OfflineRecognizer của sherpa-onnx
             TextCleaner           – bỏ từ đệm, thêm dấu kết câu tiếng Nhật trước khi dịch
             SpeechPipeline        – VAD → Whisper → partial/final
             SentenceBoundary      – dấu kết câu: đoạn VAD đã hết câu chưa, còn chỗ gộp thêm không
             AudioHistory          – 12 s âm thanh gần nhất theo vị trí tuyệt đối (khớp `SpeechSegment.start`)
             SpeechPadding         – tính khoảng âm thanh đệm 0,3 s trước/sau mỗi đoạn VAD
             SpeechMarks           – ghi từng lúc VAD ngưỡng thường có nghe ra tiếng nói (dùng khi có nhạc)
             SpeechCutter          – chọn chỗ tự cắt đoạn nói liền quá dài, bỏ phần đã cắt khi VAD chốt
             LanguageTracker       – giữ ngôn ngữ nguồn ổn định cho cả phiên
             HallucinationFilter   – lọc câu Whisper "bịa" khi gặp nhạc/ồn
             VocalNoiseFilter      – bỏ cụm tiếng rên/thở ReazonSpeech ra lẫn trong câu, theo thời điểm từng chữ
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

1. **Tải model nhận dạng.** Chọn một model rồi bấm **Tải**: Whisper small (~375 MB, đa ngôn ngữ) hoặc ReazonSpeech (~170 MB, chỉ tiếng Nhật). Nên dùng Wi-Fi, hoặc bật *Chỉ tải qua Wi-Fi* trong Cài đặt.
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
- **Cài đặt → Bắt cả nói thầm** (mặc định bật; tắt đi nếu phim nhiều nhạc nền bị bắt nhầm thành chữ): hạ ngưỡng VAD từ 0,5 xuống 0,35 và tự khuếch đại đoạn âm nhỏ tối đa ×8 (+18 dB, không khuếch đại im lặng/nhiễu nền), để bắt được giọng thì thầm. Để không bắt nhầm nhạc thành chữ, app tự nhận ra lúc đang có nhạc (`MusicDetector`: trong 2 s gần nhất, tỉ lệ khung 20 ms nhỏ hơn nửa mức trung bình dưới 0,10 là nhạc, trên 0,18 là hết, phải ổn định 0,5 s mới đổi). Khi có nhạc thì không khuếch đại, và chỉ nhận đoạn mà một VAD thứ hai ở ngưỡng thường (0,5, nghe âm thanh gốc) cũng nghe ra tiếng nói ít nhất 30% thời gian, tức là chạy như khi tắt chế độ nói thầm. Đo bằng giọng và nhạc tổng hợp trên máy tính: giọng nói, giọng thì thầm, giọng có nhạc nền nhỏ cho tỉ lệ 0,20–0,54, nhạc 0,00–0,02; nhạc thật chưa đo. Áp dụng từ phiên sau. Các ngưỡng là ước lượng, chưa chỉnh theo phim thật; bản debug ghi log `có nhạc` / `hết nhạc` và các đoạn bị bỏ vì nhạc.
- Phần phụ đề không nhận chạm, nên bạn vẫn chạm được vào video bên dưới.

## Chiếu lên TV

Overlay là một phần nội dung màn hình, nên khi mirror toàn màn hình, TV cũng hiện phụ đề:

- **Miracast / Smart View / Screen Cast:** kéo thanh thông báo xuống → *Smart View* / *Truyền màn hình* → chọn TV.
- **Google Cast:** dùng app Google Home → thiết bị → *Truyền màn hình*. Chỉ chế độ mirror mới có phụ đề. Nút Cast trong trình phát video gửi thẳng link lên TV, nên phụ đề của app sẽ không theo.

Lưu ý (giả định, chưa kiểm chứng trên mọi máy): khi mirror, một số máy chuyển âm thanh sang TV. Nếu lúc đó app báo không có âm thanh, hãy để âm thanh phát trên loa điện thoại, hoặc bắt đầu LiveSub trước rồi mới mirror.

## Server dịch trên máy tính (tuỳ chọn)

ML Kit dịch Nhật/Hàn → Việt qua tiếng Anh nên câu thường cứng và sai xưng hô. Thư mục `server/` chứa một server nhỏ chạy LLM ngay trên Mac Apple Silicon bằng [mlx-lm](https://github.com/ml-explore/mlx-lm). Server miễn phí, chạy offline và không cần API key.

- Điện thoại vẫn tự nhận dạng giọng nói, chỉ gửi **câu chữ đã nhận dạng** (không gửi âm thanh) lên máy tính trong mạng Wi-Fi. Server mặc định không dùng câu trước làm ngữ cảnh (`MAX_CONTEXT = 0`, xem số đo bên dưới).
- Khi server sẵn sàng, app **không hiện bản ML Kit** (hay sai nghĩa rồi mới bị thay): phụ đề tạm và câu final chỉ hiện chữ gốc, câu final được gửi lên server, bản dịch LLM về thì điền vào. Câu đã có trong `PhraseBook` vẫn dịch ngay. ML Kit chỉ còn là dự phòng: khi server lỗi, hoặc server chậm hơn nhịp thoại thì app bỏ câu cũ nhất đang chờ và dùng bản ML Kit cho nó (tối đa 2 câu chờ, không kể câu đang dịch).
- Server không ghi log (không lưu lại chữ phụ đề của video); terminal chỉ in trạng thái khởi động.
- Không kết nối được server thì app tự quay về ML Kit, hiện thông báo, và 15 giây sau thử gọi server lại.

Chạy server (lần đầu tự tạo venv, cài `mlx-lm` và tải model ~4,6 GB vào `~/.cache/huggingface`, **nên dùng Wi-Fi**):

```bash
cd server
./run.sh                                             # mặc định mlx-community/Qwen3-8B-4bit
./run.sh --model mlx-community/gemma-3-12b-it-4bit   # model khác trên HuggingFace mlx-community
./run.sh --genre auto                                # tự nhận cảnh thân mật (mặc định: luôn dịch kiểu phim người lớn)
./run.sh --genre general                             # phim thường
```

Server dịch từng câu không kèm ngữ cảnh nên không biết đang là cảnh gì, và hay chọn nghĩa thông thường (`気持ちいい？` → "Có dễ chịu không?", `イっちゃう` → "I chứ"). Mặc định server luôn dịch kiểu phim người lớn (`--genre adult`). Với `--genre auto`, server tự nhận cảnh thân mật (hiện chỉ cho tiếng Nhật) bằng từ khoá trong khoảng 10 câu gần nhất, chỉ đếm điểm, không lưu chữ. Câu có từ khoá rõ (`イっちゃう`, `エッチ`...) dịch kiểu người lớn ngay; các từ cũng gặp ở phim thường (`濡れ`, `舐め`, `入れて`...) phải dồn đủ điểm mới chuyển. Tiếng rên, thở (`あっ`, `はぁ`) chỉ giữ chế độ chứ không làm chuyển, để phim kinh dị, hành động không bị nhận nhầm. Hết 8 câu liền không còn từ khoá thì về dịch thường. Ở chế độ người lớn, câu lệnh hệ thống thêm hướng dẫn không né tránh, cách đoán người nói để xưng anh/em, và các từ hay bị dịch sai (riêng cặp Nhật → Việt). `--genre general` dịch như phim thường. Câu lệnh hệ thống được cache nên không chậm thêm mỗi câu (~1,3 giây trên M1); lúc khởi động server nạp sẵn cả hai thể loại cho cặp Nhật → Việt (thêm ~8 giây).

Trên điện thoại, mở **Cài đặt → Server dịch trên máy tính** và bật **Dịch bằng server trên máy tính**. Không cần nhập IP: server tự quảng bá qua Bonjour/mDNS (`_livesub._tcp`, bằng `dns-sd` có sẵn trên macOS), app tự tìm thấy và hiện tên máy. Trong phiên phụ đề, app tìm server liên tục, nên bật server sau cũng được. Khi kết nối được, overlay hiện "Đã kết nối server dịch".

Nếu mạng chặn multicast (một số router, Wi-Fi công cộng) thì app không tự tìm được. Khi đó nhập `IP:8765` vào ô **Địa chỉ nhập tay**, IP do `run.sh` in ra. Bật/tắt có hiệu lực từ phiên phụ đề tiếp theo. Lần đầu macOS có thể hỏi có cho Python nhận kết nối mạng không: chọn **Cho phép**.

Lưu ý:
- Server không có xác thực, chỉ nên chạy trong mạng nhà. Khi tự tìm, app dùng server `_livesub._tcp` đầu tiên thấy trong mạng.
- Chưa thử tự tìm server khi Mac kết nối qua điểm phát Wi-Fi (hotspot) của chính điện thoại.
- **Tốc độ** (M1 16 GB, Qwen3-8B-4bit, đo trên chính Mac, trung vị): **khoảng 1,2 giây mỗi câu**, trước đây khoảng 2,8–3,1 giây. Phần chậm là *đọc prompt* (prefill, chỉ ~70 token/giây trên M1; sinh chữ thì ~15 token/giây mà bản dịch chỉ vài chục token), nên server giữ KV cache của câu lệnh hệ thống (~125 token) và chỉ đọc đúng câu mới. Kết quả giống hệt đường không cache (19/19 câu). Chưa đo lại qua Wi-Fi từ điện thoại sau thay đổi này.
- **Những gì đã thử và bỏ**, để khỏi thử lại:
  - Giữ cache theo cả hội thoại (câu trước làm lượt trò chuyện): cũng ~1,2 giây nhưng mô hình bắt chước xưng hô và bịa "Bà ..." vì không biết ai đang nói.
  - Gửi 2 câu trước làm ngữ cảnh: chậm lên ~2,0 giây mà không dịch hay hơn.
  - Cho LLM xem bản ML Kit làm bản nháp để sửa tên riêng: sửa được `品川` nhưng cũng chép luôn lỗi của ML Kit, chậm thêm ~0,4 giây.
- **Chất lượng** (24 câu thoại tiếng Nhật, tự đánh giá tay): LLM hay hơn ML Kit ở câu dài, nhưng còn sai tên riêng (`品川駅にいます` ra "ga Shimbashi") và từ chuyên biệt (`二番線です` ra "Hàng hai."). Đây là giới hạn của model 8B nén 4-bit.

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

## Cách ngắt câu hoạt động

Silero VAD chỉ nghe khoảng lặng, nên nghỉ lấy hơi giữa câu (≥ 0,45 s) hay đoạn bị cắt vì quá dài đều thành một đoạn riêng. Nếu mỗi đoạn là một câu phụ đề thì bản dịch bị cụt và một câu nói vỡ thành nhiều dòng. Ngoài ra Silero cắt sát chỗ nó nhận ra tiếng nói và cấu hình của nó không có tham số đệm. Khi đối chiếu log với phụ đề của video, đầu câu hay mất: video `少々 お待ちください` thành `お待ちください`, `わあ、これ何ですか` thành `これ何ですか`; cuối câu mất âm nhẹ (`ございま`). `SpeechPipeline` vì vậy lấy thêm **0,3 s âm thanh ngay trước và sau** mỗi đoạn VAD (từ `AudioHistory`, theo `segment.start`) trước khi đưa cho engine, và dùng đoạn đệm này cả cho partial. Nếu `start` không khớp âm thanh VAD trả về thì app bỏ đệm và ghi cảnh báo vào log. Đoạn bị cắt cứng ở 6 s không đệm cuối, để không lặp lại phần đầu đoạn kế. Nguyên nhân mất đầu/cuối câu mới là suy luận từ log, chưa xác nhận bằng âm thanh gốc.

`SpeechPipeline` cũng không chốt câu ngay khi VAD chốt đoạn:

1. **Nhận dạng đoạn vừa chốt**, rồi xét chữ bằng `SentenceBoundary`:
   - Đoạn nói liền không có chỗ nghỉ: app **tự cắt** ở chỗ lặng nhất (giữa hai chữ) khi đoạn đã dài 5,5 s (`SpeechCutter`) và bỏ phần đã cắt khi VAD chốt. VAD chỉ còn cắt cứng ở 10 s làm chốt chặn: trước đây VAD cắt ở 6 s đúng mốc thời gian nên hay cắt giữa chữ (log trên máy thật: `償って` thành `償っ` rồi bị dịch "Chết đi"), và đo trên máy cũng có đoạn 7–15 s VAD không cắt. Đoạn bị cắt kiểu này **hiện thành câu ngay, không giữ chờ ghép**: trước đây giữ lại thì phụ đề đứng hình gần 14 giây ở câu nói liền 15,7 giây.
   - Chữ kết thúc bằng dấu kết câu (`。！？!?.…`, bỏ qua ngoặc đóng): hết câu, chốt final ngay.
   - Chữ không có dấu kết câu (hay gặp là dấu phẩy hoặc bỏ lửng): câu chưa dứt.
   - Riêng **câu đáp ngắn đứng riêng** (dưới 1 s, ví dụ `はい`, `うん`, `え？`) thì chốt luôn, trừ khi dừng ở dấu phẩy hoặc trợ từ. Trong hội thoại người kia nói tiếp ngay, nên nếu giữ lại chờ ghép thì Whisper nhận dạng lại cả đoạn dài và hay bỏ mất câu ngắn. Đoạn VAD từ 0,2 s trở lên mới được nhận dạng (trước là 0,3 s, làm mất các câu đáp ngắn), và câu một chữ kana có dấu hỏi/than (`え？`) không còn bị coi là rác.
   - ReazonSpeech gặp tiếng rên, thở thì ra chữ `あっ`, `んっ`, `はぁ` lẫn vào câu. `VocalNoiseFilter` gom chữ thành cụm theo thời điểm từng chữ (cách nhau quá 0,5 s là cụm khác) và chỉ bỏ cụm toàn âm rên (`あ ん っ は お ー`), giữ lời thật ở giữa (`だめ` giữa tiếng rên). Đo trên máy thật thấy chữ đầu tiên luôn được báo ở ~0,00 s và khoảng cách giữa các chữ của lời nói thật có lúc > 0,5 s (`は@0,00 い@0,88`), nên chữ đầu luôn ghép với chữ thứ hai và chữ kana đứng lẻ không bị bỏ (bản đầu bỏ chữ lẻ làm `そうね` thành `うね`). Đoạn đã bỏ tiếng rên thì không còn bị coi là "quá ít chữ so với độ dài"; bản debug ghi log `bỏ tiếng rên/thở` kèm thời điểm từng chữ.
   - ReazonSpeech không xuất dấu câu, nên khoảng lặng là hết câu, trừ khi chữ dừng ở trợ từ (`は が を に へ と も や`, ví dụ "私は" rồi mới tới "学校の学生です"). Lời chào `こんにちは`, `こんばんは`, `どうも` vẫn tính là câu trọn.
   - **Chữ tạm đứng yên thì chốt luôn:** cảnh có tiếng rên, thở liên tục thì VAD không bao giờ thấy quãng lặng, câu 1 s như `やめてください` phải chờ đủ 6 s mới tự cắt (log trên máy thật). Nếu chữ tạm (partial) không đổi trong 1,2 s giọng nói mới, người nói đã dứt và phần sau chỉ là tiếng rên/thở/nhạc: app chốt câu ngay. Cần bật phụ đề tạm.
   - Đoạn dài từ 2,5 s mà chỉ ra tối đa 3 âm giống tiếng rên (`うん`, `ん`, `あ`) thì bỏ: log trên máy thật thấy tiếng `うーん/んー` thành `うん` rồi hiện "Ừ" liên tục. Lời ngắn thật (`よし`, `いい`, `はい`) trong đoạn dài toàn tiếng rên vẫn giữ khi không có nhạc.
2. **Câu chưa dứt thì giữ lại**: chữ vẫn hiện ngay dưới dạng phụ đề tạm (màu xám). Đoạn VAD kế tiếp được ghép vào âm thanh đã giữ và **nhận dạng lại cả câu**, nên chỗ bị cắt giữa từ cũng được nghe lại liền mạch. Partial trong lúc đó cũng giải mã từ đầu câu.
3. **Chốt final** khi chữ đã có dấu kết câu, hoặc sau **0,7 s** không có giọng nói mới (cộng 0,45 s lặng của VAD), hoặc khi ghép thêm sẽ vượt **8 s** giọng nói. 8 s là mức tạm chọn để bản dịch còn vừa khung 3 dòng. Câu vượt mức này bị chốt riêng như trước.

4. **Tách tràng thoại thành từng câu** khi chốt final: nhiều người nói nối nhau không có chỗ lặng 0,45 s nên VAD gộp cả tràng tới 5,5 s thành một đoạn, Whisper ra `はい。そうです。じゃあ行こう。`. Chữ được tách theo dấu kết câu (`。！？!?`, và `.` khi theo sau là khoảng trắng; `…` và số thập phân không tách), mỗi câu thành một dòng phụ đề và được dịch riêng. Một tràng có thể ra vài câu cùng lúc nên hàng đợi dịch LLM giữ tối đa 4 câu. Khung chỉ hiện số câu theo cài đặt (mặc định 3), nên câu đầu tràng có thể bị đẩy khỏi khung ngay. ReazonSpeech không có dấu câu nên không tách được.

Chỉ câu final mới dịch bằng server LLM nên bản dịch giờ là cả câu thay vì từng mảnh.

Đánh đổi: câu bị bỏ lửng (không có dấu kết câu) ra final chậm hơn 0,7 s. Chữ và bản dịch ML Kit của nó vẫn hiện sớm dưới dạng phụ đề tạm. Các hằng số `HOLD_SAMPLES`, `CUT_MARGIN_SEC` (`SpeechPipeline`) và `MAX_SENTENCE_SEC` (`SentenceBoundary`) là giá trị ước lượng, **chưa chỉnh trên máy thật**.

## Độ trễ và log

Chỉ **bản debug** ghi logcat (`AppLog`). Bản release không ghi dòng log nào của app, vì log có cả chữ nhận dạng từ video người dùng xem; lỗi làm app dừng đột ngột vẫn được lưu để hiện ở lần mở sau (`CrashLog`). Xem thời gian từng bước ở bản debug bằng lệnh:

```bash
adb logcat -s SpeechPipeline SubtitleService
# (số liệu dưới đây chỉ minh hoạ định dạng log, không phải kết quả đo)
# chunk #12 2.3s (mới 1.1s) asr=640ms lid+redecode=15ms rtf=0.28 lang=ja conf=1.00 cut=false giữ text=昨日ね、
# chunk #12 3.4s (mới 1.1s) asr=720ms lid+redecode=0ms rtf=0.27 lang=ja conf=1.00 cut=false final text=昨日ね、駅で会ったよ。
# translate #12 ja->vi 38ms
```

Mỗi dòng `chunk` là một lần VAD chốt đoạn: `giữ` nghĩa là câu chưa dứt và được giữ lại để ghép, `final` là đã chốt. `cut=true` là đoạn bị cắt ở độ dài tối đa của VAD.

Tổng độ trễ ≈ khoảng lặng để chốt đoạn (0,45 s) + thời gian ASR + thời gian dịch, cộng thêm 0,7 s nếu chữ chưa có dấu kết câu. Thời gian ASR phụ thuộc rất nhiều vào máy và model, nên hãy đo trên máy thật. Nếu máy không xử lý kịp, app sẽ báo "Máy xử lý không kịp" và bỏ bớt âm thanh (tối đa 8 giây tồn đọng) thay vì để phụ đề trễ dần. Khi đó, hãy tắt phụ đề tạm hoặc đổi model.

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
- **Model nhỏ hơn.** Điện thoại không chạy được Whisper `large-v3` theo thời gian thực, nên app dùng Whisper small. Độ chính xác thấp hơn, nhất là khi có nhạc nền hoặc thoại chồng.
- **Engine dịch.** Thay cho lựa chọn "engine dịch", màn hình chính cho chọn **model nhận dạng**, vì dịch chỉ còn ML Kit. ML Kit dịch kém tự nhiên hơn LLM, đặc biệt Nhật/Hàn → Việt (thường dịch qua tiếng Anh làm trung gian, không xử lý tốt xưng hô hay chủ ngữ bị lược).
- **Ngưỡng 0,6.** Độ tin cậy nhận diện ngôn ngữ là tỉ lệ phiếu theo thời lượng (xem mục trên), không phải `language_probability` của faster-whisper.
- **Partial.** Whisper không phải mô hình streaming. Partial được tạo bằng cách giải mã lại đoạn âm thanh từ đầu câu, mỗi 0,5–1 giây (engine nhanh cập nhật dày hơn, bắt đầu từ 0,6 s giọng nói), và tự bỏ qua khi máy đang tồn đọng âm thanh.
- **Kiểu foreground service.** Service khai báo `mediaProjection|microphone`. Kiểu `microphone` được thêm để AudioRecord không bị tắt tiếng khi app chạy nền trên một số ROM.
- **Chạm xuyên overlay.** Cửa sổ phụ đề đặt `alpha = 0.8`, vì Android 12+ chặn chạm đi xuyên qua overlay có độ đục lớn hơn 0.8. Do đó chữ hơi trong hơn một chút so với 100%.
