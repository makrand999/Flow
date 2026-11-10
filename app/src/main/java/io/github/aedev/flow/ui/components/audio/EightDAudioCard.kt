package io.github.aedev.flow.ui.components.audio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.player.audio.EightDAudioController
import io.github.aedev.flow.player.audio.EightDRoomPreset
import kotlin.math.roundToInt

/**
 * Material 3 control card for toggling and tuning the 8D Spatial Audio effect.
 * Coordinates with [EightDAudioController] to update playback pipelines in real time.
 */
@Composable
fun EightDAudioCard(modifier: Modifier = Modifier) {
    val enabled by EightDAudioController.enabled.collectAsState()
    val speedSec by EightDAudioController.speed.collectAsState()
    val depth by EightDAudioController.depth.collectAsState()
    val roomPreset by EightDAudioController.roomPreset.collectAsState()

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.SurroundSound,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.label_eight_d_audio),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { EightDAudioController.setEnabled(it) },
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.eight_d_audio_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Headphones,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = stringResource(R.string.eight_d_headphones_recommended),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (enabled) {
                Spacer(Modifier.height(16.dp))

                // Room Acoustics Presets
                Text(
                    text = stringResource(R.string.label_eight_d_room_acoustics),
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    EightDRoomPreset.entries.forEachIndexed { index, preset ->
                        val labelRes =
                            when (preset) {
                                EightDRoomPreset.STUDIO -> R.string.eight_d_room_studio
                                EightDRoomPreset.LIVING_ROOM -> R.string.eight_d_room_living_room
                                EightDRoomPreset.CONCERT_HALL -> R.string.eight_d_room_concert_hall
                            }
                        SegmentedButton(
                            selected = roomPreset == preset,
                            onClick = { EightDAudioController.setRoomPreset(preset) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = EightDRoomPreset.entries.size),
                            icon = {},
                            label = {
                                Text(
                                    text = stringResource(labelRes),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Rotation Speed Slider
                Text(
                    text = stringResource(R.string.template_eight_d_speed, speedSec.roundToInt()),
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = speedSec,
                    onValueChange = { EightDAudioController.setSpeed(it) },
                    valueRange = 6f..30f,
                    steps = 24,
                )

                Spacer(Modifier.height(8.dp))

                // Spatial Intensity Slider
                Text(
                    text = stringResource(R.string.template_eight_d_depth, (depth * 100).roundToInt()),
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = depth,
                    onValueChange = { EightDAudioController.setDepth(it) },
                    valueRange = 0.2f..1.0f,
                    steps = 15,
                )
            }
        }
    }
}
