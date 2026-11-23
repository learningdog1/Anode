package com.nodeseek.app.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import coil3.DrawableImage
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.decodeHex
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Coil 3 [Decoder] for animated NodeSeek Fluent Emoji `.webm` stickers
 * (`/static/image/sticker/emoji/{00..48}.webm`).
 *
 * NodeSeek serves Fluent Emoji stickers as tiny 100x100, 12fps, 3-second VP9 WebM streams with
 * Matroska `AlphaMode = 1` (`BlockAdditions` / `BlockAddID = 1` grayscale VP9 alpha plane).
 * Standard Android `MediaExtractor` / `MediaMetadataRetriever` drops Matroska `BlockAdditions`
 * and produces a single static frame on an opaque black background. This decoder demuxes both
 * the color and alpha VP9 tracks directly from the EBML container, decodes them through Android's
 * software VP9 [MediaCodec] (`YUV_420_888`), composites BT.601 YUV + alpha into crisp transparent
 * `ARGB_8888` frames, and returns a shareable [AnimatedWebmDrawable] that animates natively inside
 * Jetpack Compose `AsyncImage` / `InlineTextContent`.
 */
class WebmStickerDecoder(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult? = withContext(Dispatchers.Default) {
        val rawBytes = source.use { imageSource ->
            readBoundedBytes(imageSource, MAX_WEBM_BYTES)
        } ?: return@withContext null

        val track = WebmDemuxer.parse(rawBytes) ?: return@withContext null
        if (track.frames.isEmpty()) return@withContext null

        val mimeType = when (track.codecId) {
            "V_VP9" -> MediaFormat.MIMETYPE_VIDEO_VP9
            "V_VP8" -> MediaFormat.MIMETYPE_VIDEO_VP8
            else -> return@withContext null
        }

        val bitmaps = decodeSemaphore.withPermit {
            decodeTrackToBitmaps(track, mimeType)
        } ?: return@withContext null

        if (bitmaps.isEmpty()) return@withContext null

        if (bitmaps.size == 1) {
            return@withContext DecodeResult(
                image = bitmaps[0].asImage(shareable = true),
                isSampled = false,
            )
        }

        val durations = IntArray(bitmaps.size) { index ->
            track.frames.getOrNull(index)?.durationMs?.coerceIn(MIN_FRAME_DURATION_MS, MAX_FRAME_DURATION_MS)
                ?: DEFAULT_FRAME_DURATION_MS
        }
        val drawable = AnimatedWebmDrawable(
            frames = bitmaps,
            frameDurationsMs = durations,
        )
        DecodeResult(
            image = drawable.asImage(shareable = true),
            isSampled = false,
        )
    }

    private suspend fun decodeTrackToBitmaps(
        track: WebmVideoTrack,
        mimeType: String,
    ): List<Bitmap>? {
        val hasAlphaFrames = track.hasAlpha && track.frames.any { it.alphaPayload != null }

        // Pass 1 (optional): decode the VP9 alpha plane stream (`BlockAddID = 1`) into compact
        // width*height 8-bit luma buffers (~10 KB/frame for 100x100 emojis).
        val alphaByPts = HashMap<Long, ByteArray>(track.frames.size)
        val alphaByIndex = ArrayList<ByteArray>(if (hasAlphaFrames) track.frames.size else 0)
        if (hasAlphaFrames) {
            val alphaPackets = track.frames.mapNotNull { frame ->
                val payload = frame.alphaPayload ?: return@mapNotNull null
                EncodedPacket(ptsUs = frame.ptsUs, payload = payload, isKeyFrame = frame.isKeyFrame)
            }
            if (alphaPackets.isNotEmpty()) {
                val ok = decodeEncodedPackets(
                    mimeType = mimeType,
                    width = track.width,
                    height = track.height,
                    packets = alphaPackets,
                ) { ptsUs, image ->
                    val alphaPlane = YuvAlphaConverter.extractLumaPlane(image, track.width, track.height)
                    if (alphaPlane != null) {
                        alphaByPts[ptsUs] = alphaPlane
                        alphaByIndex += alphaPlane
                    }
                }
                if (!ok) return null
            }
        }

        // Pass 2: decode the VP9 color stream and composite each frame with its matching alpha plane.
        val colorPackets = track.frames.map { frame ->
            EncodedPacket(ptsUs = frame.ptsUs, payload = frame.colorPayload, isKeyFrame = frame.isKeyFrame)
        }
        val resultBitmaps = ArrayList<Bitmap>(colorPackets.size)
        var frameIndex = 0
        val ok = decodeEncodedPackets(
            mimeType = mimeType,
            width = track.width,
            height = track.height,
            packets = colorPackets,
        ) { ptsUs, image ->
            val alpha = if (hasAlphaFrames) {
                alphaByPts[ptsUs] ?: alphaByIndex.getOrNull(frameIndex)
            } else {
                null
            }
            val argb = YuvAlphaConverter.yuvImageToArgb(
                image = image,
                alphaPlane = alpha,
                expectedWidth = track.width,
                expectedHeight = track.height,
            )
            if (argb != null) {
                resultBitmaps += Bitmap.createBitmap(
                    argb.pixels,
                    argb.width,
                    argb.height,
                    Bitmap.Config.ARGB_8888,
                )
            }
            frameIndex++
        }
        return if (ok && resultBitmaps.isNotEmpty()) resultBitmaps else null
    }

    private suspend fun decodeEncodedPackets(
        mimeType: String,
        width: Int,
        height: Int,
        packets: List<EncodedPacket>,
        onFrame: (ptsUs: Long, image: Image) -> Unit,
    ): Boolean {
        if (packets.isEmpty()) return false
        val maxPacketSize = packets.maxOf { it.payload.size }.coerceAtLeast(65_536)
        val codec = createPreferredDecoder(mimeType, width, height) ?: return false

        return try {
            val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxPacketSize)
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                )
            }
            try {
                codec.configure(format, null, null, 0)
            } catch (_: Throwable) {
                // Retry without explicit color format hint on codecs that auto-select YUV420Flexible.
                val fallbackFormat = MediaFormat.createVideoFormat(mimeType, width, height).apply {
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxPacketSize)
                }
                codec.configure(fallbackFormat, null, null, 0)
            }
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputIndexCursor = 0
            var inputEosSent = false
            var outputEosReached = false
            var idleSpinCount = 0

            while (!outputEosReached && idleSpinCount < MAX_CODEC_IDLE_SPINS) {
                currentCoroutineContext().ensureActive()

                if (!inputEosSent) {
                    val inIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuffer = codec.getInputBuffer(inIndex)
                        if (inBuffer == null) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEosSent = true
                        } else if (inputIndexCursor < packets.size) {
                            val packet = packets[inputIndexCursor++]
                            inBuffer.clear()
                            if (inBuffer.remaining() < packet.payload.size) {
                                return false
                            }
                            inBuffer.put(packet.payload)
                            val flags = if (packet.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                            codec.queueInputBuffer(
                                inIndex,
                                0,
                                packet.payload.size,
                                packet.ptsUs,
                                flags,
                            )
                        } else {
                            inBuffer.clear()
                            codec.queueInputBuffer(
                                inIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEosSent = true
                        }
                        idleSpinCount = 0
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                when {
                    outIndex >= 0 -> {
                        idleSpinCount = 0
                        val hasData = bufferInfo.size > 0 &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        if (hasData) {
                            val image = runCatching { codec.getOutputImage(outIndex) }.getOrNull()
                            if (image != null) {
                                try {
                                    onFrame(bufferInfo.presentationTimeUs, image)
                                } finally {
                                    runCatching { image.close() }
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputEosReached = true
                        }
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        idleSpinCount = 0
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        idleSpinCount++
                    }
                }
            }
            outputEosReached
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    /**
     * Prefers Android's CTS-mandated software VP9/VP8 decoders (`c2.android.vp9.decoder` /
     * `OMX.google.vp9.decoder`) over hardware decoders because 100x100 emojis fall below many
     * SoC hardware VP9 minimum resolution limits (often 128x128 or 144x144) and avoid exhausting
     * system-wide hardware video codec slots.
     */
    private fun createPreferredDecoder(mimeType: String, width: Int, height: Int): MediaCodec? {
        val codecInfos = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        }.getOrNull().orEmpty()

        val candidates = codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
        }

        fun supportsSize(info: MediaCodecInfo): Boolean = runCatching {
            val caps = info.getCapabilitiesForType(mimeType).videoCapabilities ?: return@runCatching true
            caps.isSizeSupported(width, height)
        }.getOrDefault(true)

        fun isSoftwareCodec(info: MediaCodecInfo): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (info.isSoftwareOnly) return true
            }
            val lower = info.name.lowercase()
            return lower.startsWith("c2.android.") || lower.startsWith("omx.google.")
        }

        val orderedNames = buildList {
            candidates.filter { isSoftwareCodec(it) && supportsSize(it) }
                .sortedBy { if (it.name.startsWith("c2.android.", ignoreCase = true)) 0 else 1 }
                .forEach { add(it.name) }
            candidates.filter { !isSoftwareCodec(it) && supportsSize(it) }
                .forEach { add(it.name) }
        }

        for (name in orderedNames) {
            val created = runCatching { MediaCodec.createByCodecName(name) }.getOrNull()
            if (created != null) return created
        }

        return runCatching { MediaCodec.createDecoderByType(mimeType) }.getOrNull()
    }

    private fun readBoundedBytes(imageSource: ImageSource, maxBytes: Int): ByteArray? {
        val buffered = imageSource.source()
        val out = ByteArrayOutputStream(32 * 1024)
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val read = buffered.read(chunk, 0, chunk.size)
            if (read <= 0) break
            total += read
            if (total > maxBytes) return null
            out.write(chunk, 0, read)
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            if (!isWebm(result)) return null
            return WebmStickerDecoder(result.source, options)
        }

        private fun isWebm(result: SourceFetchResult): Boolean {
            val peek = result.source.source().peek()
            if (peek.rangeEquals(0L, EBML_MAGIC)) return true
            return result.mimeType?.startsWith("video/webm", ignoreCase = true) == true
        }
    }

    private data class EncodedPacket(
        val ptsUs: Long,
        val payload: ByteArray,
        val isKeyFrame: Boolean,
    )

    companion object {
        private val EBML_MAGIC = "1a45dfa3".decodeHex()
        private val decodeSemaphore = Semaphore(permits = 2)

        private const val MAX_WEBM_BYTES = 2 * 1024 * 1024
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val MAX_CODEC_IDLE_SPINS = 250
        private const val MIN_FRAME_DURATION_MS = 16
        private const val MAX_FRAME_DURATION_MS = 1_000
        private const val DEFAULT_FRAME_DURATION_MS = 83
    }
}

