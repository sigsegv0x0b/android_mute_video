package com.mutevideo

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer

class VideoTrimmer {

    suspend fun trimVideo(
        context: Context,
        inputUri: Uri,
        startTimeUs: Long,
        endTimeUs: Long,
        onLog: (String) -> Unit = {}
    ): Result<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            onLog("Checking cache dir...")
            val cacheDir = context.cacheDir
            val inputFile = File(cacheDir, "input_${System.nanoTime()}")
            val outputFile = File(cacheDir, "output_${System.nanoTime()}.mp4")

            onLog("Opening input file...")
            copyUriToFile(context, inputUri, inputFile)
            val fileSize = inputFile.length()
            onLog("File size: ${formatFileSize(fileSize)}")

            onLog("Scanning tracks...")
            val extractor = MediaExtractor()
            extractor.setDataSource(inputFile.absolutePath)

            data class TrackInfo(val index: Int, val format: MediaFormat, val mime: String)
            val tracks = mutableListOf<TrackInfo>()

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    tracks.add(TrackInfo(i, format, mime))
                    val info = formatDetails(format)
                    onLog("  Track $i: $mime")
                    info.forEach { onLog("    $it") }
                }
            }
            extractor.release()

            if (tracks.isEmpty()) {
                inputFile.delete()
                return@withContext Result.failure(Exception("No video or audio tracks found"))
            }

            val startSec = startTimeUs / 1_000_000
            val endSec = endTimeUs / 1_000_000
            val durSec = (endTimeUs - startTimeUs) / 1_000_000
            onLog("Trim range: ${startSec / 60}m${startSec % 60}s -> ${endSec / 60}m${endSec % 60}s (${durSec / 60}m${durSec % 60}s)")

            var rotation = 0
            onLog("Creating MediaMuxer...")
            val muxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            val trackMap = mutableMapOf<Int, Int>()
            for (track in tracks) {
                val format = track.format
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""

                val cleanFormat = if (mime.startsWith("video/")) {
                    val w = format.getInteger(MediaFormat.KEY_WIDTH, 0)
                    val h = format.getInteger(MediaFormat.KEY_HEIGHT, 0)
                    val f = MediaFormat.createVideoFormat(mime, w, h)
                    copyCsd(format, f)
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE))
                        f.setInteger(MediaFormat.KEY_FRAME_RATE, format.getInteger(MediaFormat.KEY_FRAME_RATE))
                    if (format.containsKey(MediaFormat.KEY_BIT_RATE))
                        f.setInteger(MediaFormat.KEY_BIT_RATE, format.getInteger(MediaFormat.KEY_BIT_RATE))
                    if (format.containsKey(MediaFormat.KEY_ROTATION))
                        rotation = format.getInteger(MediaFormat.KEY_ROTATION)
                    f
                } else {
                    val sr = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, 44100)
                    val ch = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 2)
                    val f = MediaFormat.createAudioFormat(mime, sr, ch)
                    copyCsd(format, f)
                    if (format.containsKey(MediaFormat.KEY_BIT_RATE))
                        f.setInteger(MediaFormat.KEY_BIT_RATE, format.getInteger(MediaFormat.KEY_BIT_RATE))
                    f
                }

                val muxerIndex = muxer.addTrack(cleanFormat)
                trackMap[track.index] = muxerIndex
                onLog("  Added track ${track.index} -> muxer track $muxerIndex")
            }

            if (rotation != 0) {
                muxer.setOrientationHint(rotation)
            }

            muxer.start()
            onLog("Muxer started")

            var totalFrames = 0
            for (track in tracks) {
                val ex = MediaExtractor()
                ex.setDataSource(inputFile.absolutePath)
                ex.selectTrack(track.index)
                ex.seekTo(startTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                val buffer = ByteBuffer.allocate(10 * 1024 * 1024)
                val info = MediaCodec.BufferInfo()
                var frames = 0

                while (true) {
                    val sampleSize = ex.readSampleData(buffer, 0)
                    if (sampleSize < 0) break

                    val pts = ex.sampleTime
                    if (pts > endTimeUs) break

                    val adjustedPts = (pts - startTimeUs).coerceAtLeast(0)

                    info.offset = 0
                    info.size = sampleSize
                    info.flags = ex.sampleFlags
                    info.presentationTimeUs = adjustedPts

                    if (pts >= startTimeUs || track.mime.startsWith("video/")) {
                        muxer.writeSampleData(trackMap[track.index]!!, buffer, info)
                        frames++
                    }

                    ex.advance()
                }

                ex.release()
                totalFrames += frames
                val trackLabel = if (track.mime.startsWith("video/")) "video" else "audio"
                onLog("  $trackLabel: $frames frames")
            }

            onLog("Total: $totalFrames frames written")
            onLog("Finalizing muxer...")
            muxer.stop()
            muxer.release()
            inputFile.delete()

            val outputName = "Trimmed_Video_${System.currentTimeMillis()}.mp4"
            onLog("Saving to Downloads/$outputName ...")
            val savedPath = saveToDownloads(context, outputFile, outputName)
            onLog("Saved: Downloads/$outputName")

            onLog("Done!")
            return@withContext Result.success(savedPath)
        } catch (e: Exception) {
            onLog("ERROR: ${e::class.simpleName}: ${e.message}")
            return@withContext Result.failure(e)
        }
    }

    private fun copyCsd(source: MediaFormat, dest: MediaFormat) {
        val csd0 = source.getByteBuffer("csd-0")
        val csd1 = source.getByteBuffer("csd-1")
        if (csd0 != null) dest.setByteBuffer("csd-0", csd0)
        if (csd1 != null) dest.setByteBuffer("csd-1", csd1)
    }

    private fun copyUriToFile(context: Context, uri: Uri, dest: File) {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw Exception("Cannot open file")
        inputStream.use { input ->
            dest.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun saveToDownloads(context: Context, file: File, displayName: String): String {
        val contentValues = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw Exception("Failed to create MediaStore entry")

        val outputStream = resolver.openOutputStream(uri)
            ?: throw Exception("Failed to open output stream")
        outputStream.use { output ->
            file.inputStream().use { input ->
                input.copyTo(output)
            }
        }

        file.delete()
        return uri.toString()
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
        }
    }

    private fun formatDetails(format: MediaFormat): List<String> {
        val details = mutableListOf<String>()
        if (format.containsKey(MediaFormat.KEY_WIDTH)) {
            details.add("${format.getInteger(MediaFormat.KEY_WIDTH)}x${format.getInteger(MediaFormat.KEY_HEIGHT)}")
        }
        if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            details.add("${format.getInteger(MediaFormat.KEY_FRAME_RATE)} fps")
        }
        if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            details.add("${format.getInteger(MediaFormat.KEY_SAMPLE_RATE)} Hz")
        }
        if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            details.add("${format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)} ch")
        }
        if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
            val br = format.getInteger(MediaFormat.KEY_BIT_RATE)
            details.add("${br / 1000} kbps")
        }
        if (format.containsKey(MediaFormat.KEY_DURATION)) {
            val us = format.getLong(MediaFormat.KEY_DURATION)
            val sec = us / 1_000_000
            val min = sec / 60
            val s = sec % 60
            details.add("${min}m${s}s")
        }
        return details
    }
}
