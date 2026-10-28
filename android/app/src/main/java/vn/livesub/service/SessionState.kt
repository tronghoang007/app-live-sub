package vn.livesub.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class SessionPhase { IDLE, STARTING, RUNNING }

data class Notice(val level: Level, val text: String) {
    enum class Level { INFO, WARNING, ERROR }
}

data class SessionStatus(
    val phase: SessionPhase = SessionPhase.IDLE,
    /** Ngôn ngữ nguồn của phiên (đã chốt hoặc đang đoán), null nếu chưa có. */
    val sourceLang: String? = null,
    /** true khi ngôn ngữ đã được chốt cho phiên (tự động hoặc người dùng khoá). */
    val sourceConfirmed: Boolean = false,
    val manuallyLocked: Boolean = false,
    val notice: Notice? = null,
)

/** Trạng thái phiên dùng chung giữa service, overlay và UI. */
object SessionState {
    private val _status = MutableStateFlow(SessionStatus())
    val status: StateFlow<SessionStatus> = _status

    fun update(transform: (SessionStatus) -> SessionStatus) = _status.update(transform)

    fun notify(notice: Notice?) = _status.update { it.copy(notice = notice) }

    fun reset(notice: Notice? = null) {
        _status.value = SessionStatus(notice = notice)
    }
}
