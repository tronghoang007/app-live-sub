package vn.livesub

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import vn.livesub.asr.ModelManager
import vn.livesub.data.SettingsRepository

/** Các đối tượng dùng chung toàn app (service locator đơn giản). */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(app)
    val models = ModelManager(app, appScope)
}

class LiveSubApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val channel = NotificationChannel(CHANNEL_ID, "Phụ đề trực tiếp", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Trạng thái phiên phụ đề và nút dừng"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "live_subtitle"
    }
}
