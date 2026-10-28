package vn.livesub.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

class AudioCaptureException(message: String) : Exception(message)

/**
 * Bắt âm thanh các app khác đang phát (AudioPlaybackCapture, Android 10+),
 * trả về từng chunk PCM16 mono 16 kHz dài ~100 ms.
 *
 * Chỉ bắt được luồng có usage MEDIA/UNKNOWN và app phát cho phép capture.
 * App chặn (allowAudioPlaybackCapture=false, một số trình phát DRM) sẽ cho
 * ra toàn số 0, xem [SilenceMonitor].
 */
class PlaybackAudioCapture(private val projection: MediaProjection) {

    // RECORD_AUDIO đã được kiểm tra ở MainActivity trước khi khởi động service.
    @SuppressLint("MissingPermission")
    fun chunks(): Flow<ShortArray> = flow {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            // Đệm nội bộ ~400 ms để không mất mẫu khi luồng đọc bị trễ nhất thời.
            .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_SAMPLES * 2 * 4))
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw AudioCaptureException("Không khởi tạo được bộ bắt âm thanh")
        }
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw AudioCaptureException("Hệ thống không cho phép bắt âm thanh lúc này")
            }
            while (currentCoroutineContext().isActive) {
                val chunk = ShortArray(CHUNK_SAMPLES)
                var offset = 0
                // read() có thể trả về ít hơn yêu cầu, đọc đến khi đủ một chunk.
                while (offset < CHUNK_SAMPLES) {
                    val n = record.read(chunk, offset, CHUNK_SAMPLES - offset)
                    if (n < 0) throw AudioCaptureException("Lỗi đọc âm thanh (mã $n)")
                    offset += n
                }
                emit(chunk)
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_SAMPLES = SAMPLE_RATE / 10
    }
}
