import java.net.HttpURLConnection
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// sherpa-onnx (VAD + Whisper on-device) không có trên Maven Central/Google Maven,
// chỉ phát hành dạng .aar trên GitHub Releases. Tải một lần lúc cấu hình Gradle
// để build "chạy là được"; file nằm trong .gitignore.
val sherpaOnnxVersion = "2.0.1"
val sherpaAar = file("libs/sherpa-onnx-$sherpaOnnxVersion.aar")
if (!sherpaAar.exists()) {
    sherpaAar.parentFile.mkdirs()
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/sherpa-onnx-$sherpaOnnxVersion.aar"
    logger.lifecycle("Downloading $url")
    var conn = URI(url).toURL().openConnection() as HttpURLConnection
    // GitHub chuyển hướng sang host khác (release-assets...), tự theo redirect.
    var redirects = 0
    while (conn.responseCode in 300..399 && redirects++ < 5) {
        val next = conn.getHeaderField("Location")
        conn.disconnect()
        conn = URI(next).toURL().openConnection() as HttpURLConnection
    }
    check(conn.responseCode == 200) { "Không tải được sherpa-onnx AAR (HTTP ${conn.responseCode})" }
    val tmp = File(sherpaAar.path + ".part")
    conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
    tmp.renameTo(sherpaAar)
}

android {
    namespace = "vn.livesub"
    compileSdk = 36

    defaultConfig {
        applicationId = "vn.livesub"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"

        ndk {
            // x86_64 để chạy thử trên emulator; bỏ đi nếu muốn APK nhỏ hơn.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            // Tắt R8: app cài tay, R8 chỉ bớt vài MB (phần lớn APK là thư viện native) nhưng dễ gây
            // lỗi chỉ có ở bản release và làm stack trace trong CrashLog khó đọc.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Ký bằng debug key để cài thử nhanh; thay bằng keystore riêng khi phát hành.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs {
            // Model .onnx được tải về lúc chạy, các .so cần giải nén để sherpa-onnx nạp.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(files(sherpaAar))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
