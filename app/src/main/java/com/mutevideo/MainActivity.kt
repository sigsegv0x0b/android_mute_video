package com.mutevideo

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mutevideo.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val videoMuter = VideoMuter()
    private var progressLine = false

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            muteVideo(uri)
        }
    }

    private val pickTrimVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            lifecycleScope.launch {
                val cacheFile = withContext(Dispatchers.IO) {
                    val f = java.io.File(cacheDir, "trim_preview_${System.nanoTime()}")
                    contentResolver.openInputStream(uri)?.use { input ->
                        f.outputStream().use { output -> input.copyTo(output) }
                    }
                    f
                }
                val intent = Intent(this@MainActivity, TrimActivity::class.java).apply { data = Uri.fromFile(cacheFile) }
                startActivity(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.trimButton.setOnClickListener {
            pickTrimVideo.launch(arrayOf("video/*"))
        }

        binding.muteButton.setOnClickListener {
            pickVideo.launch(arrayOf("video/*"))
        }
    }

    private fun muteVideo(inputUri: Uri) {
        lifecycleScope.launch {
            binding.muteButton.isEnabled = false
            binding.logText.text = ""

            val result = videoMuter.muteVideo(this@MainActivity, inputUri) { msg ->
                appendLog(msg)
            }

            result.onSuccess { outputPath ->
                appendLog("Done: saved to Downloads")
                Toast.makeText(this@MainActivity, "Muted video saved", Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                val msg = if (error.message != null) error.message else "${error::class.simpleName}"
                Log.e("MuteVideo", "Failed to mute video", error)
                appendLog("FAILED: $msg")
                Toast.makeText(this@MainActivity, "Failed: $msg", Toast.LENGTH_LONG).show()
            }

            binding.muteButton.isEnabled = true
        }
    }

    private fun appendLog(msg: String) {
        lifecycleScope.launch {
            val current = binding.logText.text.toString()
            val empty = current.isEmpty() || current == "Select a video to mute"

            val newText = when {
                msg.startsWith("  ") && progressLine -> {
                    val lines = current.lines()
                    if (lines.isNotEmpty()) lines.dropLast(1).joinToString("\n") + "\n" + msg else msg
                }
                empty -> msg
                else -> "$current\n$msg"
            }

            progressLine = msg == "Processing frames..." || (msg.startsWith("  ") && progressLine)

            binding.logText.text = newText
            binding.logScroll.post { binding.logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }
}
