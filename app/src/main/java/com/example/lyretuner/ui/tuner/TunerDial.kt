package com.example.lyretuner.ui.tuner

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lyretuner.presentation.TunerUiState
import com.example.lyretuner.tuning.LyreTuning
import com.example.lyretuner.ui.theme.InTuneGreen
import kotlin.math.roundToInt

@Composable
internal fun TunerDial(state: TunerUiState, modifier: Modifier = Modifier) {
    val reading = state.reading
    val target = state.targetString
    val cents = state.centsFromTarget
    val displayCents = cents?.coerceIn(
        minimumValue = -DISPLAY_CENTS_LIMIT.toDouble(),
        maximumValue = DISPLAY_CENTS_LIMIT.toDouble(),
    ) ?: 0.0
    val accent = if (state.isInTune) InTuneGreen else MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = statusText(state),
            color = accent,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.8.sp,
            modifier = Modifier.testTag(TunerTestTags.STATUS),
        )
        AnimatedContent(targetState = target?.note ?: EM_DASH, label = "note") { note ->
            Text(
                text = note,
                fontSize = 92.sp,
                lineHeight = 100.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.testTag(TunerTestTags.TARGET_NOTE),
            )
        }
        Text(
            text = target?.octave?.toString() ?: " ",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag(TunerTestTags.TARGET_OCTAVE),
        )
        Spacer(Modifier.height(18.dp))
        CentsScale(
            displayCents = displayCents,
            hasReading = reading != null,
            accent = accent,
        )
        CentsLabels(cents = cents, accent = accent)
        Spacer(Modifier.height(12.dp))
        Text(
            text = frequencyText(state),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(TunerTestTags.FREQUENCY),
        )
    }
}

@Composable
private fun CentsScale(displayCents: Double, hasReading: Boolean, accent: Color) {
    val baselineColor = MaterialTheme.colorScheme.outlineVariant
    val tickColor = MaterialTheme.colorScheme.outline
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(ScaleHeight),
    ) {
        val centerX = size.width / 2f
        val scaleY = size.height * SCALE_VERTICAL_POSITION_FRACTION
        val scaleHalfWidth = size.width * SCALE_HALF_WIDTH_FRACTION
        val tickSpacing = scaleHalfWidth / TICKS_PER_SIDE
        drawLine(
            color = baselineColor,
            start = Offset(0f, scaleY),
            end = Offset(size.width, scaleY),
            strokeWidth = BaselineStrokeWidth.toPx(),
            cap = StrokeCap.Round,
        )
        for (step in -TICKS_PER_SIDE..TICKS_PER_SIDE) {
            val x = centerX + tickSpacing * step
            val isCenter = step == 0
            val tickHalfHeight = if (isCenter) CenterTickHalfHeight else TickHalfHeight
            drawLine(
                color = if (isCenter) accent else tickColor,
                start = Offset(x, scaleY - tickHalfHeight.toPx()),
                end = Offset(x, scaleY + tickHalfHeight.toPx()),
                strokeWidth = if (isCenter) {
                    CenterTickStrokeWidth.toPx()
                } else {
                    TickStrokeWidth.toPx()
                },
                cap = StrokeCap.Round,
            )
        }
        if (hasReading) {
            val needleOffset = displayCents.toFloat() / DISPLAY_CENTS_LIMIT * scaleHalfWidth
            drawCircle(
                color = accent,
                radius = NeedleRadius.toPx(),
                center = Offset(centerX + needleOffset, scaleY),
            )
        }
    }
}

@Composable
private fun CentsLabels(cents: Double?, accent: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("\u266D $DISPLAY_CENTS_LIMIT", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = cents?.let { "${if (it >= 0) "+" else ""}${it.roundToInt()} cents" }
                ?: "$EM_DASH cents",
            color = accent,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag(TunerTestTags.CENTS),
        )
        Text("$DISPLAY_CENTS_LIMIT \u266F", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun statusText(state: TunerUiState): String {
    val centsFromTarget = state.centsFromTarget
    return when {
        !state.isListening -> "READY WHEN YOU ARE"
        state.reading == null || centsFromTarget == null -> "PLAY ONE STRING"
        state.isInTune -> "IN TUNE"
        centsFromTarget < 0 -> "TUNE UP"
        else -> "TUNE DOWN"
    }
}

private fun frequencyText(state: TunerUiState): String {
    val target = state.targetString ?: return tuningRangeText()
    val frequency = state.reading?.frequencyHz
        ?: return "Target %.1f Hz".format(target.frequencyHz)
    return "%.1f Hz  \u00B7  target %.1f Hz".format(frequency, target.frequencyHz)
}

private fun tuningRangeText(): String {
    val lowestString = LyreTuning.strings.firstOrNull() ?: return EM_DASH
    val highestString = LyreTuning.strings.lastOrNull() ?: return EM_DASH
    return "${lowestString.name} $EM_DASH ${highestString.name}"
}

private const val DISPLAY_CENTS_LIMIT = 50
private const val TICKS_PER_SIDE = 5
private const val SCALE_VERTICAL_POSITION_FRACTION = 0.62f
private const val SCALE_HALF_WIDTH_FRACTION = 0.45f
private const val EM_DASH = "\u2014"

private val ScaleHeight = 58.dp
private val BaselineStrokeWidth = 3.dp
private val TickHalfHeight = 7.dp
private val CenterTickHalfHeight = 15.dp
private val TickStrokeWidth = 2.dp
private val CenterTickStrokeWidth = 3.dp
private val NeedleRadius = 8.dp