/**
 * Lightweight, shareable animated [Drawable] backed by pre-decoded `ARGB_8888` frames.
 *
 * Implements [DrawableImage.SizeProvider] so Coil's memory cache accounts for the true multi-frame
 * bitmap footprint, and returns a fresh lightweight wrapper from [mutate] sharing the same
 * immutable frame list so multiple instances of the same sticker (e.g. `:emoji02: :emoji02:`)
 * share one copy in RAM while each animating with its own [Drawable.Callback].
 */
internal class AnimatedWebmDrawable(
    private val frames: List<Bitmap>,
    private val frameDurationsMs: IntArray,
    private val totalDurationMs: Long = computeTotalDurationMs(frameDurationsMs),
    private val cumulativeEndMs: LongArray = computeCumulativeEnds(frameDurationsMs),
) : Drawable(), Animatable, DrawableImage.SizeProvider {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private var running = false

    private val frameTickRunnable = Runnable {
        if (!running) return@Runnable
        invalidateSelf()
        scheduleNextFrame()
    }

    override val size: Long
        get() = frames.sumOf { it.allocationByteCount.toLong() }.coerceAtLeast(1L)

    override fun getIntrinsicWidth(): Int = frames.firstOrNull()?.width ?: -1

    override fun getIntrinsicHeight(): Int = frames.firstOrNull()?.height ?: -1

    override fun draw(canvas: Canvas) {
        if (frames.isEmpty()) return
        val index = currentFrameIndex(SystemClock.uptimeMillis())
        val frame = frames[index]
        if (frame.isRecycled) return
        srcRect.set(0, 0, frame.width, frame.height)
        canvas.drawBitmap(frame, srcRect, bounds, paint)
    }

    override fun start() {
        if (running || frames.size <= 1) return
        running = true
        invalidateSelf()
        scheduleNextFrame()
    }

    override fun stop() {
        if (!running) return
        running = false
        unscheduleSelf(frameTickRunnable)
    }

    override fun isRunning(): Boolean = running

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) {
            if (restart || running) {
                running = true
                scheduleNextFrame()
            }
        } else {
            unscheduleSelf(frameTickRunnable)
        }
        return changed
    }

    override fun mutate(): Drawable {
        // Return a fresh lightweight Drawable sharing the same immutable decoded Bitmap frames
        // so multiple Compose `DrawablePainter`s never contend on one `Drawable.Callback`.
        return AnimatedWebmDrawable(
            frames = frames,
            frameDurationsMs = frameDurationsMs,
            totalDurationMs = totalDurationMs,
            cumulativeEndMs = cumulativeEndMs,
        )
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun currentFrameIndex(uptimeMs: Long): Int {
        if (frames.size <= 1 || totalDurationMs <= 0L) return 0
        val phaseMs = Math.floorMod(uptimeMs, totalDurationMs)
        var lo = 0
        var hi = cumulativeEndMs.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (phaseMs < cumulativeEndMs[mid]) {
                hi = mid
            } else {
                lo = mid + 1
            }
        }
        return lo.coerceIn(0, frames.lastIndex)
    }

    private fun scheduleNextFrame() {
        if (!running || frames.size <= 1 || totalDurationMs <= 0L) return
        val now = SystemClock.uptimeMillis()
        val phaseMs = Math.floorMod(now, totalDurationMs)
        val idx = currentFrameIndex(now)
        val remainingMs = (cumulativeEndMs[idx] - phaseMs).coerceIn(16L, 1_000L)
        unscheduleSelf(frameTickRunnable)
        scheduleSelf(frameTickRunnable, now + remainingMs)
    }

    companion object {
        private fun computeTotalDurationMs(durations: IntArray): Long {
            var sum = 0L
            for (d in durations) sum += d.coerceAtLeast(16)
            return sum.coerceAtLeast(16L)
        }

        private fun computeCumulativeEnds(durations: IntArray): LongArray {
            val ends = LongArray(durations.size)
            var acc = 0L
            for (i in durations.indices) {
                acc += durations[i].coerceAtLeast(16)
                ends[i] = acc
            }
            return ends
        }
    }
}

