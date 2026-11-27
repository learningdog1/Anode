package com.nodeseek.app.domain.image

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Normalized `[0f, 1f]` point in image coordinate space (independent of screen/preview scale).
 */
data class MosaicPoint(
    val xNorm: Float,
    val yNorm: Float,
)

/**
 * Normalized `[0f, 1f]` rectangular region in image coordinate space.
 */
data class MosaicRect(
    val leftNorm: Float,
    val topNorm: Float,
    val rightNorm: Float,
    val bottomNorm: Float,
)

/**
 * Immutable edit operation recorded in undo history so Undo / Reset always recompute
 * losslessly from original decoded pixels.
 */
sealed interface MosaicEdit {
    val blockSizeNorm: Float

    data class BrushStroke(
        val points: List<MosaicPoint>,
        val radiusNorm: Float,
        override val blockSizeNorm: Float = DEFAULT_BLOCK_SIZE_NORM,
    ) : MosaicEdit

    data class BoxRegion(
        val rect: MosaicRect,
        override val blockSizeNorm: Float = DEFAULT_BLOCK_SIZE_NORM,
    ) : MosaicEdit

    companion object {
        const val DEFAULT_BLOCK_SIZE_NORM = 0.024f
    }
}

/**
 * Pure-Kotlin, grid-aligned ARGB pixelation processor (zero `android.*` dependencies).
 *
 * All mosaic blocks snap to an integer grid `(col * blockSizePx, row * blockSizePx)` so
 * overlapping strokes and rectangles remain crisp and uniform without smearing artifacts.
 */
object BitmapMosaicProcessor {

    fun applyMosaicEdits(
        sourcePixels: IntArray,
        width: Int,
        height: Int,
        edits: List<MosaicEdit>,
    ): IntArray {
        val result = sourcePixels.copyOf()
        if (width <= 0 || height <= 0 || result.size.toLong() < width.toLong() * height.toLong() || edits.isEmpty()) {
            return result
        }
        if (width <= 1 && height <= 1) {
            return result
        }
        val minDim = min(width, height).toFloat().coerceAtLeast(1f)
        val maxDim = max(width, height).coerceAtLeast(2)
        val maxRadiusPx = hypot(width.toDouble(), height.toDouble()).toFloat().coerceAtLeast(1f)
        for (edit in edits) {
            val safeBlockSizeNorm = if (edit.blockSizeNorm.isFinite()) {
                edit.blockSizeNorm
            } else {
                MosaicEdit.DEFAULT_BLOCK_SIZE_NORM
            }
            val blockSizePx = (safeBlockSizeNorm * minDim).roundToInt().coerceIn(2, maxDim)
            when (edit) {
                is MosaicEdit.BrushStroke -> {
                    if (!edit.radiusNorm.isFinite()) continue
                    val finitePoints = edit.points.filter { it.xNorm.isFinite() && it.yNorm.isFinite() }
                    if (finitePoints.isEmpty()) continue
                    val radiusPx = (edit.radiusNorm * minDim).coerceIn(1f, maxRadiusPx)
                    val pointsPx = finitePoints.map { pt ->
                        pt.xNorm.coerceIn(0f, 1f) * (width - 1).coerceAtLeast(0) to
                            pt.yNorm.coerceIn(0f, 1f) * (height - 1).coerceAtLeast(0)
                    }
                    applyMosaicStrokePx(
                        pixels = result,
                        width = width,
                        height = height,
                        pointsPx = pointsPx,
                        radiusPx = radiusPx,
                        blockSizePx = blockSizePx,
                    )
                }

                is MosaicEdit.BoxRegion -> {
                    val rect = edit.rect
                    if (!rect.leftNorm.isFinite() ||
                        !rect.topNorm.isFinite() ||
                        !rect.rightNorm.isFinite() ||
                        !rect.bottomNorm.isFinite()
                    ) {
                        continue
                    }
                    val left = (min(rect.leftNorm, rect.rightNorm).coerceIn(0f, 1f) * width)
                        .roundToInt()
                    val right = (max(rect.leftNorm, rect.rightNorm).coerceIn(0f, 1f) * width)
                        .roundToInt()
                    val top = (min(rect.topNorm, rect.bottomNorm).coerceIn(0f, 1f) * height)
                        .roundToInt()
                    val bottom = (max(rect.topNorm, rect.bottomNorm).coerceIn(0f, 1f) * height)
                        .roundToInt()
                    applyMosaicRectPx(
                        pixels = result,
                        width = width,
                        height = height,
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                        blockSizePx = blockSizePx,
                    )
                }
            }
        }
        return result
    }

    fun applyMosaicRectPx(
        pixels: IntArray,
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        blockSizePx: Int,
    ) {
        if (width <= 0 || height <= 0 || pixels.size.toLong() < width.toLong() * height.toLong()) return
        if (width <= 1 && height <= 1) return
        val l = min(left, right).coerceIn(0, width)
        val r = max(left, right).coerceIn(0, width)
        val t = min(top, bottom).coerceIn(0, height)
        val b = max(top, bottom).coerceIn(0, height)
        if (l >= r || t >= b) return

        val maxDim = max(width, height).coerceAtLeast(2)
        val block = blockSizePx.coerceIn(2, maxDim)
        val cols = (width + block - 1) / block
        val rows = (height + block - 1) / block
        val mask = BooleanArray(cols * rows)

        val colStart = (l / block).coerceIn(0, cols - 1)
        val colEnd = ((r - 1) / block).coerceIn(colStart, cols - 1)
        val rowStart = (t / block).coerceIn(0, rows - 1)
        val rowEnd = ((b - 1) / block).coerceIn(rowStart, rows - 1)

        for (row in rowStart..rowEnd) {
            val rowOffset = row * cols
            for (col in colStart..colEnd) {
                mask[rowOffset + col] = true
            }
        }

        pixelateMarkedBlocks(
            pixels = pixels,
            width = width,
            height = height,
            blockSizePx = block,
            cols = cols,
            rows = rows,
            mask = mask,
        )
    }

