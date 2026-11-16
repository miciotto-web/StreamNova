package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.repository.MediaRepository
import com.example.data.stremio.StremioCatalogMapper
import com.example.ui.theme.NovaCardBg
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Restyling cinematografico delle righe «In evidenza: Film» / «In evidenza: Serie TV»
 * (riferimento: classifiche Top 10 Netflix).
 *
 * Ogni cella della riga è la coppia [numero di classifica outline] + [card]:
 *
 * - NON selezionata: locandina verticale 148×220 invariata, angoli arrotondati, titolo e
 *   anno sotto la locandina. Nessuna sinossi, nessuna scheda dettagliata.
 * - SELEZIONATA (focus D-pad): la stessa card — stesso nodo focusable, stessa identità per
 *   il coordinatore — si allarga fino al rapporto 16:9 (391dp) con immagine orizzontale,
 *   gradiente scuro inferiore, titolo/logo del contenuto dentro l'immagine, bordo e alone
 *   Neon [NovaCyanBright]. Sotto la card le informazioni passano in dissolvenza da
 *   «titolo + anno» a «GENERE · ANNO · STAGIONI · CLASSIFICAZIONE» + sinossi (max 3 righe).
 *
 * Il numero di classifica resta SEMPRE visibile (anche a card espansa): contorno grigio
 * scuro, interno trasparente ([Stroke] senza riempimento), leggero rilievo, altezza pari a
 * circa il 40-45% della locandina. La card lo copre parzialmente sul bordo destro, come
 * nella composizione Netflix.
 *
 * Animazioni: le larghezze/alpha sono [animateDpAsState]/[animateFloatAsState] leggiti solo
 * nelle fasi layout/draw — nessun ritardo arbitrario, nessun cambio di identità della card,
 * quindi il focus non si perde né durante l'espansione né con movimenti rapidi del telecomando.
 *
 * Dati: sinossi, genere, anno, backdrop 16:9 arrivano dal [MediaItem] già in memoria; logo,
 * certificazione e numero di stagioni arrivano da [MediaRepository.FeaturedCardMeta]
 * (richiesti SOLO al primo focus, cachati in Room 12h). Risorsa mancante → segmento omesso
 * o fallback testuale: nessun valore inventato.
 */

/** Larghezza della locandina verticale non selezionata: geometria Top 10 invariata. */
internal val FeaturedPosterWidth = 148.dp

/** Altezza condivisa da locandina verticale e scheda 16:9. */
internal val FeaturedPosterHeight = 220.dp

/**
 * Larghezza della scheda selezionata: 16:9 esatto sull'altezza della locandina
 * (220dp × 16/9 ≈ 391dp), «sensibilmente più larga» come da specifica.
 */
internal val FeaturedCardExpandedWidth = 391.dp

/** Altezza dell'area numero + card: ereditata dalla geometria Top 10 (già centrata). */
internal val FeaturedCardAreaHeight = 235.dp

/**
 * Slot informativo SOTTO la card, ad ALTEZZA FISSA per ogni cella: contiene «titolo + anno»
 * quando la card è verticale e «metadati + sinossi» quando è 16:9. L'altezza costante
 * impedisce sobbalzi verticali della riga (e delle righe sottostanti) a ogni cambio focus.
 */
internal val FeaturedInfoSlotHeight = 92.dp

/**
 * Corpo del numero di classifica: ~124sp ⇒ glifo ≈ 89dp ≈ 40% dell'altezza locandina
 * (richiesta: 40-45%). Il contorno (stroke) e non il riempimento definisce il glifo.
 */
internal val FeaturedRankFontSize = 124.sp

/** Area di layout del numero: una cifra oppure due («10»). */
private val FeaturedRankAreaOneDigit = 78.dp
private val FeaturedRankAreaTwoDigits = 150.dp

/**
 * Quanto la card copre il bordo destro del numero: la locandina «sale sopra» la cifra
 * (zIndex di composizione) riproducendo la composizione Netflix con il numero dietro.
 */
private val FeaturedRankOverlap = 12.dp

/**
 * Larghezza dell'area del numero per la posizione [rank]: identica per 1-9 (una cifra),
 * allargata per i numeri a due cifre come «10».
 */
internal fun featuredRankAreaWidth(rank: Int): Dp =
  if (rank >= 10) FeaturedRankAreaTwoDigits else FeaturedRankAreaOneDigit