// ── Pure-Kotlin Matroska / WebM Demuxer (JVM unit-testable) ───────────────────

internal data class WebmVideoFrame(
    val ptsUs: Long,
    val durationMs: Int,
    val colorPayload: ByteArray,
    val alphaPayload: ByteArray?,
    val isKeyFrame: Boolean,
)

internal data class WebmVideoTrack(
    val width: Int,
    val height: Int,
    val codecId: String,
    val hasAlpha: Boolean,
    val frames: List<WebmVideoFrame>,
)

internal object WebmDemuxer {

    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_INFO = 0x1549A966L
    private const val ID_TIMECODE_SCALE = 0x2AD7B1L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_CODEC_ID = 0x86L
    private const val ID_DEFAULT_DURATION = 0x23E383L
    private const val ID_VIDEO = 0xE0L
    private const val ID_PIXEL_WIDTH = 0xB0L
    private const val ID_PIXEL_HEIGHT = 0xBAL
    private const val ID_ALPHA_MODE = 0x53C0L
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_TIMESTAMP = 0xE7L
    private const val ID_BLOCK_GROUP = 0xA0L
    private const val ID_BLOCK = 0xA1L
    private const val ID_BLOCK_DURATION = 0x9BL
    private const val ID_REFERENCE_BLOCK = 0xFBL
    private const val ID_BLOCK_ADDITIONS = 0x75A1L
    private const val ID_BLOCK_MORE = 0xA6L
    private const val ID_BLOCK_ADD_ID = 0xEEL
    private const val ID_BLOCK_ADDITIONAL = 0xA5L
    private const val ID_SIMPLE_BLOCK = 0xA3L