    fun applyMosaicStrokePx(
        pixels: IntArray,
        width: Int,
        height: Int,
        pointsPx: List<Pair<Float, Float>>,
        radiusPx: Float,
        blockSizePx: Int,
    ) {
        if (width <= 0 || height <= 0 || pixels.size.toLong() < width.toLong() * height.toLong() || pointsPx.isEmpty()) return
        if (width <= 1 && height <= 1) return
        if (!radiusPx.isFinite()) return
        val finitePoints = pointsPx.filter { it.first.isFinite() && it.second.isFinite() }
        if (finitePoints.isEmpty()) return

        val maxDim = max(width, height).coerceAtLeast(2)
        val block = blockSizePx.coerceIn(2, maxDim)
        val maxRadiusPx = hypot(width.toDouble(), height.toDouble()).toFloat().coerceAtLeast(1f)
        val safeRadius = radiusPx.coerceIn(1f, maxRadiusPx)
        val radiusSq = safeRadius * safeRadius
        val cols = (width + block - 1) / block
        val rows = (height + block - 1) / block
        val mask = BooleanArray(cols * rows)

        fun markCircleAt(rawX: Float, rawY: Float) {
            val cx = rawX.coerceIn(0f, (width - 1).toFloat())
            val cy = rawY.coerceIn(0f, (height - 1).toFloat())
            val minPx = floor(cx - safeRadius).toInt().coerceIn(0, width - 1)
            val maxPx = ceil(cx + safeRadius).toInt().coerceIn(0, width - 1)
            val minPy = floor(cy - safeRadius).toInt().coerceIn(0, height - 1)
            val maxPy = ceil(cy + safeRadius).toInt().coerceIn(0, height - 1)

            val colStart = (minPx / block).coerceIn(0, cols - 1)
            val colEnd = (maxPx / block).coerceIn(colStart, cols - 1)
            val rowStart = (minPy / block).coerceIn(0, rows - 1)
            val rowEnd = (maxPy / block).coerceIn(rowStart, rows - 1)

            for (row in rowStart..rowEnd) {
                val by = row * block
                val byEnd = min(by + block, height) - 1
                val nearestY = cy.coerceIn(by.toFloat(), byEnd.toFloat())
                val dy = cy - nearestY
                val rowOffset = row * cols
                for (col in colStart..colEnd) {
                    if (mask[rowOffset + col]) continue
                    val bx = col * block
                    val bxEnd = min(bx + block, width) - 1
                    val nearestX = cx.coerceIn(bx.toFloat(), bxEnd.toFloat())
                    val dx = cx - nearestX
                    if (dx * dx + dy * dy <= radiusSq) {
                        mask[rowOffset + col] = true
                    }
                }
            }
        }

        val step = max(1f, safeRadius * 0.35f)
        markCircleAt(finitePoints.first().first, finitePoints.first().second)
        for (i in 1 until finitePoints.size) {
            val (x0, y0) = finitePoints[i - 1]
            val (x1, y1) = finitePoints[i]
            val dist = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
            if (!dist.isFinite()) continue
            val steps = ceil((dist / step).toDouble()).toInt().coerceIn(1, 100_000)
            for (s in 1..steps) {
                val t = s.toFloat() / steps.toFloat()
                markCircleAt(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
            }
        }

        pixelateMarkedBlocks(
            pixels = pixels,
            width = width,
            height = height,
            blockSizePx = block,
            cols = cols,
            rows = rows,
            mask = mask,
        )
    }

    private fun pixelateMarkedBlocks(
        pixels: IntArray,
        width: Int,
        height: Int,
        blockSizePx: Int,
        cols: Int,
        rows: Int,
        mask: BooleanArray,
    ) {
        for (row in 0 until rows) {
            val by = row * blockSizePx
            val byEnd = min(by + blockSizePx, height)
            val rowOffset = row * cols
            for (col in 0 until cols) {
                if (!mask[rowOffset + col]) continue
                val bx = col * blockSizePx
                val bxEnd = min(bx + blockSizePx, width)

                var sumA = 0L
                var sumR = 0L
                var sumG = 0L
                var sumB = 0L
                var count = 0L

                for (y in by until byEnd) {
                    val base = y * width
                    for (x in bx until bxEnd) {
                        val argb = pixels[base + x]
                        sumA += (argb ushr 24) and 0xFF
                        sumR += (argb ushr 16) and 0xFF
                        sumG += (argb ushr 8) and 0xFF
                        sumB += argb and 0xFF
                        count++
                    }
                }
                if (count <= 0L) continue

                val avgA = (sumA / count).toInt() and 0xFF
                val avgR = (sumR / count).toInt() and 0xFF
                val avgG = (sumG / count).toInt() and 0xFF
                val avgB = (sumB / count).toInt() and 0xFF
                val fillColor = if (avgA == 0) {
                    0
                } else {
                    (avgA shl 24) or (avgR shl 16) or (avgG shl 8) or avgB
                }

                for (y in by until byEnd) {
                    val base = y * width
                    for (x in bx until bxEnd) {
                        pixels[base + x] = fillColor
                    }
                }
            }
        }
    }
}
