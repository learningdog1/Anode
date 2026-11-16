package com.nodeseek.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * NodeSeek official chicken-leg (加鸡腿) vector icon matching IconPark `chicken-leg` outline geometry.
 */
val NodeSeekDrumstickIcon: ImageVector
    get() {
        _nodeSeekDrumstickIcon?.let { return it }
        return ImageVector.Builder(
            name = "NodeSeekDrumstick",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 48f,
            viewportHeight = 48f,
        ).apply {
            // Drumstick meat outline
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 4f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                drumstickMeatContour()
            }
            // Bone shaft
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 4f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(41f, 41f)
                lineToRelative(-7f, -7f)
            }
            // Bone tip knobs + 3 sesame dots on drumstick
            path(fill = SolidColor(Color.Black)) {
                circle(cx = 42.193f, cy = 40.071f, r = 2.5f)
                circle(cx = 40.072f, cy = 42.192f, r = 2.5f)
                circle(cx = 17f, cy = 18f, r = 2f)
                circle(cx = 12f, cy = 21f, r = 2f)
                circle(cx = 17f, cy = 24f, r = 2f)
            }
        }.build().also { _nodeSeekDrumstickIcon = it }
    }

/**
 * Filled variant of [NodeSeekDrumstickIcon] used when a comment/post has already received a drumstick.
 */
val NodeSeekDrumstickFilledIcon: ImageVector
    get() {
        _nodeSeekDrumstickFilledIcon?.let { return it }
        return ImageVector.Builder(
            name = "NodeSeekDrumstickFilled",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 48f,
            viewportHeight = 48f,
        ).apply {
            // Solid meat interior with 3 negative-space cutout dots
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.EvenOdd,
            ) {
                drumstickMeatContour()
                close()
                circle(cx = 17f, cy = 18f, r = 2.15f)
                circle(cx = 12f, cy = 21f, r = 2.15f)
                circle(cx = 17f, cy = 24f, r = 2.15f)
            }
            // Outer stroke matching outline weight without filling inner cutouts
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 4f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                drumstickMeatContour()
            }
            // Bone shaft
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 4f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(41f, 41f)
                lineToRelative(-7f, -7f)
            }
            // Bone tip knobs
            path(fill = SolidColor(Color.Black)) {
                circle(cx = 42.193f, cy = 40.071f, r = 2.5f)
                circle(cx = 40.072f, cy = 42.192f, r = 2.5f)
            }
        }.build().also { _nodeSeekDrumstickFilledIcon = it }
    }

private var _nodeSeekDrumstickIcon: ImageVector? = null
private var _nodeSeekDrumstickFilledIcon: ImageVector? = null

private fun PathBuilder.drumstickMeatContour() {
    moveTo(33.375f, 33.874f)
    curveToRelative(4.243f, -4.242f, 1.414f, -18.384f, -4.95f, -24.748f)
    curveToRelative(-2.828f, -2.829f, -10.96f, -8.84f, -19.799f, 0f)
    curveToRelative(-8.839f, 8.838f, -2.828f, 16.97f, 0f, 19.799f)
    curveToRelative(6.364f, 6.364f, 20.506f, 9.192f, 24.749f, 4.95f)
}

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx + r, cy)
    arcTo(
        horizontalEllipseRadius = r,
        verticalEllipseRadius = r,
        theta = 0f,
        isMoreThanHalf = false,
        isPositiveArc = true,
        x1 = cx - r,
        y1 = cy,
    )
    arcTo(
        horizontalEllipseRadius = r,
        verticalEllipseRadius = r,
        theta = 0f,
        isMoreThanHalf = false,
        isPositiveArc = true,
        x1 = cx + r,
        y1 = cy,
    )
    close()
}