    // Top-level Segment children that terminate an unknown-size Cluster.
    private val SEGMENT_CHILD_TERMINATORS = setOf(
        ID_EBML,
        ID_SEGMENT,
        ID_INFO,
        ID_TRACKS,
        ID_CLUSTER,
        0x114D9B74L, // SeekHead
        0x1C53BB6BL, // Cues
        0x1254C367L, // Tags
        0x1043A770L, // Chapters
        0x1941A469L, // Attachments
    )

    private const val MAX_STICKER_DIMENSION = 512
    private const val MAX_STICKER_FRAMES = 180
    private const val DEFAULT_TIMECODE_SCALE_NS = 1_000_000L
    private const val DEFAULT_FRAME_DURATION_MS = 83

    fun parse(data: ByteArray): WebmVideoTrack? {
        if (data.size < 12) return null
        if (data[0] != 0x1A.toByte() ||
            data[1] != 0x45.toByte() ||
            data[2] != 0xDF.toByte() ||
            data[3] != 0xA3.toByte()
        ) {
            return null
        }

        var timecodeScaleNs = DEFAULT_TIMECODE_SCALE_NS
        var videoTrackNumber = -1L
        var codecId = ""
        var width = 0
        var height = 0
        var alphaMode = 0L
        var defaultDurationNs = 0L
        val rawFrames = ArrayList<RawFrame>(48)

        var pos = 0
        val limit = data.size
        while (pos < limit) {
            val header = readElementHeader(data, pos, limit) ?: break
            val elemEnd = boundedElementEnd(header, limit)
            if (elemEnd < header.dataStart) break

            when (header.id) {
                ID_EBML -> {
                    pos = elemEnd
                }
                ID_SEGMENT -> {
                    var segPos = header.dataStart
                    while (segPos < elemEnd) {
                        val child = readElementHeader(data, segPos, elemEnd) ?: break
                        val childEnd = boundedElementEnd(child, elemEnd)
                        if (childEnd < child.dataStart) break

                        when (child.id) {
                            ID_INFO -> {
                                timecodeScaleNs = parseInfoTimecodeScale(data, child.dataStart, childEnd)
                                    ?: timecodeScaleNs
                                segPos = childEnd
                            }
                            ID_TRACKS -> {
                                val trackInfo = parseFirstVideoTrack(data, child.dataStart, childEnd)
                                if (trackInfo != null) {
                                    videoTrackNumber = trackInfo.trackNumber
                                    codecId = trackInfo.codecId
                                    width = trackInfo.width
                                    height = trackInfo.height
                                    alphaMode = trackInfo.alphaMode
                                    defaultDurationNs = trackInfo.defaultDurationNs
                                }
                                segPos = childEnd
                            }
                            ID_CLUSTER -> {
                                val nextPos = parseCluster(
                                    data = data,
                                    start = child.dataStart,
                                    end = childEnd,
                                    unknownSize = child.unknownSize,
                                    targetTrackNumber = videoTrackNumber,
                                    timecodeScaleNs = timecodeScaleNs,
                                    outFrames = rawFrames,
                                )
                                segPos = if (nextPos > child.dataStart) nextPos else childEnd
                                if (rawFrames.size >= MAX_STICKER_FRAMES) break
                            }
                            else -> {
                                segPos = childEnd
                            }
                        }
                    }
                    pos = elemEnd
                }
                else -> {
                    pos = elemEnd
                }
            }
        }

        if (videoTrackNumber <= 0L ||
            width !in 1..MAX_STICKER_DIMENSION ||
            height !in 1..MAX_STICKER_DIMENSION ||
            codecId.isEmpty() ||
            rawFrames.isEmpty()
        ) {
            return null
        }

        val defaultDurationMs = if (defaultDurationNs > 0L) {
            (defaultDurationNs / 1_000_000L).toInt().coerceIn(16, 1_000)
        } else {
            DEFAULT_FRAME_DURATION_MS
        }

        val frames = ArrayList<WebmVideoFrame>(rawFrames.size)
        for (i in rawFrames.indices) {
            val raw = rawFrames[i]
            val explicitMs = raw.durationUnits?.let { units ->
                ((units * timecodeScaleNs) / 1_000_000L).toInt()
            }
            val nextDeltaMs = rawFrames.getOrNull(i + 1)?.let { next ->
                val deltaUs = next.ptsUs - raw.ptsUs
                if (deltaUs > 0L) (deltaUs / 1_000L).toInt() else null
            }
            val durationMs = (explicitMs ?: nextDeltaMs ?: defaultDurationMs).coerceIn(16, 1_000)
            frames += WebmVideoFrame(
                ptsUs = raw.ptsUs,
                durationMs = durationMs,
                colorPayload = raw.colorPayload,
                alphaPayload = raw.alphaPayload,
                isKeyFrame = raw.isKeyFrame || i == 0,
            )
        }

        val hasAlpha = alphaMode == 1L || frames.any { it.alphaPayload != null }
        return WebmVideoTrack(
            width = width,
            height = height,
            codecId = codecId,
            hasAlpha = hasAlpha,
            frames = frames,
        )
    }

