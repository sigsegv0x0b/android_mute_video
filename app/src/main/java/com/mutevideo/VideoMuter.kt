package com.mutevideo

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer

class VideoMuter {

    suspend fun muteVideo(context: Context, inputUri: Uri, onLog: (String) -> Unit = {}): Result<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            onLog("Checking cache dir...")
            val cacheDir = context.cacheDir
            val inputFile = File(cacheDir, "input_${System.nanoTime()}")
            val outputFile = File(cacheDir, "output_${System.nanoTime()}.mp4")

            onLog("Opening input file...")
            copyUriToFile(context, inputUri, inputFile)
            val fileSize = inputFile.length()
            onLog("File size: ${formatFileSize(fileSize)}")

            onLog("Initializing MediaExtractor...")
            val extractor = MediaExtractor()
            extractor.setDataSource(inputFile.absolutePath)

            val trackCount = extractor.trackCount
            onLog("Found $trackCount tracks")

            var videoTrackIndex = -1
            var rawFormat: MediaFormat? = null
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: "unknown"
                val info = formatDetails(format)
                onLog("  Track $i: $mime")
                info.forEach { onLog("    $it") }
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    rawFormat = format
                }
            }

            if (videoTrackIndex == -1 || rawFormat == null) {
                extractor.release()
                inputFile.delete()
                return@withContext Result.failure(Exception("No video track found"))
            }

            val mime = rawFormat.getString(MediaFormat.KEY_MIME)!!
            onLog("Selected video track $videoTrackIndex: $mime")

            extractor.selectTrack(videoTrackIndex)

            val width = rawFormat.getInteger(MediaFormat.KEY_WIDTH, -1)
            val height = rawFormat.getInteger(MediaFormat.KEY_HEIGHT, -1)
            onLog("Resolution: ${width}x$height")

            onLog("Building clean format for muxer...")
            val cleanFormat = MediaFormat.createVideoFormat(mime, width, height)

            val csd0 = rawFormat.getByteBuffer("csd-0")
            val csd1 = rawFormat.getByteBuffer("csd-1")
            if (csd0 != null) {
                onLog("  csd-0: ${csd0.remaining()} bytes")
                cleanFormat.setByteBuffer("csd-0", csd0)
            }
            if (csd1 != null) {
                onLog("  csd-1: ${csd1.remaining()} bytes")
                cleanFormat.setByteBuffer("csd-1", csd1)
            }

            if (rawFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                cleanFormat.setInteger(MediaFormat.KEY_FRAME_RATE, rawFormat.getInteger(MediaFormat.KEY_FRAME_RATE))
            }
            if (rawFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
                cleanFormat.setInteger(MediaFormat.KEY_BIT_RATE, rawFormat.getInteger(MediaFormat.KEY_BIT_RATE))
            }

            val rotation = if (rawFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                rawFormat.getInteger(MediaFormat.KEY_ROTATION)
            } else 0
            onLog("Rotation: ${rotation}deg")

            onLog("Creating MediaMuxer...")
            val muxer: MediaMuxer
            try {
                muxer = MediaMuxer(
                    outputFile.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )
                onLog("MediaMuxer created")
            } catch (e: Exception) {
                extractor.release()
                inputFile.delete()
                onLog("FAILED: MediaMuxer creation - ${e::class.simpleName}: ${e.message}")
                return@withContext Result.failure(Exception("Muxer creation failed: ${e.message}"))
            }

            if (rotation != 0) {
                muxer.setOrientationHint(rotation)
                onLog("Orientation hint set to ${rotation}deg")
            }

            onLog("Adding track to muxer...")
            val muxerTrackIndex: Int
            try {
                muxerTrackIndex = muxer.addTrack(cleanFormat)
                onLog("Track added, index=$muxerTrackIndex")
            } catch (e: Exception) {
                muxer.release()
                extractor.release()
                inputFile.delete()
                outputFile.delete()
                onLog("FAILED: addTrack - ${e::class.simpleName}: ${e.message}")
                return@withContext Result.failure(Exception("addTrack failed: ${e.message}"))
            }

            muxer.start()
            onLog("Muxer started")

            val buffer = ByteBuffer.allocate(10 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var frames = 0

            onLog("Processing frames...")
            while (true) {
                buffer.clear()
                val sampleSize: Int
                try {
                    sampleSize = extractor.readSampleData(buffer, 0)
                } catch (e: Exception) {
                    onLog("FAILED: readSampleData at frame $frames: ${e::class.simpleName}: ${e.message}")
                    throw e
                }
                if (sampleSize < 0) break

                if (sampleSize > buffer.capacity() / 2) {
                    onLog("  Frame $frames: large sample (${formatFileSize(sampleSize.toLong())})")
                }

                info.offset = 0
                info.size = sampleSize
                info.flags = extractor.sampleFlags
                info.presentationTimeUs = extractor.sampleTime

                try {
                    muxer.writeSampleData(muxerTrackIndex, buffer, info)
                } catch (e: Exception) {
                    onLog("FAILED: writeSampleData at frame $frames (size=${formatFileSize(sampleSize.toLong())}): ${e::class.simpleName}: ${e.message}")
                    throw e
                }
                extractor.advance()
                frames++
                if (frames % 100 == 0) {
                    onLog("  Processed $frames frames...")
                }
            }

            onLog("Processed $frames frames")
            onLog("Finalizing muxer...")
            muxer.stop()
            muxer.release()
            extractor.release()
            inputFile.delete()

            val outputName = "Muted_Video_${System.currentTimeMillis()}.mp4"
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
        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""

        if (format.containsKey(MediaFormat.KEY_WIDTH)) {
            details.add("${format.getInteger(MediaFormat.KEY_WIDTH)}x${format.getInteger(MediaFormat.KEY_HEIGHT)}")
        }
        if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            details.add("${format.getInteger(MediaFormat.KEY_FRAME_RATE)} fps")
        }
        if (format.containsKey(MediaFormat.KEY_ROTATION)) {
            details.add("${format.getInteger(MediaFormat.KEY_ROTATION)}deg rotation")
        }
        if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
            val br = format.getInteger(MediaFormat.KEY_BIT_RATE)
            details.add("${br / 1000} kbps")
        }
        if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            details.add("${format.getInteger(MediaFormat.KEY_SAMPLE_RATE)} Hz")
        }
        if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            details.add("${format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)} ch")
        }
        if (format.containsKey(MediaFormat.KEY_DURATION)) {
            val us = format.getLong(MediaFormat.KEY_DURATION)
            val sec = us / 1_000_000
            val min = sec / 60
            val s = sec % 60
            details.add("${min}m${s}s")
        }
        if (format.containsKey(MediaFormat.KEY_COLOR_FORMAT)) {
            val cf = format.getInteger(MediaFormat.KEY_COLOR_FORMAT)
            val cfName = when (cf) {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> "YUV420 planar"
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar -> "YUV420 packed planar"
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> "YUV420 semi-planar"
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar -> "YUV420 packed semi-planar"
                MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar -> "TI YUV420"
                else -> "0x${cf.toString(16)}"
            }
            details.add("color=$cfName")
        }
        if (mime.startsWith("video/")) {
            if (format.containsKey("csd-0")) details.add("csd-0: ${format.getByteBuffer("csd-0")?.remaining()} bytes")
            if (format.containsKey("csd-1")) details.add("csd-1: ${format.getByteBuffer("csd-1")?.remaining()} bytes")
        }
        return details
    }
}
