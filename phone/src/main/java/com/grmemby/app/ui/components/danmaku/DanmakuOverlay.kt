package com.grmemby.app.ui.components.danmaku

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable
internal fun DanmakuOverlay(
    comments: List<DanmakuComment>,
    currentPositionMs: Long,
    isPlaying: Boolean,
    enabled: Boolean,
    lineCount: Int,
    speedPercent: Int,
    opacityPercent: Int,
    fontSizeSp: Int,
    bold: Boolean,
    modifier: Modifier = Modifier
) {
    if (!enabled || comments.isEmpty() || lineCount <= 0) return

    val density = LocalDensity.current
    val densityValue = density.density
    val safeLineCount = lineCount.coerceIn(1, 10)
    val safeSpeed = speedPercent.coerceIn(50, 200)
    val safeFontSizeSp = fontSizeSp.coerceIn(12, 36)
    val alpha = (opacityPercent.coerceIn(20, 100) * 255 / 100).coerceIn(51, 255)
    val scrollDurationMs = (9000L * 100 / safeSpeed).coerceIn(4200L, 18000L)
    val fixedDurationMs = 4500L
    val sortedComments = remember(comments) { comments.sortedBy { it.timeMs } }

    var framePositionMs by remember { mutableLongStateOf(currentPositionMs) }
    LaunchedEffect(currentPositionMs, isPlaying, enabled, comments) {
        framePositionMs = currentPositionMs
        if (!enabled || !isPlaying || comments.isEmpty()) return@LaunchedEffect
        val anchorPositionMs = currentPositionMs
        val anchorFrameNs = withFrameNanos { it }
        while (true) {
            withFrameNanos { frameNs ->
                val elapsedMs = ((frameNs - anchorFrameNs) / 1_000_000L).coerceAtLeast(0L)
                framePositionMs = anchorPositionMs + elapsedMs
            }
        }
    }

    val paint = remember(safeFontSizeSp, bold, densityValue) {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
            textSize = safeFontSizeSp * densityValue
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(3.5f * densityValue, 0f, 0f, 0xD0000000.toInt())
        }
    }

    val measured = remember(sortedComments, safeFontSizeSp, bold, densityValue) {
        FloatArray(sortedComments.size) { index ->
            paint.measureText(sortedComments[index].text).coerceAtLeast(40f * densityValue)
        }
    }

    val activeRange = remember(sortedComments, currentPositionMs, scrollDurationMs) {
        sortedComments.activeIndexRange(
            currentPositionMs = currentPositionMs,
            // Keep enough already-started comments in the lane layout pass even after
            // they have moved off the left edge. Otherwise when an old comment drops
            // out of the active window, later comments can be re-assigned to a new
            // lane mid-flight and visually jump/flicker.
            beforeMs = scrollDurationMs * 2L + fixedDurationMs,
            afterMs = 80L,
            limit = 360
        )
    }
    if (activeRange.first >= activeRange.second) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val drawPositionMs = framePositionMs
        val nativeCanvas = drawContext.canvas.nativeCanvas
        val horizontalPadding = with(density) { 18.dp.toPx() }
        val topPadding = with(density) { 14.dp.toPx() }
        val bottomPadding = with(density) { 82.dp.toPx() }
        val horizontalGap = with(density) { 26.dp.toPx() }
        val drawableWidth = (size.width - horizontalPadding * 2f).coerceAtLeast(1f)
        val fontMetrics = paint.fontMetrics
        val naturalLineHeight = (fontMetrics.descent - fontMetrics.ascent).coerceAtLeast(22f * densityValue)

        // Default top area: hard max line count, compact lanes near the top.
        // Do not expand into the full screen; subtitle/control space remains untouched.
        val laneStride = (naturalLineHeight * 1.26f).coerceAtLeast(24f * densityValue)
        val availableTopHeight = (size.height - topPadding - bottomPadding).coerceAtLeast(laneStride)
        val effectiveLaneCount = safeLineCount.coerceAtMost((availableTopHeight / laneStride).toInt().coerceAtLeast(1))
        val scrollBaselines = FloatArray(effectiveLaneCount) { lane ->
            topPadding + lane * laneStride - fontMetrics.ascent
        }
        val fixedTopBaselines = scrollBaselines
        val fixedBottomStart = (size.height - bottomPadding - laneStride * effectiveLaneCount).coerceAtLeast(topPadding)
        val fixedBottomBaselines = FloatArray(effectiveLaneCount) { lane ->
            fixedBottomStart + lane * laneStride - fontMetrics.ascent
        }

        drawScrollingDanmaku(
            comments = sortedComments,
            widths = measured,
            startIndex = activeRange.first,
            endIndex = activeRange.second,
            currentPositionMs = drawPositionMs,
            durationMs = scrollDurationMs,
            laneBaselines = scrollBaselines,
            paint = paint,
            alpha = alpha,
            left = horizontalPadding,
            width = drawableWidth,
            horizontalGap = horizontalGap,
            canvas = nativeCanvas
        )

        drawFixedDanmaku(
            comments = sortedComments,
            widths = measured,
            startIndex = activeRange.first,
            endIndex = activeRange.second,
            currentPositionMs = drawPositionMs,
            durationMs = fixedDurationMs,
            topBaselines = fixedTopBaselines,
            bottomBaselines = fixedBottomBaselines,
            left = horizontalPadding,
            width = drawableWidth,
            horizontalGap = horizontalGap,
            paint = paint,
            alpha = alpha,
            canvas = nativeCanvas
        )
    }
}

