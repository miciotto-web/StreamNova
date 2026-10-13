package com.example.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.ui.components.TvFocusableBox
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaSurface
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary

data class PlayerQuickTrackOption(
    val index: Int?,
    val title: String,
    val details: String,
    val selected: Boolean
)

@Composable
fun PlayerTrackQuickPanel(
    audioOptions: List<PlayerQuickTrackOption>,
    onAudioSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val initialFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { initialFocusRequester.requestFocus() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .width(440.dp)
                .background(NovaSurface, RoundedCornerShape(20.dp))
                .border(1.5.dp, NovaCyan, RoundedCornerShape(20.dp))
                .padding(22.dp)
        ) {
            Text(
                text = stringResource(R.string.player_track_quick_title),
                color = NovaTextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 19.sp
            )
            Spacer(Modifier.height(16.dp))
            TrackQuickSection(
                title = stringResource(R.string.player_audio_dialog_title),
                emptyText = stringResource(R.string.player_audio_default),
                options = audioOptions,
                focusRequester = initialFocusRequester,
                onSelect = { index -> index?.let(onAudioSelected) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TrackQuickSection(
    title: String,
    emptyText: String,
    options: List<PlayerQuickTrackOption>,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            color = NovaCyanBright,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(8.dp))
        if (options.isEmpty()) {
            Text(emptyText, color = NovaTextMuted, fontSize = 13.sp)
        } else {
            Column(
                modifier = Modifier
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                options.forEachIndexed { index, option ->
                    TvFocusableBox(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == 0 && focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
                        shape = RoundedCornerShape(10.dp),
                        focusedScale = 1.02f,
                        onClick = { onSelect(option.index) }
                    ) { focused ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    when {
                                        option.selected -> NovaCyan.copy(alpha = 0.18f)
                                        focused -> NovaSurfaceVariant
                                        else -> Color.Transparent
                                    },
                                    RoundedCornerShape(10.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 9.dp)
                        ) {
                            Text(
                                text = option.title,
                                color = if (focused || option.selected) NovaCyanBright else NovaTextPrimary,
                                fontSize = 13.sp,
                                fontWeight = if (option.selected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (option.details.isNotBlank()) {
                                Text(
                                    text = option.details,
                                    color = NovaTextSecondary,
                                    fontSize = 11.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