/**
 * Cella «In evidenza» della riga Top 10 Home: numero outline + card verticale/16:9.
 *
 * @param meta metadati arricchiti (logo, certificazione, stagioni) già recuperati per
 *   [MediaItem.id]; `null` finché l'arricchimento non è arrivato: la card mostra subito
 *   ciò che ha e i segmenti mancanti compaiono dopo, senza salti di layout.
 * @param onRequestMeta invocato una volta per focus: chiede al ViewModel l'arricchimento
 *   (deduplicato lato ViewModel/repository: nessuna richiesta ripetuta a cambio focus).
 * @param rowKey chiave della riga Home (`section_top_10_film` / `section_top_10_serie`):
 *   con `rowKey != null` il ripristino del focus è guidato dal coordinatore della Home.
 * @param isRestoreTarget true per la card target del ripristino focus al BACK dal Dettaglio.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FeaturedCinemaCard(
  rank: Int,
  media: MediaItem,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  meta: MediaRepository.FeaturedCardMeta? = null,
  onRequestMeta: ((MediaItem) -> Unit)? = null,
  focusRequester: FocusRequester? = null,
  rowKey: String? = null,
  isRestoreTarget: Boolean = false
) {
  var isFocused by remember { mutableStateOf(false) }

  // Arricchimento dati SOLO alla prima selezione della card: la chiamata è idempotente
  // lato ViewModel (memo + cache Room), quindi i focus successivi non rifanno rete.
  LaunchedEffect(isFocused) {
    if (isFocused) onRequestMeta?.invoke(media)
  }

  // ---------------------------------------------------------------------------
  // Animazioni: gli stati animati vengono letti SOLO nelle lambda di layout/draw,
  // quindi l'espansione non ricompone l'albero a ogni frame.
  // ---------------------------------------------------------------------------
  val cardWidth by animateDpAsState(
    targetValue = if (isFocused) FeaturedCardExpandedWidth else FeaturedPosterWidth,
    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
    label = "featuredCardWidth"
  )
  val overlayProgress by animateFloatAsState(
    targetValue = if (isFocused) 1f else 0f,
    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
    label = "featuredOverlayProgress"
  )
  val glowState by animateFloatAsState(
    targetValue = if (isFocused) 1f else 0f,
    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
    label = "featuredGlow"
  )
  val specularState by animateFloatAsState(
    targetValue = if (isFocused) 1f else 0f,
    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
    label = "featuredSpecular"
  )

  // Il backdrop 16:9 viene richiesto alla PRIMA espansione e resta composto: Coil lo
  // tiene in cache, quindi i successivi cambi di stato non ricaricano l'immagine.
  var backdropRequested by remember(media.id) { mutableStateOf(false) }
  var backdropReady by remember(media.id) { mutableStateOf(false) }
  LaunchedEffect(isFocused) {
    if (isFocused) backdropRequested = true
  }

  // ---------------------------------------------------------------------------
  // Bring-into-view: quando la card riceve il focus il rettangolo rivelato copre l'INTERA
  // larghezza dell'espansione target (numero + card 16:9), così la scheda non resta mai
  // tagliata al bordo destro e il numero non finisce sotto la Sidebar.
  // ---------------------------------------------------------------------------
  val bringIntoViewRequester = remember { BringIntoViewRequester() }
  val density = LocalDensity.current
  val rankLabel = rank.toString()
  val rankAreaWidth = featuredRankAreaWidth(rank)
  val revealRect = remember(density, rankAreaWidth, isFocused) {
    val cardTop = (FeaturedCardAreaHeight - FeaturedPosterHeight) / 2
    val cardLeft = rankAreaWidth - FeaturedRankOverlap
    val cardWidthTarget = if (isFocused) FeaturedCardExpandedWidth else FeaturedPosterWidth
    with(density) {
      Rect(
        left = 0f,
        top = cardTop.toPx(),
        right = (cardLeft + cardWidthTarget).toPx(),
        bottom = (cardTop + FeaturedPosterHeight).toPx()
      )
    }
  }
  LaunchedEffect(isFocused) {
    if (isFocused) {
      try {
        bringIntoViewRequester.bringIntoView(revealRect)
      } catch (_: Exception) {}
    }
  }

  // ---------------------------------------------------------------------------
  // Protocollo di ripristino focus: identico a [Top10RankedMediaCard] (stessa Home).
  // ---------------------------------------------------------------------------
  val targetMediaId = LocalLastFocusedMediaId.current
  val pendingRestoreId = targetMediaId
  val isRestoringActive = pendingRestoreId != null
  val restoreTarget = LocalLastFocusedTarget.current ?: targetMediaId?.let { FOCUS_ROW_ANY to it }
  val restoreEligible = matchesFocusRestoreTarget(restoreTarget, rowKey, media.id) &&
    (rowKey == null || isRestoreTarget)
  val clearLastFocusedMedia = LocalClearLastFocusedMedia.current
  val focusRestoreToken = LocalFocusRestoreToken.current
  val internalFocusRequester = remember { FocusRequester() }
  val posterFocusRequester = focusRequester ?: internalFocusRequester

  var focusRequestedToken by remember { mutableStateOf<Int?>(null) }

  LaunchedEffect(focusRestoreToken) {
    if (focusRequestedToken != null && focusRestoreToken != focusRequestedToken) {
      focusRequestedToken = null
    }
  }

  // Nelle righe Home (rowKey != null) il ripristino è guidato dal coordinatore della Home
  // (richiesta pendente + HomeVerticalFocusSync): qui si interviene solo per le card isolate.
  LaunchedEffect(targetMediaId, posterFocusRequester, focusRestoreToken) {
    if (rowKey != null) return@LaunchedEffect
    if (restoreEligible && focusRequestedToken != focusRestoreToken) {
      try {
        posterFocusRequester.requestFocus()
      } catch (_: Exception) {
        // Nodo non ancora agganciato: un tentativo su frame successivo, senza delay arbitrari.
        try {
          posterFocusRequester.requestFocus()
        } catch (_: Exception) {}
      }
      focusRequestedToken = focusRestoreToken
    }
  }

  val posterShape = RoundedCornerShape(NeonCardCorner)
  val posterUrl = StremioCatalogMapper.extractImageUrl(media.posterUrl ?: media.backdropUrl)
  val backdropUrl = StremioCatalogMapper.extractImageUrl(media.backdropUrl)
    ?.takeIf { it.isNotBlank() && it != posterUrl }
  val fallbackRes = (media.posterRes ?: media.backdropRes)?.takeIf { it != R.drawable.banner_dune }

  Column(
    modifier = modifier
      .bringIntoViewRequester(bringIntoViewRequester)
      // L'intera cella (numero + card + informazioni) sale sopra le vicine quando è a fuoco.
      .zIndex(if (isFocused) 10f else 1f)
  ) {
    // ---------------------------------------------------------------------
    // 1. Area numero + card
    // ---------------------------------------------------------------------
    Row(modifier = Modifier.height(FeaturedCardAreaHeight)) {
      // Numero di classifica: NON focusable, dietro alla card, sempre visibile.
      Box(
        modifier = Modifier
          .width(rankAreaWidth)
          .fillMaxHeight(),
        contentAlignment = Alignment.BottomEnd
      ) {
        FeaturedRankNumber(
          rankLabel = rankLabel,
          isFocused = isFocused,
          // L'area piena è indispensabile: il glifo viene disegnato su canvas
          // allineandolo all'angolo in basso a destra di questa misura.
          modifier = Modifier
            .fillMaxSize()
            .offset(y = 10.dp)
        )
      }

      // La card: UNICO nodo focusable della cella. L'offset negativo la sovrappone al
      // bordo destro del numero; l'identità del nodo non cambia mai durante l'animazione.
      Box(
        modifier = Modifier
          .align(Alignment.CenterVertically)
          .offset(x = -FeaturedRankOverlap)
          .width(cardWidth)
          .height(FeaturedPosterHeight)
          .focusProperties {
            canFocus = !isRestoringActive || (media.id == pendingRestoreId && (rowKey == null || isRestoreTarget))
          }
          .focusRequester(posterFocusRequester)
          .onFocusChanged { focusState ->
            isFocused = focusState.isFocused
            if (focusState.isFocused && (isRestoreTarget || rowKey == null)) {
              clearLastFocusedMedia?.invoke()
              focusRequestedToken = null
            }
          }
          .onKeyEvent { keyEvent ->
            val isConfirm = keyEvent.key == Key.Enter ||
              keyEvent.key == Key.NumPadEnter ||
              keyEvent.key == Key.DirectionCenter
            if (isConfirm && keyEvent.type == KeyEventType.KeyUp) {
              onClick()
              true
            } else {
              false
            }
          }
          .clickable { onClick() }
          // Alone Neon PRIMA del clip: si estende oltre i bordi della card.
          .drawBehind {
            drawNeonGlow(
              cornerRadiusPx = NeonCardCorner.toPx(),
              color = NovaCyanBright,
              spreadPx = NeonCardGlowSpread.toPx(),
              intensity = glowState
            )
          }
          .clip(posterShape)
          .background(NovaCardBg)
          .drawWithContent {
            drawContent()
            // Gradiente scuro inferiore: leggibilità di logo/titolo sulla banda bassa.
            if (overlayProgress > 0.01f) {
              drawRect(
                brush = Brush.verticalGradient(
                  colors = listOf(
                    Color.Transparent,
                    Color.Black.copy(alpha = 0.55f * overlayProgress),
                    Color.Black.copy(alpha = 0.85f * overlayProgress)
                  ),
                  startY = size.height * 0.45f,
                  endY = size.height
                )
              )
            }
            drawSpecularHighlight(intensity = specularState * overlayProgress)
            // Bordo ciano StreamNova: presente solo nella scheda 16:9 selezionata.
            drawNeonBorder(
              cornerRadiusPx = NeonCardCorner.toPx(),
              color = NovaCyanBright,
              widthPx = NeonCardBorderWidth.toPx(),
              intensity = glowState
            )
          }
      ) {
        // Layer 1: locandina verticale. Smontata SOLO quando il backdrop 16:9 copre già
        // tutta la card (alpha pieno): evita l'overdraw di due immagini complete nella
        // condizione di riposo espansa. Al collasso torna composta (Coil: cache memoria).
        if (!(overlayProgress > 0.99f && backdropReady)) {
          CardMediaImage(
            primaryUrl = posterUrl,
            secondaryUrl = media.backdropUrl,
            fallbackRes = fallbackRes,
            contentDescription = media.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
          )
        }

        // Layer 2: immagine cinematografica orizzontale 16:9 in dissolvenza sopra il poster.
        if (backdropRequested && backdropUrl != null) {
          val context = LocalContext.current
          val backdropRequest = remember(backdropUrl) {
            ImageRequest.Builder(context)
              .data(backdropUrl)
              .crossfade(true)
              .build()
          }
          AsyncImage(
            model = backdropRequest,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onSuccess = { backdropReady = true },
            modifier = Modifier
              .fillMaxSize()
              .graphicsLayer { alpha = overlayProgress }
          )
        }

        // Titolo del contenuto dentro l'immagine: logo originale quando disponibile,
        // altrimenti il titolo testuale (mai un logo fittizio).
        Box(
          modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .graphicsLayer { alpha = overlayProgress }
        ) {
          val titleFallback: @Composable () -> Unit = {
            Text(
              text = media.title,
              color = NovaTextPrimary,
              fontSize = 22.sp,
              fontWeight = FontWeight.ExtraBold,
              letterSpacing = 0.4.sp,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              style = TextStyle(
                shadow = Shadow(color = Color.Black.copy(alpha = 0.65f), blurRadius = 10f)
              ),
              modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp)
            )
          }
          // Logo del titolo: prima l'arricchimento TMDB, poi il logo già presente nel
          // MediaItem (i cataloghi addon lo portano dal meta `logo`). Un solo
          // fallback testuale se nessuna sorgente ha un logo reale: mai loghi finti.
          val logoUrl = meta?.logoUrl ?: media.logoUrl?.takeIf { it.isNotBlank() }
          if (logoUrl.isNullOrBlank()) {
            titleFallback()
          } else {
            val context = LocalContext.current
            val logoRequest = remember(logoUrl) {
              ImageRequest.Builder(context)
                .data(logoUrl)
                .crossfade(300)
                .build()
            }
            SubcomposeAsyncImage(
              model = logoRequest,
              contentDescription = media.title,
              contentScale = ContentScale.Fit,
              alignment = Alignment.BottomStart,
              modifier = Modifier
                .padding(start = 18.dp, end = 18.dp, bottom = 14.dp)
                .heightIn(min = 34.dp, max = 54.dp)
                .widthIn(max = FeaturedCardExpandedWidth - 36.dp),
              loading = { titleFallback() },
              error = { titleFallback() }
            )
          }
        }
      }
    }

    // ---------------------------------------------------------------------
    // 2. Slot informativo ad altezza fissa: titolo+anno (verticale) oppure
    //    metadati+sinossi (16:9), in dissolvenza incrociata.
    // ---------------------------------------------------------------------
    Box(
      modifier = Modifier
        .offset(x = rankAreaWidth - FeaturedRankOverlap)
        .width(cardWidth)
        .height(FeaturedInfoSlotHeight)
    ) {
      val metadataLine = featuredMetadataLine(media, meta)
      // Sinossi nella lingua dell'app: i cataloghi addon consegnano descrizioni
      // inglesi, quindi vince l'overview TMDB localizzata dell'arricchimento quando
      // esiste (già validata dal localizer dell'Hero); altrimenti quella dell'item.
      val synopsis = (meta?.localizedOverview?.takeIf { it.isNotBlank() } ?: media.synopsis)
        .takeIf { it.isNotBlank() }

      // Stato verticale: titolo + anno sotto la locandina (come il resto delle righe).
      // Rimosso dall'albero quando la scheda è a piena espansione: i testi invisibili non
      // devono restare nell'albero semantico (accessibilità) né duplicare i nodi.
      if (overlayProgress < 0.99f) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .graphicsLayer { alpha = 1f - overlayProgress }
        ) {
          Text(
            text = media.title,
            color = if (isFocused) NovaCyanBright else Color.White,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
          )
          if (media.year > 0) {
            Text(
              text = media.year.toString(),
              color = NovaTextMuted,
              style = MaterialTheme.typography.labelSmall,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }
      }

      // Stato 16:9: GENERE · ANNO · STAGIONI (serie) · CLASSIFICAZIONE + sinossi.
      // Il gate di composizione segue l'animazione: fuori dal tree quando la cella è
      // completamente collassata (nessun testo invisibile per l'accessibilità), poi
      // l'apparizione avviene in fase draw (alpha).
      if (overlayProgress > 0.01f && (metadataLine != null || synopsis != null)) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .graphicsLayer { alpha = overlayProgress }
        ) {
          if (metadataLine != null) {
            Text(
              text = metadataLine,
              color = Color.White.copy(alpha = 0.92f),
              fontSize = 14.sp,
              fontWeight = FontWeight.Medium,
              letterSpacing = 0.3.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
          }
          if (synopsis != null) {
            Text(
              text = synopsis,
              color = NovaTextSecondary,
              fontSize = 13.sp,
              lineHeight = 17.sp,
              maxLines = 3,
              overflow = TextOverflow.Ellipsis
            )
          }
        }
      }
    }
  }
}

/**
 * Riga metadati della scheda 16:9: `GENERE · ANNO · STAGIONI (solo serie) · CLASSIFICAZIONE`.
 * Ogni segmento compare SOLO se il dato esiste davvero nei metadati: mai «Rated R» di
 * comodo, mai «1 Stagioni» inventato per i titoli senza informazioni.
 */