private fun drawScrollingDanmaku(
    comments: List<DanmakuComment>,
    widths: FloatArray,
    startIndex: Int,
    endIndex: Int,
    currentPositionMs: Long,
    durationMs: Long,
    laneBaselines: FloatArray,
    paint: Paint,
    alpha: Int,
    left: Float,
    width: Float,
    horizontalGap: Float,
    canvas: android.graphics.Canvas
) {
    val laneLastTime = LongArray(laneBaselines.size) { Long.MIN_VALUE }
    val laneLastWidth = FloatArray(laneBaselines.size)

    var index = startIndex
    while (index < endIndex) {
        val comment = comments[index]
        if (comment.mode == 4 || comment.mode == 5) {
            index++
            continue
        }
        val elapsedMs = currentPositionMs - comment.timeMs
        val textWidth = widths.getOrNull(index) ?: paint.measureText(comment.text)
        val lane = chooseScrollLane(
            laneLastTime = laneLastTime,
            laneLastWidth = laneLastWidth,
            textWidth = textWidth,
            timeMs = comment.timeMs,
            durationMs = durationMs,
            width = width,
            horizontalGap = horizontalGap
        )
        if (lane >= 0) {
            laneLastTime[lane] = comment.timeMs
            laneLastWidth[lane] = textWidth

            if (elapsedMs in 0L..durationMs) {
                val progress = elapsedMs.toFloat() / durationMs.toFloat()
                val travel = width + textWidth
                val x = left + width - travel * progress
                if (x <= left + width && x + textWidth >= left) {
                    drawComment(canvas, paint, comment, alpha, x, laneBaselines[lane])
                }
            }
        }
        index++
    }
}

private fun drawFixedDanmaku(
    comments: List<DanmakuComment>,
    widths: FloatArray,
    startIndex: Int,
    endIndex: Int,
    currentPositionMs: Long,
    durationMs: Long,
    topBaselines: FloatArray,
    bottomBaselines: FloatArray,
    left: Float,
    width: Float,
    horizontalGap: Float,
    paint: Paint,
    alpha: Int,
    canvas: android.graphics.Canvas
) {
    val topUntil = LongArray(topBaselines.size) { Long.MIN_VALUE }
    val bottomUntil = LongArray(bottomBaselines.size) { Long.MIN_VALUE }
    var index = startIndex
    while (index < endIndex) {
        val comment = comments[index]
        if (comment.mode != 4 && comment.mode != 5) {
            index++
            continue
        }
        val elapsed = currentPositionMs - comment.timeMs
        val textWidth = widths.getOrNull(index) ?: 0f
        val baselines = if (comment.mode == 4) bottomBaselines else topBaselines
        val laneUntil = if (comment.mode == 4) bottomUntil else topUntil
        val lane = firstAvailableLane(laneUntil, comment.timeMs)
        if (lane >= 0) {
            laneUntil[lane] = comment.timeMs + durationMs
            if (elapsed in 0L..durationMs) {
                val x = (left + (width - textWidth) / 2f).coerceAtLeast(left + horizontalGap)
                drawComment(canvas, paint, comment, alpha, x, baselines[lane])
            }
        }
        index++
    }
}

private fun chooseScrollLane(
    laneLastTime: LongArray,
    laneLastWidth: FloatArray,
    textWidth: Float,
    timeMs: Long,
    durationMs: Long,
    width: Float,
    horizontalGap: Float
): Int {
    var lane = 0
    while (lane < laneLastTime.size) {
        val lastTime = laneLastTime[lane]
        if (lastTime == Long.MIN_VALUE) return lane
        val delta = (timeMs - lastTime).coerceAtLeast(0L)
        val lastWidth = laneLastWidth[lane]
        val previousClearMs = (durationMs * (lastWidth + horizontalGap) / (width + lastWidth)).toLong()
        val catchUpSafeMs = if (textWidth > lastWidth) {
            (durationMs * (textWidth + horizontalGap) / (width + textWidth)).toLong()
        } else {
            previousClearMs
        }
        val requiredDelta = maxOf(previousClearMs, catchUpSafeMs) + 160L
        if (delta >= requiredDelta) return lane
        lane++
    }
    // preventOverlap=true: when every lane would collide, skip this comment.
    return -1
}

private fun firstAvailableLane(laneUntil: LongArray, currentPositionMs: Long): Int {
    var index = 0
    while (index < laneUntil.size) {
        if (currentPositionMs >= laneUntil[index]) return index
        index++
    }
    return -1
}

private fun drawComment(
    canvas: android.graphics.Canvas,
    paint: Paint,
    comment: DanmakuComment,
    alpha: Int,
    x: Float,
    baseline: Float
) {
    val baseColor = comment.color or 0xFF000000.toInt()
    paint.color = (baseColor and 0x00FFFFFF) or (alpha shl 24)
    canvas.drawText(comment.text, x, baseline, paint)
}

private fun List<DanmakuComment>.activeIndexRange(
    currentPositionMs: Long,
    beforeMs: Long,
    afterMs: Long,
    limit: Int
): IntRangePair {
    if (isEmpty()) return IntRangePair(0, 0)
    val fromTime = currentPositionMs - beforeMs
    val toTime = currentPositionMs + afterMs
    val start = lowerBoundByTime(fromTime)
    var end = start
    while (end < size && end - start < limit && this[end].timeMs <= toTime) {
        end++
    }
    return IntRangePair(start, end)
}

private fun List<DanmakuComment>.lowerBoundByTime(targetMs: Long): Int {
    var low = 0
    var high = size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (this[mid].timeMs < targetMs) low = mid + 1 else high = mid
    }
    return low
}

private data class IntRangePair(
    val first: Int,
    val second: Int
)
