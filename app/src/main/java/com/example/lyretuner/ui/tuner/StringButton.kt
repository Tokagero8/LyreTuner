package com.example.lyretuner.ui.tuner

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lyretuner.tuning.LyreString

@Composable
internal fun StringButton(
    string: LyreString,
    selected: Boolean,
    locked: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TunerTestTags.string(string.midi))
            .selectable(
                selected = selected,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                contentDescription = "String ${string.note} ${string.octave}"
                stateDescription = when {
                    locked -> "Locked"
                    selected -> "Automatically selected"
                    else -> "Not selected"
                }
            },
        shape = RoundedCornerShape(13.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                locked -> MaterialTheme.colorScheme.primary
                selected -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = when {
                locked -> MaterialTheme.colorScheme.onPrimary
                selected -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = string.note,
                fontWeight = FontWeight.Black,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(string.octave.toString(), style = MaterialTheme.typography.labelSmall)
        }
    }
}
