package com.batteryalert.guard.presentation.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batteryalert.guard.domain.usecase.CellHealth
import com.batteryalert.guard.presentation.theme.GuardColors
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Canvas-drawn instrumentation.
 *
 * These components draw geometry only — they receive finished numbers and never evaluate
 * a threshold. The one colour that carries meaning is passed in by the caller, which got
 * it from the domain layer's [com.batteryalert.guard.domain.model.AlertLevel].
 */

// --- Battery arc gauge --------------------------------------------------------------

private const val ARC_START_ANGLE = 135f
private const val ARC_SWEEP_ANGLE = 270f
private const val ARC_TICK_DIVISIONS = 4

/**
 * A 270-degree radial gauge.
 *
 * The number in the middle is the reading; the ring around it is the same reading made
 * glanceable from arm's length on a controller in daylight, which is how this device is
 * actually used.
 *
 * @param tickColor drawn across the ring to break it into quadrants. Defaults to the card
 *   colour, which is what the gauge normally sits on.
 */
@Composable
fun BatteryArcGauge(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 16.dp,
    tickColor: Color = GuardColors.Card,
    content: @Composable () -> Unit,
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "battery-arc",
    )

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val ringSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(stroke / 2f, stroke / 2f)
            val radius = min(size.width, size.height) / 2f - stroke / 2f
            val centre = Offset(size.width / 2f, size.height / 2f)

            drawArc(
                color = GuardColors.Track,
                startAngle = ARC_START_ANGLE,
                sweepAngle = ARC_SWEEP_ANGLE,
                useCenter = false,
                topLeft = topLeft,
                size = ringSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )

            if (animated > 0.001f) {
                val sweep = ARC_SWEEP_ANGLE * animated

                // Soft halo behind the solid arc, so the reading still registers in glare.
                drawArc(
                    color = color.copy(alpha = 0.20f),
                    startAngle = ARC_START_ANGLE,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = ringSize,
                    style = Stroke(width = stroke * 2.3f, cap = StrokeCap.Round),
                )
                drawArc(
                    color = color,
                    startAngle = ARC_START_ANGLE,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = ringSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                drawCircle(
                    color = color,
                    radius = stroke * 0.32f,
                    center = centre + polarOffset(ARC_START_ANGLE + sweep, radius),
                )
            }

            drawQuadrantTicks(centre, radius, stroke, tickColor)
        }
        content()
    }
}

private fun DrawScope.drawQuadrantTicks(
    centre: Offset,
    radius: Float,
    stroke: Float,
    tickColor: Color,
) {
    for (division in 0..ARC_TICK_DIVISIONS) {
        val angle = ARC_START_ANGLE + ARC_SWEEP_ANGLE * division / ARC_TICK_DIVISIONS
        val direction = polarOffset(angle, 1f)
        drawLine(
            color = tickColor,
            start = centre + direction * (radius - stroke * 0.95f),
            end = centre + direction * (radius + stroke * 0.95f),
            strokeWidth = stroke * 0.16f,
            cap = StrokeCap.Round,
        )
    }
}

private fun polarOffset(angleDegrees: Float, radius: Float): Offset {
    val radians = Math.toRadians(angleDegrees.toDouble())
    return Offset((cos(radians) * radius).toFloat(), (sin(radians) * radius).toFloat())
}

// --- Cell balance chart -------------------------------------------------------------

/**
 * Per-cell voltages plotted as their offset above the weakest cell in the pack.
 *
 * Plotting offset-from-weakest rather than offset-from-average is deliberate: it makes
 * the vertical distance between the tallest and shortest bar *exactly* the ΔV the
 * requirements threshold, so the dashed limit line answers "is this pack out of balance?"
 * by inspection rather than by arithmetic. Bars crossing the line is the same statement
 * as `ΔV > limit`.
 */