    private fun parseInfoTimecodeScale(data: ByteArray, start: Int, end: Int): Long? {
        var pos = start
        while (pos < end) {
            val child = readElementHeader(data, pos, end) ?: break
            val childEnd = boundedElementEnd(child, end)
            if (childEnd < child.dataStart) break
            if (child.id == ID_TIMECODE_SCALE) {
                val scale = readUnsignedInt(data, child.dataStart, childEnd)
                if (scale > 0L) return scale
            }
            pos = childEnd
        }
        return null
    }

    private data class ParsedTrackHeader(
        val trackNumber: Long,
        val codecId: String,
        val width: Int,
        val height: Int,
        val alphaMode: Long,
        val defaultDurationNs: Long,
    )

    private fun parseFirstVideoTrack(data: ByteArray, start: Int, end: Int): ParsedTrackHeader? {
        var pos = start
        while (pos < end) {
            val child = readElementHeader(data, pos, end) ?: break
            val childEnd = boundedElementEnd(child, end)
            if (childEnd < child.dataStart) break
            if (child.id == ID_TRACK_ENTRY) {
                val parsed = parseTrackEntry(data, child.dataStart, childEnd)
                if (parsed != null) return parsed
            }
            pos = childEnd
        }
        return null
    }

    private fun parseTrackEntry(data: ByteArray, start: Int, end: Int): ParsedTrackHeader? {
        var trackNumber = -1L
        var trackType = -1L
        var codecId = ""
        var defaultDurationNs = 0L
        var width = 0
        var height = 0
        var alphaMode = 0L

        var pos = start
        while (pos < end) {
            val child = readElementHeader(data, pos, end) ?: break
            val childEnd = boundedElementEnd(child, end)
            if (childEnd < child.dataStart) break
            when (child.id) {
                ID_TRACK_NUMBER -> trackNumber = readUnsignedInt(data, child.dataStart, childEnd)
                ID_TRACK_TYPE -> trackType = readUnsignedInt(data, child.dataStart, childEnd)
                ID_CODEC_ID -> codecId = String(data, child.dataStart, childEnd - child.dataStart, Charsets.US_ASCII)
                    .trimEnd('\u0000')
                ID_DEFAULT_DURATION -> defaultDurationNs = readUnsignedInt(data, child.dataStart, childEnd)
                ID_VIDEO -> {
                    var vPos = child.dataStart
                    while (vPos < childEnd) {
                        val vChild = readElementHeader(data, vPos, childEnd) ?: break
                        val vEnd = boundedElementEnd(vChild, childEnd)
                        if (vEnd < vChild.dataStart) break
                        when (vChild.id) {
                            ID_PIXEL_WIDTH -> width = readUnsignedInt(data, vChild.dataStart, vEnd).toInt()
                            ID_PIXEL_HEIGHT -> height = readUnsignedInt(data, vChild.dataStart, vEnd).toInt()
                            ID_ALPHA_MODE -> alphaMode = readUnsignedInt(data, vChild.dataStart, vEnd)
                        }
                        vPos = vEnd
                    }
                }
            }
            pos = childEnd
        }

        if (trackType != 1L || trackNumber <= 0L) return null
        return ParsedTrackHeader(
            trackNumber = trackNumber,
            codecId = codecId,
            width = width,
            height = height,
            alphaMode = alphaMode,
            defaultDurationNs = defaultDurationNs,
        )
    }

