# sherpa-onnx gọi ngược từ JNI vào các lớp config/result theo tên trường,
# nên không được đổi tên hay xoá chúng.
-keep class com.k2fsa.sherpa.onnx.** { *; }