@Composable
private fun featuredMetadataLine(
  media: MediaItem,
  meta: MediaRepository.FeaturedCardMeta?
): String? {
  val parts = mutableListOf<String>()
  // Genere localizzato TMDB quando l'arricchimento l'ha portato (i cataloghi addon
  // espongono generi inglesi); altrimenti il genere già sull'item.
  val genre = meta?.localizedGenres?.firstOrNull()?.takeIf { it.isNotBlank() }
    ?: media.genres.firstOrNull()?.takeIf { it.isNotBlank() }
  genre?.let { parts.add(it) }
  if (media.year > 0) parts.add(media.year.toString())
  if (media.type == MediaType.SERIE_TV) {
    meta?.seasonsCount?.takeIf { it > 0 }?.let { seasons ->
      parts.add(stringResource(R.string.hero_seasons_count, seasons))
    }
  }
  meta?.certification?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
  return parts.joinToString(" · ").takeIf { parts.isNotEmpty() }
}

/**
 * Numero di classifica in stile classifica Netflix: corpo a riempimento
 * semitrasparente («trasparente all'interno», nessun riempimento pieno), contorno
 * sottile grigio scuro e rilievo da ombra scura in offset.
 *
 * Il contorno è ottenuto PER DILATAZIONE CON SOLI RIEMPIMENTI: il glifo fillato viene
 * disegnato a [OUTLINE_OFFSET_COUNT] offset circolari di raggio pari allo spessore del
 * bordo e poi l'interno del glifo (non dilatato) viene cancellato con
 * `BlendMode.DstOut` dentro un `saveLayer`. Nessuno `Stroke`: Roboto costruisce i
 * glifi pesanti (3, 4, 6, 8, 9) con contorni sovrapposti e lo stroke diretto traccia
 * anche i segmenti che attraversano il corpo del glifo (bande, scalette e lenti
 * visibili ai raccordi). I fill non producono mai artefatti: il risultato è un bordo
 * uniforme lungo il profilo e i controforni, senza alcuna linea interna.
 *
 * Quando la propria card è selezionata corpo e contorno passano al ciano StreamNova.
 * La dimensione ([FeaturedRankFontSize]) resta al 40-45% della locandina anche a
 * scheda espansa: il numero non sparisce mai.
 */
