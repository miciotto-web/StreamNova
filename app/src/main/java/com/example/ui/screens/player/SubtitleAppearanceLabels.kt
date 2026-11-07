package com.example.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.data.prefs.SubtitleBackground
import com.example.data.prefs.SubtitleOpacity
import com.example.data.prefs.SubtitleSize

/**
 * Etichette localizzate dell'aspetto dei sottotitoli.
 *
 * Vivono qui, in un unico punto, perché le usano sia le Impostazioni generali sia il
 * menu Sottotitoli del player: due mapping separati finirebbero per divergere (es. un
 * livello di dimensione aggiunto solo in una delle due schermate).
 */

@Composable
internal fun subtitleSizeLabel(size: SubtitleSize): String = when (size) {
  SubtitleSize.EXTRA_SMALL -> stringResource(R.string.sub_size_extra_small)
  SubtitleSize.SMALL -> stringResource(R.string.sub_size_small)
  SubtitleSize.MEDIUM -> stringResource(R.string.sub_size_medium)
  SubtitleSize.LARGE -> stringResource(R.string.sub_size_large)
  SubtitleSize.VERY_LARGE -> stringResource(R.string.sub_size_very_large)
}

@Composable
internal fun subtitleBackgroundLabel(background: SubtitleBackground): String = when (background) {
  SubtitleBackground.NONE -> stringResource(R.string.sub_bg_none)
  SubtitleBackground.BLACK -> stringResource(R.string.sub_bg_black)
  SubtitleBackground.WHITE -> stringResource(R.string.sub_bg_white)
  SubtitleBackground.BLUE -> stringResource(R.string.sub_bg_blue)
}

@Composable
internal fun subtitleOpacityLabel(opacity: SubtitleOpacity): String = when (opacity) {
  SubtitleOpacity.FULL -> stringResource(R.string.sub_opacity_full)
  SubtitleOpacity.SEVENTY_FIVE -> stringResource(R.string.sub_opacity_high)
  SubtitleOpacity.FIFTY -> stringResource(R.string.sub_opacity_medium)
  SubtitleOpacity.TWENTY_FIVE -> stringResource(R.string.sub_opacity_low)
}
