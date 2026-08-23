package com.example.lyretuner.ui.tuner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lyretuner.analysis.PitchReading
import com.example.lyretuner.presentation.TunerError
import com.example.lyretuner.presentation.TunerUiState
import com.example.lyretuner.tuning.LyreString
import com.example.lyretuner.tuning.LyreTuning
import com.example.lyretuner.ui.theme.InTuneGreen
import com.example.lyretuner.ui.theme.LyreTunerTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunerScreen(
    state: TunerUiState,
    onListeningClick: () -> Unit,
    onStringClick: (LyreString) -> Unit,
    onAutoClick: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.testTag(TunerTestTags.SCREEN),
        containerColor = MaterialTheme.colorScheme.background,
    ) { insets ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets),
        ) {
            val compactLayout = maxWidth < CompactLayoutBreakpoint
            val horizontalPadding = if (compactLayout) {
                CompactHorizontalPadding
            } else {
                DefaultHorizontalPadding
            }
            val gridSpacing = if (compactLayout) CompactGridSpacing else DefaultGridSpacing

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TunerHeader(isListening = state.isListening)
                TunerDial(
                    state = state,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.92f),
                )
                TunerErrorMessage(error = state.error)
                ListeningButton(
                    isListening = state.isListening,
                    onClick = onListeningClick,
                )
                StringGridHeader(
                    automaticSelection = state.lockedString == null,
                    onAutoClick = onAutoClick,
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(STRING_GRID_COLUMNS),
                    contentPadding = PaddingValues(bottom = GridBottomPadding),
                    horizontalArrangement = Arrangement.spacedBy(gridSpacing),
                    verticalArrangement = Arrangement.spacedBy(gridSpacing),
                    modifier = Modifier
                        .weight(1f)
                        .selectableGroup()
                        .testTag(TunerTestTags.STRING_GRID),
                ) {
                    items(LyreTuning.strings, key = { it.midi }) { string ->
                        StringButton(
                            string = string,
                            selected = string == state.targetString,
                            locked = string == state.lockedString,
                            onClick = { onStringClick(string) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TunerHeader(isListening: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "LYRE",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            letterSpacing = 3.sp,
        )
        Text(
            text = " / ${LyreTuning.strings.size} STRING TUNER",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(9.dp)
                .background(
                    if (isListening) InTuneGreen else MaterialTheme.colorScheme.outline,
                    CircleShape,
                ),
        )
    }
}

@Composable
private fun TunerErrorMessage(error: TunerError?) {
    val message = when (error) {
        TunerError.MICROPHONE_PERMISSION_DENIED ->
            "Microphone access is needed to hear your lyre."
        TunerError.ANALYZER_START_FAILED ->
            "Could not start microphone analysis. Please try again."
        null -> return
    }
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .padding(bottom = 8.dp)
            .testTag(TunerTestTags.PERMISSION_MESSAGE),
    )
}

@Composable
private fun ListeningButton(isListening: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .testTag(TunerTestTags.LISTENING_BUTTON),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isListening) {
                MaterialTheme.colorScheme.surfaceVariant
            } else MaterialTheme.colorScheme.primary,
            contentColor = if (isListening) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text(if (isListening) "\u25A0" else "\u25CF", fontSize = 12.sp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (isListening) "Stop listening" else "Start tuning",
            fontWeight = FontWeight.Bold,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StringGridHeader(automaticSelection: Boolean, onAutoClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("STRINGS", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        FilterChip(
            modifier = Modifier.testTag(TunerTestTags.AUTO_CHIP),
            selected = automaticSelection,
            onClick = onAutoClick,
            label = { Text("AUTO") },
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun TunerScreenPreview() {
    LyreTunerTheme(dynamicColor = false) {
        Surface {
            TunerScreen(
                state = TunerUiState(
                    reading = PitchReading(440.8, 0.95),
                    isListening = true,
                    automaticString = LyreTuning.strings.single { it.name == "A4" },
                ),
                onListeningClick = {},
                onStringClick = {},
                onAutoClick = {},
            )
        }
    }
}

private const val STRING_GRID_COLUMNS = 6

private val CompactLayoutBreakpoint = 400.dp
private val CompactHorizontalPadding = 8.dp
private val DefaultHorizontalPadding = 20.dp
private val CompactGridSpacing = 3.dp
private val DefaultGridSpacing = 7.dp
private val GridBottomPadding = 20.dp
