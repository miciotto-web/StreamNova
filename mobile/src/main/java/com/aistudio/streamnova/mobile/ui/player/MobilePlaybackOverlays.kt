package com.aistudio.streamnova.mobile.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.aistudio.streamnova.mobile.R
import com.example.core.designsystem.R as DesignR
import com.example.data.repository.ParentalGuideCategory
import com.example.data.repository.ParentalGuideCategoryType
import com.example.data.repository.ParentalGuideSeverity
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaTextPrimary

/**
 * Overlay touch del player mobile per le opzioni "Overlay e avvisi di riproduzione"
 * (sezione Riproduzione): stessa preferenza e stesso comportamento della TV, con
 * dimensioni e disposizione adatte allo schermo touch.
 *
 * Nessun elemento è cliccabile: i tocchi attraversano gli overlay e raggiungono il
 * livello gesti e i controlli del player (pausa/riproduzione, seek, uscita).
 */

/** Tag di semantica dell'overlay in pausa (verifiche UI nei test). */
object MobilePauseOverlayTestTags {
  const val ROOT = "pause_overlay_root"
  const val POSTER = "pause_overlay_poster"
  const val TITLE = "pause_overlay_title"
  const val SYNOPSIS = "pause_overlay_synopsis"
}

/** Tag di semantica dell'overlay Parental Guide (verifiche UI nei test). */
object MobileParentalGuideTestTags {
  const val ROOT = "parental_guide_root"
  const val TITLE = "parental_guide_title"
  fun categoryRow(typeName: String) = "parental_guide_row_$typeName"
}

/**
 * Overlay informativo mostrato dopo 5 secondi di pausa (opzione "Overlay in pausa"):
 * poster, logo StreamNova, titolo ORIGINALE, stagione/episodio e sinossi (dell'episodio
 * per le serie, altrimenti quella del titolo). Tutti dati già in sessione, nessuna rete.
 *
 * Adattamento touch: pannello più compatto della TV (poster 96×144), in alto a sinistra
 * sotto la barra del titolo; senza gestori di click, così i controlli del player restano
 * pienamente operativi.
 *
 * @param title titolo originale (fallback: titolo localizzato).
 * @param episodeSubtitle "S1E1 • Titolo episodio" per le serie; `null` per i film.
 * @param synopsis sinossi mostrata; stringa vuota = nessun paragrafo.
 */
@Composable
fun MobilePauseInfoOverlay(
  posterUrl: String?,
  title: String,
  episodeSubtitle: String?,
  synopsis: String,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current

  Row(
    verticalAlignment = Alignment.Top,
    modifier = modifier
      .widthIn(max = 460.dp)
      .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(16.dp))
      .border(1.dp, NovaCyan.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
      .padding(16.dp)
      .testTag(MobilePauseOverlayTestTags.ROOT)
  ) {
    // Poster del contenuto. Se manca o non si carica resta il riquadro scuro: la
    // card non è mai vuota né deforme.
    SubcomposeAsyncImage(
      model = ImageRequest.Builder(context)
        .data(posterUrl)
        .crossfade(200)
        .build(),
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = Modifier
        .width(96.dp)
        .height(144.dp)
        .clip(RoundedCornerShape(10.dp))
        .testTag(MobilePauseOverlayTestTags.POSTER),
      loading = {
        Box(
          Modifier
            .width(96.dp)
            .height(144.dp)
            .background(Color.White.copy(alpha = 0.06f))
        )
      },
      error = {
        Box(
          Modifier
            .width(96.dp)
            .height(144.dp)
            .background(Color.White.copy(alpha = 0.06f))
        )
      }
    )

    Spacer(modifier = Modifier.width(16.dp))

    Column {
      Image(
        painter = painterResource(DesignR.drawable.streamnova_logo),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
          .width(88.dp)
          .height(24.dp)
      )

      Spacer(modifier = Modifier.height(10.dp))

      Text(
        text = stringResource(R.string.player_pause_overlay_paused),
        color = NovaCyan,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp
      )

      Spacer(modifier = Modifier.height(4.dp))

      Text(
        text = title,
        color = NovaTextPrimary,
        fontSize = 18.sp,
        fontWeight = FontWeight.ExtraBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag(MobilePauseOverlayTestTags.TITLE)
      )

      if (episodeSubtitle != null) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = episodeSubtitle,
          color = Color.White.copy(alpha = 0.78f),
          fontSize = 12.sp,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }

      if (synopsis.isNotBlank()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = synopsis,
          color = Color.White.copy(alpha = 0.72f),
          fontSize = 12.sp,
          lineHeight = 16.sp,
          maxLines = 4,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.testTag(MobilePauseOverlayTestTags.SYNOPSIS)
        )
      }
    }
  }
}

/** Ombra leggera dietro al testo della guida: leggibilità su scene chiare senza riquadro. */
private val OverlayTextShadow = TextStyle(
  shadow = Shadow(
    color = Color.Black.copy(alpha = 0.75f),
    offset = Offset(1.5f, 1.5f),
    blurRadius = 6f
  )
)

/**
 * Overlay Parental Guide (opzione "Avvisi sui contenuti"): volgarità, violenza, paura
 * e alcol/droghe con livello di gravità. Solo testo senza riquadro (ombra nera per la
 * leggibilità), dissolvenza lenta guidata da [visible]; la durata è gestita dal player.
 *
 * Come sulla TV non intercetta alcun tocco (nessun click target) e non mostra MAI un
 * overlay vuoto: il chiamante lo rende solo con categorie effettive.
 *
 * @param categories categorie valide con livello dominante già calcolato; l'ordine di
 *   arrivo da Tiffara è preservato.
 */
@Composable
fun MobileParentalGuideOverlay(
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
        .widthIn(max = 300.dp)
        .padding(horizontal = 14.dp, vertical = 12.dp)
        .testTag(MobileParentalGuideTestTags.ROOT)
    ) {
      Text(
        text = stringResource(R.string.parental_guide_title),
        color = NovaTextPrimary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        style = OverlayTextShadow,
        modifier = Modifier.testTag(MobileParentalGuideTestTags.TITLE)
      )

      Spacer(modifier = Modifier.height(8.dp))

      categories.forEach { category ->
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier
            .padding(vertical = 2.dp)
            .testTag(MobileParentalGuideTestTags.categoryRow(category.type.name))
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
            // dopo il nome categoria più lungo.
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
private fun categoryLabel(type: ParentalGuideCategoryType): String = when (type) {
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
private fun severityLabel(severity: ParentalGuideSeverity): String = when (severity) {
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