@Composable
fun CellBalanceChart(
    cellVoltages: List<Double>,
    limitVolts: Double,
    modifier: Modifier = Modifier,
    barColor: Color = GuardColors.Accent,
    limitColor: Color = GuardColors.TextMuted,
) {
    val cells = remember(cellVoltages) {
        cellVoltages.filter { it.isFinite() && it > CellHealth.MIN_USABLE_CELL_VOLTS }
    }

    if (cells.size < 2) {
        Text(
            text = "Not enough cell readings to show a balance chart.",
            color = GuardColors.TextMuted,
            fontSize = 12.sp,
            modifier = modifier,
        )
        return
    }

    val offsets = cells.map { (it - cells.min()).toFloat() }
    val tallest = offsets.max()
    val limit = limitVolts.toFloat()
    val fullScale = maxOf(limit * 1.6f, tallest * 1.25f, 0.01f)

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val limitY = size.height * (1f - (limit / fullScale).coerceIn(0f, 1f))

            drawLine(
                color = limitColor.copy(alpha = 0.55f),
                start = Offset(0f, limitY),
                end = Offset(size.width, limitY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)),
            )

            val slot = size.width / offsets.size
            val barWidth = slot * 0.46f
            val corner = CornerRadius(barWidth / 2f, barWidth / 2f)

            offsets.forEachIndexed { index, offsetVolts ->
                val barHeight = (offsetVolts / fullScale).coerceIn(0f, 1f) * size.height
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(
                        x = slot * index + (slot - barWidth) / 2f,
                        y = size.height - barHeight,
                    ),
                    // The weakest cell sits at zero height; a hairline keeps it visible as
                    // the reference the others are measured against.
                    size = Size(barWidth, barHeight.coerceAtLeast(1.5.dp.toPx())),
                    cornerRadius = corner,
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(Modifier.fillMaxWidth()) {
            cells.indices.forEach { index ->
                Text(
                    text = "${index + 1}",
                    color = GuardColors.TextMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// --- Threshold bar ------------------------------------------------------------------

/**
 * A level bar with a threshold marked on it.
 *
 * The same idiom as the cell-balance chart's limit line: the fill is where the aircraft
 * actually is, the upright marker is the line it must stay above. When the fill stops
 * short of the marker, the situation is legible without reading a single number.
 */
@Composable
fun ThresholdBar(
    fraction: Float,
    thresholdFraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    thresholdColor: Color = GuardColors.TextSecondary,
    height: Dp = 12.dp,
) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height),
    ) {
        val corner = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = GuardColors.Track, cornerRadius = corner)

        val filled = fraction.coerceIn(0f, 1f)
        if (filled > 0f) {
            drawRoundRect(
                color = color,
                size = Size(size.width * filled, size.height),
                cornerRadius = corner,
            )
        }

        // Kept a hair inside each edge so the marker stays visible at 0% and 100%.
        val markerX = (size.width * thresholdFraction.coerceIn(0f, 1f))
            .coerceIn(1f, size.width - 1f)
        drawLine(
            color = thresholdColor,
            start = Offset(markerX, 0f),
            end = Offset(markerX, size.height),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

// --- Rolling sparkline --------------------------------------------------------------

/**
 * A rolling line chart of recent samples.
 *
 * The y-range is quantised to at least [minimumSpan] so a nearly-flat signal does not
 * get amplified into a dramatic-looking squiggle. A chart that manufactures drama out of
 * a steady voltage is worse than no chart.
 *
 * @param secondaryValues an optional second series on the same scale, drawn dashed.
 */
@Composable
fun Sparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    secondaryValues: List<Float> = emptyList(),
    secondaryColor: Color = GuardColors.TextMuted,
    minimumSpan: Float = 1f,
) {
    val all = if (secondaryValues.isEmpty()) values else values + secondaryValues

    if (all.size < 2) {
        Canvas(modifier) {
            drawLine(
                color = GuardColors.Track,
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 1.dp.toPx(),
            )
        }
        return
    }

    val low = all.min()
    val high = all.max()
    val span = (high - low).coerceAtLeast(minimumSpan)
    val centre = (high + low) / 2f
    val halfRange = span / 2f * 1.08f
    val floor = centre - halfRange
    val range = halfRange * 2f

    Canvas(modifier) {
        val width = size.width
        val height = size.height

        val yOf: (Float) -> Float = { value ->
            height * (1f - ((value - floor) / range).coerceIn(0f, 1f))
        }
        val xOf: (Int, Int) -> Float = { index, count ->
            if (count <= 1) 0f else width * index / (count - 1)
        }

        val linePath: (List<Float>) -> Path = { series ->
            Path().apply {
                series.forEachIndexed { index, value ->
                    val x = xOf(index, series.size)
                    val y = yOf(value)
                    if (index == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
        }

        if (values.size >= 2) {
            val area = Path().apply {
                addPath(linePath(values))
                lineTo(width, height)
                lineTo(0f, height)
                close()
            }
            drawPath(
                path = area,
                brush = Brush.verticalGradient(
                    colors = listOf(color.copy(alpha = 0.28f), Color.Transparent),
                    startY = 0f,
                    endY = height,
                ),
            )
            drawPath(
                path = linePath(values),
                color = color,
                style = Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
            drawCircle(
                color = color,
                radius = 2.5.dp.toPx(),
                center = Offset(width, yOf(values.last())),
            )
        }

        if (secondaryValues.size >= 2) {
            drawPath(
                path = linePath(secondaryValues),
                color = secondaryColor,
                style = Stroke(
                    width = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f)),
                ),
            )
            drawCircle(
                color = secondaryColor,
                radius = 2.dp.toPx(),
                center = Offset(width, yOf(secondaryValues.last())),
            )
        }
    }
}