    private data class RawFrame(
        val ptsUs: Long,
        val durationUnits: Long?,
        val colorPayload: ByteArray,
        val alphaPayload: ByteArray?,
        val isKeyFrame: Boolean,
    )

    private fun parseCluster(
        data: ByteArray,
        start: Int,
        end: Int,
        unknownSize: Boolean,
        targetTrackNumber: Long,
        timecodeScaleNs: Long,
        outFrames: MutableList<RawFrame>,
    ): Int {
        var clusterTimestamp = 0L
        var pos = start
        while (pos < end && outFrames.size < MAX_STICKER_FRAMES) {
            val child = readElementHeader(data, pos, end) ?: break
            if (unknownSize && child.id in SEGMENT_CHILD_TERMINATORS) {
                return pos
            }
            val childEnd = boundedElementEnd(child, end)
            if (childEnd < child.dataStart) break

            when (child.id) {
                ID_TIMESTAMP -> {
                    clusterTimestamp = readUnsignedInt(data, child.dataStart, childEnd)
                }
                ID_SIMPLE_BLOCK -> {
                    parseBlockBytes(data, child.dataStart, childEnd, isSimpleBlock = true)?.let { blk ->
                        if (targetTrackNumber <= 0L || blk.trackNumber == targetTrackNumber) {
                            val absTsUnits = (clusterTimestamp + blk.relativeTimestamp).coerceAtLeast(0L)
                            val ptsUs = (absTsUnits * timecodeScaleNs) / 1_000L
                            outFrames += RawFrame(
                                ptsUs = ptsUs,
                                durationUnits = null,
                                colorPayload = blk.payload,
                                alphaPayload = null,
                                isKeyFrame = blk.isKeyFrame,
                            )
                        }
                    }
                }
                ID_BLOCK_GROUP -> {
                    parseBlockGroup(
                        data = data,
                        start = child.dataStart,
                        end = childEnd,
                        targetTrackNumber = targetTrackNumber,
                        clusterTimestamp = clusterTimestamp,
                        timecodeScaleNs = timecodeScaleNs,
                    )?.let { outFrames += it }
                }
            }
            pos = childEnd
        }
        return pos
    }

    private fun parseBlockGroup(
        data: ByteArray,
        start: Int,
        end: Int,
        targetTrackNumber: Long,
        clusterTimestamp: Long,
        timecodeScaleNs: Long,
    ): RawFrame? {
        var block: ParsedBlock? = null
        var blockDuration: Long? = null
        var alphaPayload: ByteArray? = null
        var hasReferenceBlock = false

        var pos = start
        while (pos < end) {
            val child = readElementHeader(data, pos, end) ?: break
            val childEnd = boundedElementEnd(child, end)
            if (childEnd < child.dataStart) break

            when (child.id) {
                ID_BLOCK -> {
                    block = parseBlockBytes(data, child.dataStart, childEnd, isSimpleBlock = false)
                }
                ID_BLOCK_DURATION -> {
                    blockDuration = readUnsignedInt(data, child.dataStart, childEnd)
                }
                ID_REFERENCE_BLOCK -> {
                    hasReferenceBlock = true
                }
                ID_BLOCK_ADDITIONS -> {
                    alphaPayload = parseBlockAdditionsAlpha(data, child.dataStart, childEnd) ?: alphaPayload
                }
            }
            pos = childEnd
        }

        val resolved = block ?: return null
        if (targetTrackNumber > 0L && resolved.trackNumber != targetTrackNumber) return null
        val absTsUnits = (clusterTimestamp + resolved.relativeTimestamp).coerceAtLeast(0L)
        val ptsUs = (absTsUnits * timecodeScaleNs) / 1_000L
        return RawFrame(
            ptsUs = ptsUs,
            durationUnits = blockDuration,
            colorPayload = resolved.payload,
            alphaPayload = alphaPayload,
            isKeyFrame = !hasReferenceBlock,
        )
    }

    private fun parseBlockAdditionsAlpha(data: ByteArray, start: Int, end: Int): ByteArray? {
        var pos = start
        while (pos < end) {
            val child = readElementHeader(data, pos, end) ?: break
            val childEnd = boundedElementEnd(child, end)
            if (childEnd < child.dataStart) break
            if (child.id == ID_BLOCK_MORE) {
                // Per Matroska/WebM spec, BlockAddID defaults to 1 when omitted.
                var addId = 1L
                var additional: ByteArray? = null
                var mPos = child.dataStart
                while (mPos < childEnd) {
                    val mChild = readElementHeader(data, mPos, childEnd) ?: break
                    val mEnd = boundedElementEnd(mChild, childEnd)
                    if (mEnd < mChild.dataStart) break
                    when (mChild.id) {
                        ID_BLOCK_ADD_ID -> addId = readUnsignedInt(data, mChild.dataStart, mEnd)
                        ID_BLOCK_ADDITIONAL -> {
                            if (mEnd > mChild.dataStart) {
                                additional = data.copyOfRange(mChild.dataStart, mEnd)
                            }
                        }
                    }
                    mPos = mEnd
                }
                if (addId == 1L && additional != null) {
                    return additional
                }
            }
            pos = childEnd
        }
        return null
    }

