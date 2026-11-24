package com.slowdiv3.cinepulse

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import com.slowdiv3.cinepulse.audio.RawAudioPlayer
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private val rawPlayer = RawAudioPlayer()

    private var currentStreamUri: Uri? = null

    private lateinit var etStreamUrl: EditText
    private lateinit var btnLoadUrl: Button
    private lateinit var freqPresetRow: LinearLayout
    private lateinit var seekSpeed: SeekBar
    private lateinit var seekDepth: SeekBar
    private lateinit var tvSpeedLabel: TextView
    private lateinit var tvDepthLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupExoPlayer()
        setupDspControls()

        // Pre-fill a sample MP4 stream so you can test right after installing the APK
        val sampleUrl = "https://googleapis.com"
        etStreamUrl.setText(sampleUrl)
    }

    private fun bindViews() {
        playerView = findViewById(R.id.playerView)
        etStreamUrl = findViewById(R.id.etStreamUrl)
        btnLoadUrl = findViewById(R.id.btnLoadUrl)
        freqPresetRow = findViewById(R.id.freqPresetRow)
        seekSpeed = findViewById(R.id.seekSpeed)
        seekDepth = findViewById(R.id.seekDepth)
        tvSpeedLabel = findViewById(R.id.tvSpeedLabel)
        tvDepthLabel = findViewById(R.id.tvDepthLabel)
    }

    private fun setupExoPlayer() {
        exoPlayer = ExoPlayer.Builder(this).build().also { player ->
            // Permanently mute ExoPlayer so only RawAudioPlayer renders audio
            player.volume = 0f
            playerView.player = player

            // Expose lock-screen & Android TV media controls
            mediaSession = MediaSession.Builder(this, player).build()

            player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        rawPlayer.resume()
                    } else {
                        rawPlayer.pause()
                    }
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                        val uri = currentStreamUri ?: return
                        rawPlayer.isExoPlaying = player.isPlaying
                        rawPlayer.playUri(
                            context = this@MainActivity,
                            uri = uri,
                            startTimeMs = newPosition.positionMs,
                            crossfadeSeconds = 0,
                            onError = { err -> showToast("Seek audio error: $err") },
                            onSuccess = {}
                        )
                    }
                }
            })
        }

        btnLoadUrl.setOnClickListener {
            val url = etStreamUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                loadMovieStream(url)
            } else {
                showToast("Enter a valid movie URL")
            }
        }
    }

    private fun loadMovieStream(url: String) {
        val uri = Uri.parse(url)
        currentStreamUri = uri

        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("CinePulse Stream")
                    .setArtist("CinePulse DSP Engine")
                    .build()
            )
            .build()

        exoPlayer?.apply {
            volume = 0f
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }

        rawPlayer.isExoPlaying = true
        rawPlayer.playUri(
            context = this,
            uri = uri,
            startTimeMs = 0L,
            crossfadeSeconds = 0,
            onError = { err -> showToast("Audio error: $err") },
            onSuccess = { showToast("CinePulse Audio Active") }
        )
    }
        private fun setupDspControls() {
        findViewById<Button>(R.id.btnModeOff).setOnClickListener {
            applyModeSelection(RawAudioPlayer.PanMode.OFF, showFreqPresets = false)
        }
        findViewById<Button>(R.id.btnModeAction).setOnClickListener {
            applyModeSelection(RawAudioPlayer.PanMode.ACTION_MODE, showFreqPresets = true)
        }
        findViewById<Button>(R.id.btnModeHorror).setOnClickListener {
            applyModeSelection(RawAudioPlayer.PanMode.HORROR_MODE, showFreqPresets = true)
        }
        findViewById<Button>(R.id.btnModeThriller).setOnClickListener {
            applyModeSelection(RawAudioPlayer.PanMode.THRILLER_MODE, showFreqPresets = false)
        }
        findViewById<Button>(R.id.btnModeVolume).setOnClickListener {
            applyModeSelection(RawAudioPlayer.PanMode.VOLUME, showFreqPresets = false)
        }
        findViewById<Button>(R.id.btnModeFreq).setOnClickListener {
            applyModeSelection(RawAudioPlayer.PanMode.FREQUENCY, showFreqPresets = true)
        }

        // Frequency Sub-Presets
        findViewById<Button>(R.id.btnPresetBass).setOnClickListener {
            rawPlayer.currentPreset = RawAudioPlayer.PanPreset.FREQ_LOW_BASS
            showToast("Preset: LOW BASS")
        }
        findViewById<Button>(R.id.btnPresetHeli).setOnClickListener {
            rawPlayer.currentPreset = RawAudioPlayer.PanPreset.FREQ_HELICOPTER
            showToast("Preset: HELICOPTER")
        }
        findViewById<Button>(R.id.btnPresetHigh).setOnClickListener {
            rawPlayer.currentPreset = RawAudioPlayer.PanPreset.FREQ_HIGH
            showToast("Preset: HIGH")
        }

        // Speed Slider: progress 0..190 maps to 0.1f..2.0f Hz
        seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val freq = 0.1f + (progress / 100f)
                if (fromUser) {
                    rawPlayer.lfoFreq = freq
                }
                tvSpeedLabel.text = String.format(Locale.US, "Speed: %.1fHz", freq)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Depth Slider: progress 0..100 maps to 0.0f..1.0f
        seekDepth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val newDepth = progress / 100f
                if (fromUser) {
                    rawPlayer.depth = newDepth
                }
                tvDepthLabel.text = String.format(Locale.US, "Depth: %d%%", progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun applyModeSelection(mode: RawAudioPlayer.PanMode, showFreqPresets: Boolean) {
        rawPlayer.setMode(mode)
        freqPresetRow.visibility = if (showFreqPresets) View.VISIBLE else View.GONE

        // Sync UI sliders with any preset values applied by ACTION / HORROR / THRILLER modes
        val speedProgress = ((rawPlayer.lfoFreq - 0.1f) * 100f).toInt().coerceIn(0, 190)
        val depthProgress = (rawPlayer.depth * 100f).toInt().coerceIn(0, 100)

        seekSpeed.progress = speedProgress
        seekDepth.progress = depthProgress
        tvSpeedLabel.text = String.format(Locale.US, "Speed: %.1fHz", rawPlayer.lfoFreq)
        tvDepthLabel.text = String.format(Locale.US, "Depth: %d%%", depthProgress)

        showToast("Mode: ${mode.name}")
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        rawPlayer.stop()
        mediaSession?.release()
        mediaSession = null
        exoPlayer?.release()
        exoPlayer = null
    }
}

