package com.slowdiv3.cinepulse.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class RawAudioPlayer {

    companion object {
        private const val TAG = "RawAudioPlayer"
    }

    enum class PanMode {
        OFF,
        VOLUME,
        FREQUENCY,
        ACTION_MODE,
        HORROR_MODE,
        THRILLER_MODE
    }

    enum class PanPreset {
        VOL_SMOOTH,
        FREQ_LOW_BASS,
        FREQ_HELICOPTER,
        FREQ_HIGH
    }

    @Volatile var currentMode: PanMode = PanMode.OFF
        private set

    @Volatile var currentPreset: PanPreset = PanPreset.VOL_SMOOTH

    @Volatile var lfoFreq: Float = 0.5f
        set(value) {
            field = value.coerceIn(0.1f, 2.0f)
        }

    @Volatile var depth: Float = 0.5f
        set(value) {
            field = value.coerceIn(0.0f, 1.0f)
        }

    // Filter & LFO states (accessed only on playback thread)
    private var leftPrevLow = 0.0f
    private var rightPrevLow = 0.0f
    private var lfoPhase = 0.0f

    // Playback & Threading states
    @Volatile var isExoPlaying = false
    @Volatile private var isPlaying = false
    @Volatile private var playbackThread: Thread? = null
    @Volatile private var currentAudioTrack: AudioTrack? = null

    /**
     * Updates the active mode and automatically configures cinematic presets.
     */
    fun setMode(mode: PanMode) {
        currentMode = mode
        when (mode) {
            PanMode.ACTION_MODE -> {
                currentPreset = PanPreset.FREQ_HIGH
                lfoFreq = 1.2f
                depth = 0.6f
            }
            PanMode.HORROR_MODE -> {
                currentPreset = PanPreset.FREQ_LOW_BASS
                lfoFreq = 0.3f
                depth = 0.8f
            }
            PanMode.THRILLER_MODE -> {
                currentPreset = PanPreset.VOL_SMOOTH
                lfoFreq = 0.8f
                depth = 0.5f
            }
            PanMode.VOLUME -> {
                currentPreset = PanPreset.VOL_SMOOTH
            }
            PanMode.FREQUENCY -> {
                if (currentPreset == PanPreset.VOL_SMOOTH) {
                    currentPreset = PanPreset.FREQ_LOW_BASS
                }
            }
            PanMode.OFF -> Unit
        }
    }

    private fun resetFilters() {
        leftPrevLow = 0.0f
        rightPrevLow = 0.0f
        lfoPhase = 0.0f
    }

    @Synchronized
    fun playUri(
        context: Context,
        uri: Uri,
        startTimeMs: Long = 0,
        @Suppress("UNUSED_PARAMETER") crossfadeSeconds: Int = 0,
        onError: (String) -> Unit,
        onSuccess: () -> Unit
    ) {
        Log.d(TAG, "▶ playUri CALLED: $uri")

        isPlaying = false
        playbackThread?.interrupt()
        try {
            currentAudioTrack?.pause()
        } catch (_: Exception) {
        }
        currentAudioTrack = null

        isPlaying = true
        playbackThread = Thread({
            decodeAndPlay(context, uri, startTimeMs, onError, onSuccess)
        }, "CinePulse-AudioThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }
        private fun decodeAndPlay(
        context: Context,
        uri: Uri,
        startTimeMs: Long,
        onError: (String) -> Unit,
        onSuccess: () -> Unit
    ) {
        resetFilters()

        var localExtractor: MediaExtractor? = null
        var localCodec: MediaCodec? = null
        var localAudioTrack: AudioTrack? = null
        var localPfd: ParcelFileDescriptor? = null

        try {
            localExtractor = MediaExtractor()
            if (uri.scheme == "file" && uri.path != null) {
                val file = File(uri.path!!)
                if (file.exists()) {
                    localPfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    localExtractor.setDataSource(localPfd.fileDescriptor)
                } else {
                    throw Exception("File not found: ${uri.path}")
                }
            } else {
                localExtractor.setDataSource(context, uri, null)
            }

            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until localExtractor.trackCount) {
                val f = localExtractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = f
                    break
                }
            }

            if (audioTrackIndex == -1 || format == null) {
                throw Exception("No audio track found in stream")
            }

            localExtractor.selectTrack(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val actualSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)

            if (startTimeMs > 0) {
                localExtractor.seekTo(startTimeMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            localCodec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            val minBufferSize = AudioTrack.getMinBufferSize(
                actualSampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            localAudioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(actualSampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBufferSize * 4, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            currentAudioTrack = localAudioTrack
            if (isExoPlaying) {
                localAudioTrack.play()
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            var notifiedSuccess = false

            // Smooth crossfade tracking when switching movie modes/presets
            var outgoingMode: PanMode? = null
            var outgoingPreset: PanPreset? = null
            var lastActiveMode = currentMode
            var lastActivePreset = currentPreset
            var transitionStartMs = 0L
            var transitionProgress = 1.0f
            val transitionDurationMs = 1500L

            while (isPlaying && !sawOutputEOS && !Thread.currentThread().isInterrupted) {
                val now = System.currentTimeMillis()
                val activeMode = currentMode
                val activePreset = currentPreset

                if (activeMode != lastActiveMode || activePreset != lastActivePreset) {
                    outgoingMode = lastActiveMode
                    outgoingPreset = lastActivePreset
                    lastActiveMode = activeMode
                    lastActivePreset = activePreset
                    transitionStartMs = now
                    transitionProgress = 0.0f
                }

                if (outgoingMode != null && outgoingPreset != null) {
                    val elapsed = now - transitionStartMs
                    val rawT = (elapsed.toFloat() / transitionDurationMs).coerceIn(0f, 1f)
                    transitionProgress = rawT * rawT * (3f - 2f * rawT)
                    if (rawT >= 1.0f) {
                        outgoingMode = null
                        outgoingPreset = null
                    }
                }

                // 1. Feed compressed packets into MediaCodec
                if (!sawInputEOS) {
                    val inputBufferIndex = localCodec.dequeueInputBuffer(10_000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = localCodec.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            val sampleSize = localExtractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                localCodec.queueInputBuffer(
                                    inputBufferIndex, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                sawInputEOS = true
                            } else {
                                localCodec.queueInputBuffer(
                                    inputBufferIndex, 0, sampleSize,
                                    localExtractor.sampleTime, 0
                                )
                                localExtractor.advance()
                            }
                        }
                    }
                }

                // 2. Drain decoded 16-bit PCM frames & apply real-time DSP
                val outputBufferIndex = localCodec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outputBufferIndex >= 0) {
                    val outputBuffer = localCodec.getOutputBuffer(outputBufferIndex)
                    if (bufferInfo.size > 0 && outputBuffer != null) {
                        if (!notifiedSuccess) {
                            notifiedSuccess = true
                            Handler(Looper.getMainLooper()).post { onSuccess() }
                        }

                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val bytes = ByteArray(bufferInfo.size)
                        outputBuffer.get(bytes)

                        val lfoStep = 2.0f * PI.toFloat() * (lfoFreq / actualSampleRate)

                        if (outgoingMode != null && outgoingPreset != null && transitionProgress < 1.0f) {
                            val angle = transitionProgress * (PI.toFloat() / 2.0f)
                            val outGain = cos(angle)
                            val inGain = sin(angle)

                            val outBytes = bytes.copyOf(bufferInfo.size)
                            applyModeAndPreset(outgoingMode!!, outgoingPreset!!, outBytes, bufferInfo.size, actualSampleRate, lfoStep, outGain)
                            applyModeAndPreset(activeMode, activePreset, bytes, bufferInfo.size, actualSampleRate, lfoStep, inGain)
                            mixPcmBuffers(bytes, outBytes, bufferInfo.size)
                        } else {
                            applyModeAndPreset(activeMode, activePreset, bytes, bufferInfo.size, actualSampleRate, lfoStep, 1.0f)
                        }

                        // Advance LFO phase once per buffer
                        lfoPhase += (bufferInfo.size / 4) * lfoStep
                        while (lfoPhase > 2.0f * PI.toFloat()) {
                            lfoPhase -= 2.0f * PI.toFloat()
                        }

                        // Sync playback state with ExoPlayer
                        if (isExoPlaying && localAudioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            try { localAudioTrack.play() } catch (_: Exception) {}
                        } else if (!isExoPlaying && localAudioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            try { localAudioTrack.pause() } catch (_: Exception) {}
                        }

                        localAudioTrack.write(bytes, 0, bufferInfo.size, AudioTrack.WRITE_BLOCKING)
                    }

                    localCodec.releaseOutputBuffer(outputBufferIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true
                    }
                } else {
                    Thread.sleep(10)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            if (isPlaying) {
                Handler(Looper.getMainLooper()).post {
                    onError(e.localizedMessage ?: e.toString())
                }
            }
        } finally {
            try { localCodec?.stop(); localCodec?.release() } catch (_: Exception) {}
            try { localExtractor?.release() } catch (_: Exception) {}
            try { localPfd?.close() } catch (_: Exception) {}
            try {
                if (currentAudioTrack === localAudioTrack) currentAudioTrack = null
                localAudioTrack?.stop()
                localAudioTrack?.release()
            } catch (_: Exception) {}
        }
      }
            private fun applyModeAndPreset(
        mode: PanMode,
        preset: PanPreset,
        bytes: ByteArray,
        size: Int,
        sampleRate: Int,
        lfoStep: Float,
        gain: Float
    ) {
        val safeGain = gain.coerceIn(0.0f, 1.0f)
        when (mode) {
            PanMode.OFF -> applyGain(bytes, size, safeGain)
            PanMode.VOLUME, PanMode.THRILLER_MODE -> applyVolumePan(bytes, size, lfoStep, safeGain)
            PanMode.FREQUENCY, PanMode.ACTION_MODE, PanMode.HORROR_MODE -> {
                applyFrequencyPan(preset, bytes, size, sampleRate, lfoStep, safeGain)
            }
        }
    }

    private fun mixPcmBuffers(target: ByteArray, addend: ByteArray, size: Int) {
        var i = 0
        while (i < size - 1) {
            val a = ((target[i].toInt() and 0xFF) or ((target[i + 1].toInt() and 0xFF) shl 8)).toShort().toInt()
            val b = ((addend[i].toInt() and 0xFF) or ((addend[i + 1].toInt() and 0xFF) shl 8)).toShort().toInt()
            val mixed = (a + b).coerceIn(-32768, 32767)
            target[i] = (mixed and 0xFF).toByte()
            target[i + 1] = (mixed shr 8).toByte()
            i += 2
        }
    }

    private fun applyGain(bytes: ByteArray, size: Int, gain: Float) {
        if (gain >= 0.999f) return
        var i = 0
        while (i < size - 3) {
            val sL = ((bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)).toShort()
            val sR = ((bytes[i + 2].toInt() and 0xFF) or ((bytes[i + 3].toInt() and 0xFF) shl 8)).toShort()

            val outL = (sL * gain).toInt().coerceIn(-32768, 32767).toShort()
            val outR = (sR * gain).toInt().coerceIn(-32768, 32767).toShort()

            bytes[i] = (outL.toInt() and 0xFF).toByte()
            bytes[i + 1] = (outL.toInt() shr 8).toByte()
            bytes[i + 2] = (outR.toInt() and 0xFF).toByte()
            bytes[i + 3] = (outR.toInt() shr 8).toByte()
            i += 4
        }
    }

    private fun applyVolumePan(bytes: ByteArray, size: Int, lfoStep: Float, gain: Float) {
        var i = 0
        var phase = lfoPhase
        val currentDepth = depth

        while (i < size - 3) {
            val sampleL = ((bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)).toShort() / 32768f
            val sampleR = ((bytes[i + 2].toInt() and 0xFF) or ((bytes[i + 3].toInt() and 0xFF) shl 8)).toShort() / 32768f

            val pan = sin(phase) * currentDepth
            // Strictly bounded <= 1.0f to prevent clipping
            val leftGain = (1.0f - max(0f, pan)).coerceIn(0f, 1f) * gain
            val rightGain = (1.0f + min(0f, pan)).coerceIn(0f, 1f) * gain

            val outL = (sampleL * leftGain).coerceIn(-1f, 1f)
            val outR = (sampleR * rightGain).coerceIn(-1f, 1f)

            val sL = (outL * 32767f).toInt().toShort()
            val sR = (outR * 32767f).toInt().toShort()

            bytes[i] = (sL.toInt() and 0xFF).toByte()
            bytes[i + 1] = (sL.toInt() shr 8).toByte()
            bytes[i + 2] = (sR.toInt() and 0xFF).toByte()
            bytes[i + 3] = (sR.toInt() shr 8).toByte()

            i += 4
            phase += lfoStep
            if (phase > 2.0f * PI.toFloat()) phase -= 2.0f * PI.toFloat()
        }
    }
        private fun applyFrequencyPan(
        preset: PanPreset,
        bytes: ByteArray,
        size: Int,
        sampleRate: Int,
        lfoStep: Float,
        gain: Float
    ) {
        var i = 0
        var phase = lfoPhase
        val currentDepth = depth

        val baseFreq = when (preset) {
            PanPreset.FREQ_LOW_BASS -> 200f
            PanPreset.FREQ_HELICOPTER -> 1000f
            PanPreset.FREQ_HIGH -> 6000f
            else -> 1000f
        }
        val alpha = (1.0f - exp(-2.0f * PI.toFloat() * baseFreq / sampleRate)).coerceIn(0.001f, 0.999f)

        while (i < size - 3) {
            val sampleL = ((bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)).toShort() / 32768f
            val sampleR = ((bytes[i + 2].toInt() and 0xFF) or ((bytes[i + 3].toInt() and 0xFF) shl 8)).toShort() / 32768f

            val swing = (sin(phase) + 1.0f) * 0.5f

            val lowL = leftPrevLow + alpha * (sampleL - leftPrevLow)
            leftPrevLow = lowL
            val lowR = rightPrevLow + alpha * (sampleR - rightPrevLow)
            rightPrevLow = lowR

            val highL = sampleL - lowL
            val highR = sampleR - lowR

            val swapL = lowL * (1f - swing) + highL * swing
            val swapR = highR * (1f - swing) + lowR * swing

            // Convex dry/wet blend ensures effective gain never exceeds 1.0f
            val outL = ((sampleL * (1f - currentDepth)) + (swapL * currentDepth)) * gain
            val outR = ((sampleR * (1f - currentDepth)) + (swapR * currentDepth)) * gain

            val sL = (outL.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            val sR = (outR.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

            bytes[i] = (sL.toInt() and 0xFF).toByte()
            bytes[i + 1] = (sL.toInt() shr 8).toByte()
            bytes[i + 2] = (sR.toInt() and 0xFF).toByte()
            bytes[i + 3] = (sR.toInt() shr 8).toByte()

            i += 4
            phase += lfoStep
            if (phase > 2.0f * PI.toFloat()) phase -= 2.0f * PI.toFloat()
        }
    }

    fun pause() {
        isExoPlaying = false
        val track = currentAudioTrack
        try {
            if (track != null && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause()
            }
        } catch (_: Exception) {
        }
    }

    fun resume() {
        isExoPlaying = true
        val track = currentAudioTrack
        try {
            if (track != null && track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }
        } catch (_: Exception) {
        }
    }

    fun refreshAudioTrack() {
        val track = currentAudioTrack
        try {
            if (track != null && track.playState == AudioTrack.PLAYSTATE_STOPPED) {
                track.stop()
                track.release()
            } else if (track != null) {
                track.play()
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun stop() {
        isPlaying = false
        playbackThread?.interrupt()
        try {
            playbackThread?.join(1500)
        } catch (_: Exception) {
        }
        playbackThread = null
        currentAudioTrack = null
    }
}


    