    private fun boundedElementEnd(header: ElementHeader, limit: Int): Int {
        if (header.unknownSize) return limit
        val endLong = header.dataStart.toLong() + header.size
        return min(limit.toLong(), endLong).toInt()
    }

    private data class ParsedBlock(
        val trackNumber: Long,
        val relativeTimestamp: Int,
        val isKeyFrame: Boolean,
        val payload: ByteArray,
    )

    private fun parseBlockBytes(
        data: ByteArray,
        start: Int,
        end: Int,
        isSimpleBlock: Boolean,
    ): ParsedBlock? {
        val trackVint = readVint(data, start, end, keepMarker = false, maxBytes = 8) ?: return null
        val headerPos = trackVint.nextPos
        if (headerPos + 3 > end) return null

        val relTs = (((data[headerPos].toInt() and 0xFF) shl 8) or (data[headerPos + 1].toInt() and 0xFF))
            .toShort()
            .toInt()
        val flags = data[headerPos + 2].toInt() and 0xFF
        val lacing = (flags ushr 1) and 0x03
        if (lacing != 0) return null

        val payloadStart = headerPos + 3
        if (payloadStart >= end) return null
        return ParsedBlock(
            trackNumber = trackVint.value,
            relativeTimestamp = relTs,
            isKeyFrame = isSimpleBlock && ((flags and 0x80) != 0),
            payload = data.copyOfRange(payloadStart, end),
        )
    }

    private data class VintResult(
        val value: Long,
        val nextPos: Int,
        val allOnes: Boolean,
    )

    private data class ElementHeader(
        val id: Long,
        val size: Long,
        val unknownSize: Boolean,
        val dataStart: Int,
    )

    private fun readElementHeader(data: ByteArray, pos: Int, limit: Int): ElementHeader? {
        val idVint = readVint(data, pos, limit, keepMarker = true, maxBytes = 4) ?: return null
        val sizeVint = readVint(data, idVint.nextPos, limit, keepMarker = false, maxBytes = 8) ?: return null
        if (!sizeVint.allOnes && (sizeVint.value < 0L || sizeVint.value > Int.MAX_VALUE)) return null
        return ElementHeader(
            id = idVint.value,
            size = sizeVint.value,
            unknownSize = sizeVint.allOnes,
            dataStart = sizeVint.nextPos,
        )
    }

    private fun readVint(
        data: ByteArray,
        pos: Int,
        limit: Int,
        keepMarker: Boolean,
        maxBytes: Int,
    ): VintResult? {
        if (pos >= limit) return null
        val first = data[pos].toInt() and 0xFF
        if (first == 0) return null

        var mask = 0x80
        var length = 1
        while (length <= maxBytes && (first and mask) == 0) {
            mask = mask ushr 1
            length++
        }
        if (length > maxBytes || pos + length > limit) return null

        var value = if (keepMarker) first.toLong() else (first and (mask - 1)).toLong()
        var allOnesMask = (mask - 1).toLong()
        for (i in 1 until length) {
            val b = data[pos + i].toInt() and 0xFF
            value = (value shl 8) or b.toLong()
            allOnesMask = (allOnesMask shl 8) or 0xFFL
        }
        return VintResult(
            value = value,
            nextPos = pos + length,
            allOnes = !keepMarker && value == allOnesMask,
        )
    }

    private fun readUnsignedInt(data: ByteArray, start: Int, end: Int): Long {
        val len = (end - start).coerceIn(0, 8)
        var acc = 0L
        for (i in 0 until len) {
            acc = (acc shl 8) or (data[start + i].toInt() and 0xFF).toLong()
        }
        return acc
    }
}

// ── BT.601 YUV420 + Alpha Plane Compositor (JVM unit-testable) ────────────────

internal data class DecodedArgbFrame(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
)

internal object YuvAlphaConverter {

    /**
     * Extracts the cropped 8-bit luma (`Y`) plane from a decoded `YUV_420_888` [Image].
     * Used for Matroska WebM `alpha_mode = 1` where the alpha matte is carried in the Y plane
     * of a secondary VP9 stream (`BlockAddID = 1`).
     */
    fun extractLumaPlane(image: Image, expectedWidth: Int, expectedHeight: Int): ByteArray? {
        if (image.format != ImageFormat.YUV_420_888 || image.planes.isEmpty()) return null
        val crop = resolveCropRect(image, expectedWidth, expectedHeight)
        val w = crop.width()
        val h = crop.height()
        if (w <= 0 || h <= 0) return null

        val yPlane = image.planes[0]
        return extractPlaneBytes(
            buffer = yPlane.buffer,
            rowStride = yPlane.rowStride,
            pixelStride = yPlane.pixelStride,
            cropLeft = crop.left,
            cropTop = crop.top,
            width = w,
            height = h,
        )
    }