private const val OUTLINE_OFFSET_COUNT = 12

@Composable
private fun FeaturedRankNumber(
  rankLabel: String,
  isFocused: Boolean,
  modifier: Modifier = Modifier
) {
  val textMeasurer = rememberTextMeasurer()
  val density = LocalDensity.current
  val outlineWidthPx = remember(density) { with(density) { 2.dp.toPx() } }
  val shadowOffsetPx = remember(density) {
    Offset(with(density) { 1.5.dp.toPx() }, with(density) { 2.dp.toPx() })
  }
  val glyphStyle = remember {
    TextStyle(
      fontSize = FeaturedRankFontSize,
      fontWeight = FontWeight.Black,
      fontFamily = FontFamily.SansSerif,
      letterSpacing = (-2).sp
    )
  }
  // Layout misurato UNA volta (vincoli liberi): i passaggi di disegno lo riutilizzano
  // con colori diversi, quindi la geometria resta identica.
  val textLayout = remember(textMeasurer, rankLabel, glyphStyle) {
    textMeasurer.measure(
      text = rankLabel,
      style = glyphStyle,
      overflow = TextOverflow.Clip,
      softWrap = false,
      maxLines = 1
    )
  }
  val outlineOffsets = remember(outlineWidthPx) {
    List(OUTLINE_OFFSET_COUNT) { i ->
      val angle = 2.0 * PI * i / OUTLINE_OFFSET_COUNT
      Offset((outlineWidthPx * cos(angle)).toFloat(), (outlineWidthPx * sin(angle)).toFloat())
    }
  }
  val bodyColor = if (isFocused) NovaCyanBright.copy(alpha = 0.26f) else Color(0xFF54678A).copy(alpha = 0.34f)
  val outlineColor = if (isFocused) NovaCyanBright else Color(0xFF44546F)
  val layerPaint = remember { Paint() }

  Box(
    modifier = modifier
      // Il numero è disegnato su canvas (nessun nodo Text): la description rende la
      // posizione leggibile a screen reader e individuabile dai test UI.
      .semantics { contentDescription = "Posizione $rankLabel in classifica" }
      .drawBehind {
        // Glifo allineato in basso a destra dell'area riservata (come il layout Text).
        val topLeft = Offset(
          x = size.width - textLayout.size.width,
          y = size.height - textLayout.size.height
        )
        // Rilievo: ombra scura in offset (fill puro: nessun artefatto possibile).
        drawText(textLayout, color = Color(0xFF05080D), topLeft = topLeft + shadowOffsetPx)
        // Corpo semitrasparente: «trasparente all'interno», mai un riempimento pieno.
        drawText(textLayout, color = bodyColor, topLeft = topLeft)
        // Contorno per dilatazione: layer isolato, glifo fillato a offset circolari
        // (unione = silhouette dilatata dello spessore del bordo) e cancellazione
        // dell'interno non dilatato. Solo fill: nessuna linea interna possibile.
        drawContext.canvas.saveLayer(
          bounds = Rect(Offset(-64f, -64f), Size(size.width + 128f, size.height + 128f)),
          paint = layerPaint
        )
        outlineOffsets.forEach { offset ->
          drawText(textLayout, color = outlineColor, topLeft = topLeft + offset)
        }
        drawText(textLayout, color = Color.Black, topLeft = topLeft, blendMode = BlendMode.DstOut)
        drawContext.canvas.restore()
      }
  )
}
