package com.example.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.repository.ParentalGuideCategory
import com.example.data.repository.ParentalGuideCategoryType
import com.example.data.repository.ParentalGuideSeverity
import com.example.ui.theme.NovaTextPrimary

/** Tag di semantica dell'overlay Parental Guide (verifiche UI nei test). */
internal object ParentalGuideTestTags {
  const val ROOT = "parental_guide_root"
  const val TITLE = "parental_guide_title"
  fun categoryRow(typeName: String) = "parental_guide_row_$typeName"
}

/**
 * Ombra leggera dietro al testo dell'overlay: senza riquadro di sfondo, garantisce
 * la leggibilità su scene video chiare restando di fatto invisibile su scene scure
 * (nero sfumato, quasi trasparente).
 */
private val OverlayTextShadow = TextStyle(
  shadow = Shadow(
    color = Color.Black.copy(alpha = 0.75f),
    offset = Offset(1.5f, 1.5f),
    blurRadius = 6f
  )
)

/**
 * Overlay Parental Guide (opzione "Avvisi sui contenuti").
 *
 * Posizione: angolo superiore sinistro (lo pone il chiamante con
 * `Modifier.align(Alignment.TopStart)`); ingresso e uscita con dissolvenza lenta,
 * guidati da [visible]. La durata di visualizzazione (~7,5s) è gestita da PlayerScreen.
 * Solo testo, senza riquadro: nessuno sfondo né bordo, per non coprire il video.
 *
 * NON intercetta mai i comandi DPAD e non sottrae il focus ai controlli di
 * riproduzione (`canFocus = false`, nessun click target): è un elemento puramente
 * informativo sovrapposto al video.
 *
 * Non mostra MAI un overlay vuoto: il chiamante lo rende solo con categorie effettive.
 *
 * @param visible `true` durante i ~7,5 secondi di visualizzazione.
 * @param categories categorie valide con livello dominante già calcolato
 *   ([com.example.data.repository.ParentalGuideRating]); l'ordine di arrivo da Tiffara
 *   è preservato.
 */
@Composable
internal fun ParentalGuideOverlay(
  visible: Boolean,
  categories: List<ParentalGuideCategory>,
  modifier: Modifier = Modifier
) {
  AnimatedVisibility(
    visible = visible,
    enter = fadeIn(animationSpec = tween(durationMillis = 1000)),
    exit = fadeOut(animationSpec = tween(durationMillis = 1000)),
    modifier = modifier
  ) {
    Column(
      modifier = Modifier
        .widthIn(max = 320.dp)
        .padding(horizontal = 14.dp, vertical = 12.dp)
        .focusProperties { canFocus = false }
        .testTag(ParentalGuideTestTags.ROOT)
    ) {
      Text(
        text = stringResource(R.string.parental_guide_title),
        color = NovaTextPrimary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        style = OverlayTextShadow,
        modifier = Modifier.testTag(ParentalGuideTestTags.TITLE)
      )

      Spacer(modifier = Modifier.height(8.dp))

      categories.forEach { category ->
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier
            .padding(vertical = 2.dp)
            .testTag(ParentalGuideTestTags.categoryRow(category.type.name))
        ) {
          Box(
            modifier = Modifier
              .size(8.dp)
              .background(category.severity.color(), CircleShape)
          )
          Text(
            text = categoryLabel(category.type),
            color = Color.White.copy(alpha = 0.88f),
            fontSize = 13.sp,
            style = OverlayTextShadow,
            // Larghezza minima condivisa: allinea i livelli in colonna subito
            // dopo il nome categoria più lungo (entra anche nelle altre lingue).
            modifier = Modifier.widthIn(min = 96.dp)
          )
          Text(
            text = severityLabel(category.severity),
            color = category.severity.color(),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            style = OverlayTextShadow
          )
        }
      }
    }
  }
}

/** Nome visualizzato della categoria; nessuna categoria fuori dalla lista ufficiale. */
@Composable
internal fun categoryLabel(type: ParentalGuideCategoryType): String = when (type) {
  ParentalGuideCategoryType.PROFANITY ->
    stringResource(R.string.parental_guide_category_profanity)
  ParentalGuideCategoryType.VIOLENCE ->
    stringResource(R.string.parental_guide_category_violence)
  ParentalGuideCategoryType.FRIGHTENING_INTENSE_SCENES ->
    stringResource(R.string.parental_guide_category_fear)
  ParentalGuideCategoryType.ALCOHOL_DRUGS ->
    stringResource(R.string.parental_guide_category_alcohol_drugs)
}

/** Nome visualizzato del livello di gravità. */
@Composable
internal fun severityLabel(severity: ParentalGuideSeverity): String = when (severity) {
  ParentalGuideSeverity.MILD -> stringResource(R.string.parental_guide_severity_mild)
  ParentalGuideSeverity.MODERATE -> stringResource(R.string.parental_guide_severity_moderate)
  ParentalGuideSeverity.SEVERE -> stringResource(R.string.parental_guide_severity_severe)
}

/** Tinta del livello: coerente con la palette StreamNova (verde = lieve, rosso = grave). */
private fun ParentalGuideSeverity.color(): Color = when (this) {
  ParentalGuideSeverity.MILD -> Color(0xFF4ADE80)
  ParentalGuideSeverity.MODERATE -> Color(0xFFFACC15)
  ParentalGuideSeverity.SEVERE -> Color(0xFFF87171)
}
