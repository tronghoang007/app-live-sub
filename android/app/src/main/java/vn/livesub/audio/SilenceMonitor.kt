package vn.livesub.audio

import kotlin.math.abs

/**
 * Phát hiện im lặng tuyệt đối kéo dài trong luồng capture.
 *
 * Khi app phát chặn AudioPlaybackCapture, hệ thống vẫn trả dữ liệu nhưng toàn
 * số 0 (hoặc gần 0). Im lặng tự nhiên trong phim hiếm khi tuyệt đối như vậy,
 * nên ngưỡng biên độ rất thấp đủ để phân biệt.
 */
class SilenceMonitor(
    private val warnAfterMs: Long = 8_000,
    private val amplitudeThreshold: Int = 4,
) {
    enum class State { SOUND, SILENT, LONG_SILENCE }

    private var silentMs = 0L
    var state: State = State.SOUND
        private set

    /** Trả về true khi trạng thái thay đổi. */
    fun onChunk(samples: ShortArray, chunkMs: Long): Boolean {
        val silent = samples.all { abs(it.toInt()) <= amplitudeThreshold }
        silentMs = if (silent) silentMs + chunkMs else 0
        val newState = when {
            !silent -> State.SOUND
            silentMs >= warnAfterMs -> State.LONG_SILENCE
            else -> State.SILENT
        }
        val changed = newState != state
        state = newState
        return changed
    }
}
