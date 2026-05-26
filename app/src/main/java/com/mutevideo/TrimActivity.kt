package com.mutevideo

import android.app.AlertDialog
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mutevideo.databinding.ActivityTrimBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TrimActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTrimBinding
    private val videoTrimmer = VideoTrimmer()
    private var videoUri: Uri? = null
    private var inTimeUs: Long = -1L
    private var outTimeUs: Long = -1L
    private var isPlaying = false
    private var isDragging = false
    private var durationUs: Long = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (!isDragging && isPlaying) {
                try {
                    val pos = binding.videoView.currentPosition.toLong() * 1000L
                    updateScrubber(pos)
                } catch (_: Exception) {}
            }
            handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrimBinding.inflate(layoutInflater)
        setContentView(binding.root)

        videoUri = intent.data
        if (videoUri == null) {
            Toast.makeText(this, "No video provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupVideoView()
        setupPlayButton()
        setupScrubber()
        setupMarkers()
        setupTrimButton()
    }

    override fun onPause() {
        super.onPause()
        if (isPlaying) {
            try { binding.videoView.pause() } catch (_: Exception) {}
            isPlaying = false
            updatePlayIcon()
        }
        handler.removeCallbacks(updateRunnable)
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateRunnable)
        try { binding.videoView.stopPlayback() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun setupVideoView() {
        showLoading()
        binding.videoView.setOnPreparedListener { mp ->
            hideLoading()
            durationUs = mp.duration.toLong() * 1000L
            mp.isLooping = false
            updateTimeLabel(0L, durationUs)
            binding.scrubber.isEnabled = true
            logVideoInfo(mp.videoWidth, mp.videoHeight)
            mp.start()
            isPlaying = true
            updatePlayIcon()
            handler.post(updateRunnable)
        }
        binding.videoView.setOnCompletionListener {
            isPlaying = false
            updatePlayIcon()
            handler.removeCallbacks(updateRunnable)
        }
        binding.videoView.setOnErrorListener { _, what, extra ->
            hideLoading()
            Log.e("TrimActivity", "Video error: what=$what extra=$extra")
            showVideoErrorDialog(what, extra)
            true
        }
        binding.videoView.post { binding.videoView.setVideoURI(videoUri) }
        binding.scrubber.isEnabled = false
    }

    private fun showLoading() {
        binding.loadingOverlay.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        binding.loadingOverlay.visibility = View.GONE
    }

    private fun showVideoErrorDialog(what: Int, extra: Int) {
        val msg = when (what) {
            MediaPlayer.MEDIA_ERROR_SERVER_DIED -> "Video server died"
            MediaPlayer.MEDIA_ERROR_UNKNOWN -> "Unknown video error"
            else -> "Error code: $what / $extra"
        }
        AlertDialog.Builder(this)
            .setTitle("Failed to Load Video")
            .setMessage("$msg\n\nThe video could not be loaded. It may be corrupted or in an unsupported format.")
            .setPositiveButton("Go Back") { _, _ -> finish() }
            .setNegativeButton("Retry") { _, _ ->
                showLoading()
                binding.videoView.post { binding.videoView.setVideoURI(videoUri) }
            }
            .setCancelable(false)
            .show()
    }

    private fun logVideoInfo(videoWidth: Int, videoHeight: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            val uri = videoUri ?: return@launch
            val fd = contentResolver.openAssetFileDescriptor(uri, "r")
            val fileSize = fd?.length ?: 0L
            fd?.close()
            val info = mutableListOf<String>()
            info.add("File: ${formatFileSize(fileSize)}")

            try {
                val extractor = MediaExtractor()
                extractor.setDataSource(this@TrimActivity, uri, null)
                info.add("Tracks: ${extractor.trackCount}")
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: "unknown"
                    val parts = mutableListOf("  Track $i: $mime")
                    if (format.containsKey(MediaFormat.KEY_WIDTH))
                        parts.add("${format.getInteger(MediaFormat.KEY_WIDTH)}x${format.getInteger(MediaFormat.KEY_HEIGHT)}")
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE))
                        parts.add("${format.getInteger(MediaFormat.KEY_FRAME_RATE)} fps")
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                        parts.add("${format.getInteger(MediaFormat.KEY_SAMPLE_RATE)} Hz")
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                        parts.add("${format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)} ch")
                    if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        val br = format.getInteger(MediaFormat.KEY_BIT_RATE)
                        parts.add("${br / 1000} kbps")
                    }
                    info.add(parts.joinToString(" "))
                }
                extractor.release()
            } catch (e: Exception) {
                info.add("  (metadata read failed)")
            }

            info.add("Resolution: ${videoWidth}x${videoHeight}")
            info.add("Duration: ${timeString(durationUs)}")
            withContext(Dispatchers.Main) {
                binding.trimLogText.text = info.joinToString("\n")
            }
        }
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
    }

    private fun setupPlayButton() {
        binding.playButton.setOnClickListener {
            if (isPlaying) {
                try { binding.videoView.pause() } catch (_: Exception) {}
                handler.removeCallbacks(updateRunnable)
                isPlaying = false
            } else {
                try { binding.videoView.start() } catch (_: Exception) {}
                handler.post(updateRunnable)
                isPlaying = true
            }
            updatePlayIcon()
        }
    }

    private fun setupScrubber() {
        binding.scrubber.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && durationUs > 0) {
                    val posUs = (progress.toLong() * durationUs) / seekBar!!.max
                    updateTimeLabel(posUs, durationUs)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isDragging = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isDragging = false
                if (durationUs > 0) {
                    val posUs = (seekBar!!.progress.toLong() * durationUs) / seekBar.max
                    val posMs = (posUs / 1000L).toInt()
                    try {
                        binding.videoView.seekTo(posMs)
                        if (isPlaying) binding.videoView.start()
                    } catch (_: Exception) {}
                }
            }
        })
    }

    private fun setupMarkers() {
        binding.markInButton.setOnClickListener {
            try {
                val posMs = binding.videoView.currentPosition
                inTimeUs = posMs.toLong() * 1000L
                binding.inLabel.text = "In: ${timeString(inTimeUs)}"
                positionMarker(binding.inMarker, inTimeUs)
                binding.inMarker.visibility = View.VISIBLE
                appendLog("In point set: ${timeString(inTimeUs)}")
            } catch (_: Exception) {}
        }

        binding.markOutButton.setOnClickListener {
            try {
                val posMs = binding.videoView.currentPosition
                outTimeUs = posMs.toLong() * 1000L
                binding.outLabel.text = "Out: ${timeString(outTimeUs)}"
                positionMarker(binding.outMarker, outTimeUs)
                binding.outMarker.visibility = View.VISIBLE
                appendLog("Out point set: ${timeString(outTimeUs)}")
            } catch (_: Exception) {}
        }
    }

    private fun setupTrimButton() {
        binding.trimButton.setOnClickListener {
            if (inTimeUs < 0 || outTimeUs < 0) {
                Toast.makeText(this, "Set both In and Out points", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (inTimeUs >= outTimeUs) {
                Toast.makeText(this, "In point must be before Out point", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (durationUs <= 0) {
                Toast.makeText(this, "Video not loaded", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.trimButton.isEnabled = false
            binding.trimLogText.text = ""
            videoUri?.let { uri ->
                performTrim(uri, inTimeUs, outTimeUs)
            }
        }
    }

    private fun performTrim(uri: Uri, startUs: Long, endUs: Long) {
        lifecycleScope.launch {
            val result = videoTrimmer.trimVideo(this@TrimActivity, uri, startUs, endUs) { msg ->
                appendLog(msg)
            }

            result.onSuccess {
                appendLog("Done: saved to Downloads")
                Toast.makeText(this@TrimActivity, "Trimmed video saved", Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                val msg = if (error.message != null) error.message else "${error::class.simpleName}"
                Log.e("TrimActivity", "Failed to trim video", error)
                appendLog("FAILED: $msg")
                Toast.makeText(this@TrimActivity, "Failed: $msg", Toast.LENGTH_LONG).show()
            }

            binding.trimButton.isEnabled = true
        }
    }

    private fun updateScrubber(posUs: Long) {
        if (durationUs > 0 && !isDragging) {
            val progress = ((posUs.toFloat() / durationUs) * binding.scrubber.max).toInt()
            binding.scrubber.progress = progress.coerceIn(0, binding.scrubber.max)
            updateTimeLabel(posUs, durationUs)
        }
    }

    private fun updateTimeLabel(posUs: Long, durUs: Long) {
        binding.timeLabel.text = "${timeString(posUs)} / ${timeString(durUs)}"
    }

    private fun updatePlayIcon() {
        binding.playButton.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    private fun positionMarker(marker: View, timeUs: Long) {
        val pct = if (durationUs > 0) timeUs.toFloat() / durationUs else 0f
        marker.post {
            val scrubWidth = binding.scrubber.width - binding.scrubber.paddingLeft - binding.scrubber.paddingRight
            val offset = binding.scrubber.paddingLeft + (scrubWidth * pct).toInt() - (marker.width / 2)
            marker.translationX = offset.toFloat()
        }
    }

    private fun timeString(us: Long): String {
        val sec = us / 1_000_000
        val min = sec / 60
        val s = sec % 60
        return "%02d:%02d".format(min, s)
    }

    private fun appendLog(msg: String) {
        lifecycleScope.launch {
            val current = binding.trimLogText.text.toString()
            val empty = current.isEmpty() || current == "Open a video to trim"
            binding.trimLogText.text = if (empty) msg else "$current\n$msg"
            binding.trimLogScroll.post { binding.trimLogScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