    fun yuvImageToArgb(
        image: Image,
        alphaPlane: ByteArray?,
        expectedWidth: Int,
        expectedHeight: Int,
    ): DecodedArgbFrame? {
        if (image.format != ImageFormat.YUV_420_888 || image.planes.size < 3) return null
        val crop = resolveCropRect(image, expectedWidth, expectedHeight)
        val w = crop.width()
        val h = crop.height()
        if (w <= 0 || h <= 0) return null

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val pixels = yuv420ToArgb(
            yBuffer = yPlane.buffer,
            yRowStride = yPlane.rowStride,
            yPixelStride = yPlane.pixelStride,
            uBuffer = uPlane.buffer,
            uRowStride = uPlane.rowStride,
            uPixelStride = uPlane.pixelStride,
            vBuffer = vPlane.buffer,
            vRowStride = vPlane.rowStride,
            vPixelStride = vPlane.pixelStride,
            cropLeft = crop.left,
            cropTop = crop.top,
            width = w,
            height = h,
            alphaPlane = alphaPlane,
        )
        return DecodedArgbFrame(width = w, height = h, pixels = pixels)
    }

    internal fun extractPlaneBytes(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        cropLeft: Int,
        cropTop: Int,
        width: Int,
        height: Int,
    ): ByteArray {
        val out = ByteArray(width * height)
        val limit = buffer.limit()
        var outIdx = 0
        for (row in 0 until height) {
            val rowBase = (cropTop + row) * rowStride + cropLeft * pixelStride
            for (col in 0 until width) {
                val idx = rowBase + col * pixelStride
                out[outIdx++] = if (idx in 0 until limit) buffer.get(idx) else 0
            }
        }
        return out
    }

    /**
     * Converts BT.601 limited-range `YUV_420_888` planes + optional 0..255 alpha luma plane
     * into non-premultiplied `ARGB_8888` pixel ints suitable for [Bitmap.createBitmap].
     */
    internal fun yuv420ToArgb(
        yBuffer: ByteBuffer,
        yRowStride: Int,
        yPixelStride: Int,
        uBuffer: ByteBuffer,
        uRowStride: Int,
        uPixelStride: Int,
        vBuffer: ByteBuffer,
        vRowStride: Int,
        vPixelStride: Int,
        cropLeft: Int,
        cropTop: Int,
        width: Int,
        height: Int,
        alphaPlane: ByteArray?,
    ): IntArray {
        val pixels = IntArray(width * height)
        val yLimit = yBuffer.limit()
        val uLimit = uBuffer.limit()
        val vLimit = vBuffer.limit()
        val validAlpha = alphaPlane != null && alphaPlane.size >= width * height

        var outIdx = 0
        for (row in 0 until height) {
            val srcYRow = cropTop + row
            val yRowBase = srcYRow * yRowStride + cropLeft * yPixelStride
            val uvRow = srcYRow ushr 1
            val uRowBase = uvRow * uRowStride
            val vRowBase = uvRow * vRowStride

            for (col in 0 until width) {
                val alpha = if (validAlpha) {
                    val rawA = alphaPlane!![outIdx].toInt() and 0xFF
                    // Suppress VP9 1-2/255 quantization speckle in fully transparent corners.
                    if (rawA <= 2) 0 else rawA
                } else {
                    255
                }

                if (alpha == 0) {
                    pixels[outIdx++] = 0
                    continue
                }

                val srcX = cropLeft + col
                val yIdx = yRowBase + col * yPixelStride
                val uvCol = srcX ushr 1
                val uIdx = uRowBase + uvCol * uPixelStride
                val vIdx = vRowBase + uvCol * vPixelStride

                val yVal = if (yIdx in 0 until yLimit) yBuffer.get(yIdx).toInt() and 0xFF else 16
                val uVal = if (uIdx in 0 until uLimit) uBuffer.get(uIdx).toInt() and 0xFF else 128
                val vVal = if (vIdx in 0 until vLimit) vBuffer.get(vIdx).toInt() and 0xFF else 128

                val c = yVal - 16
                val d = uVal - 128
                val e = vVal - 128

                val r = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
                val g = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
                val b = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)

                pixels[outIdx++] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return pixels
    }

    private fun resolveCropRect(image: Image, expectedWidth: Int, expectedHeight: Int): Rect {
        val crop = image.cropRect
        val validCrop = crop.width() > 0 && crop.height() > 0 &&
            crop.right <= image.width && crop.bottom <= image.height
        val base = if (validCrop) crop else Rect(0, 0, image.width, image.height)
        val clampedW = min(base.width(), expectedWidth.coerceAtLeast(1))
        val clampedH = min(base.height(), expectedHeight.coerceAtLeast(1))
        return Rect(base.left, base.top, base.left + clampedW, base.top + clampedH)
    }
}
