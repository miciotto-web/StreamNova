package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import kotlinx.coroutines.delay

/** Intervallo dell'auto-scorrimento, riavviato da zero a ogni interazione manuale. */
private const val HERO_AUTO_SCROLL_DELAY_MS = 10_000L

/** Leggero ingrandimento dell'Hero quando ha il focus (stessa lingua visiva delle card). */
private const val HERO_FOCUSED_SCALE = 1.015f

/** Spazio riservato in basso per la riga dei pallini centrati. */
private val HERO_DOTS_RESERVED_HEIGHT = 56.dp

/**
 * Banner hero della Home: superficie unica, grande e pulita.
 *
 * Interazione (nessun pulsante interno: l'Hero *e'* il target di focus):
 * - OK / ENTER: apre la scheda dettaglio del titolo corrente ([onDetailsClick]).
 * - D-pad RIGHT: poster successivo con wrap-around, il timer dell'auto-scroll riparte da zero.
 * - D-pad LEFT: poster precedente; sul primissimo poster l'evento NON viene consumato, cosi'
 *   il focus esce dall'Hero e si posiziona sulla Sidebar (gestito da `MainActivity`).
 * - D-pad DOWN: scende alla riga IMMEDIATAMENTE sotto l'Hero. Il movimento è deciso dal
 *   coordinatore delle righe Home tramite [onHeroMoveDown] (che consuma il tasto e, se la riga
 *   di destinazione non è ancora composta, ne completa il focus appena diventa disponibile).
 * - D-pad UP: annullato, l'Hero e' la prima riga della Home.
 *
 * Focus: [heroFocusRequester] e' il requester della cella "hero_banner" del coordinatore di
 * Home. Il D-pad DOWN NON usa `focusProperties { down = ... }`: un requester assegnato a una
 * card della riga sottostante può diventare ORFANO quando la LazyColumn smonta/ricicla la riga
 * durante lo scroll, e il focus search del D-pad su un requester non agganciato termina con
 * `IllegalStateException: FocusRequester is not initialized` (crash). Il movimento passa
 * ESCLUSIVAMENTE da [onHeroMoveDown], che consuma il tasto e delega al coordinatore delle righe
 * (richieste controllate su celle montate, con fallback pendente crash-safe). Ai fini della
 * ricerca nativa `down` resta quindi `FocusRequester.Cancel`: se il coordinatore non ha una
 * destinazione il tasto resta inerte invece di far atterrare il focus dove capita.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HeroBanner(
  items: List<MediaItem>,
  onDetailsClick: (MediaItem) -> Unit,
  modifier: Modifier = Modifier,
  heroFocusRequester: FocusRequester? = null,
  onHeroMoveDown: (() -> Boolean)? = null,
  onHeroFocusChanged: ((Boolean) -> Unit)? = null
) {
  val safeItems = remember(items) { items.take(9) }
  if (safeItems.isEmpty()) return

  var currentIndex by remember(safeItems) { mutableStateOf(0) }
  var isFocused by remember { mutableStateOf(false) }

  // Token di riavvio dell'auto-scroll: ogni scorrimento manuale con il D-pad lo incrementa,
  // il LaunchedEffect viene cancellato e rilanciato e il `delay` riparte da zero. Un contatore
  // (e non `System.currentTimeMillis()`) non puo' collidere quando due pressioni cadono nello
  // stesso millisecondo.
  var autoScrollRestartToken by remember(safeItems) { mutableLongStateOf(0L) }

  // Auto-scorrimento: il ciclo vive in un `while` dentro lo stesso effetto, quindi il delay si
  // ripete finche' l'utente non interagisce (cambio token = effetto cancellato e riavviato).
  // L'indice viene letto al momento del cambio poster, non catturato alla partenza.
  LaunchedEffect(safeItems, autoScrollRestartToken) {
    if (safeItems.size > 1) {
      while (true) {
        delay(HERO_AUTO_SCROLL_DELAY_MS)
        currentIndex = HeroCarouselNavigation.nextIndex(currentIndex, safeItems.size)
      }
    }
  }

  // Stato esposto a MainActivity: il focus sull'Hero e indice/numero di poster decidono se il
  // D-pad LEFT appartiene al carosello o deve aprire la Sidebar.
  SideEffect { HeroCarouselState.onCarouselChanged(currentIndex, safeItems.size) }
  DisposableEffect(Unit) {
    onDispose { HeroCarouselState.reset() }
  }

  // Azione comune a Destra e Sinistra: cambia poster e riavvia il timer dell'auto-scroll.
  val scrollToPoster: (Int) -> Unit = { newIndex ->
    currentIndex = newIndex
    autoScrollRestartToken += 1
  }

  val currentMedia = safeItems.getOrElse(currentIndex) { safeItems.first() }

  val heroScale by animateFloatAsState(
    targetValue = if (isFocused) HERO_FOCUSED_SCALE else 1f,
    animationSpec = tween(durationMillis = 200),
    label = "HeroFocusScale"
  )
  // Bordo di focus animato (trasparente -> cyan): stesso linguaggio visivo delle card.
  // Niente ombra portata: su un banner a tutta larghezza l'elevazione disegna una banda
  // cyan netta sotto l'Hero invece di un alone morbido.
  val heroBorderColor by animateColorAsState(
    targetValue = if (isFocused) NovaCyanBright else Color.Transparent,
    animationSpec = tween(durationMillis = 200),
    label = "HeroFocusBorder"
  )

  // Ombra leggera di supporto per il titolo: garantisce leggibilità del testo su artwork chiari.
  val titleShadowStyle = TextStyle(
    shadow = Shadow(color = Color.Black.copy(alpha = 0.6f), blurRadius = 8f)
  )

  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(350.dp)
      .zIndex(if (isFocused) 10f else 1f)
      .graphicsLayer {
        scaleX = heroScale
        scaleY = heroScale
      }
      .then(if (heroFocusRequester != null) Modifier.focusRequester(heroFocusRequester) else Modifier)
      .focusProperties {
        up = FocusRequester.Cancel
        // Il GIU' vero è del coordinatore (onKeyEvent -> onHeroMoveDown, tasto consumato).
        // `Cancel` qui serve solo a non lasciare il tasto alla ricerca geometrica quando il
        // coordinatore NON consuma (nessuna riga sottostante): mai un requester che può
        // diventare orfano durante lo scroll (crash "FocusRequester is not initialized").
        down = FocusRequester.Cancel
      }
      .onFocusChanged {
        isFocused = it.isFocused
        HeroCarouselState.onHeroFocusChanged(it.isFocused)
        onHeroFocusChanged?.invoke(it.isFocused)
      }
      // Scorrimento manuale e conferma: i tasti sono consumati PRIMA di clickable, quindi
      // l'apertura della scheda avviene una sola volta.
      .onKeyEvent { keyEvent ->
        handleHeroKeyEvent(
          keyEvent = keyEvent,
          currentIndex = currentIndex,
          itemCount = safeItems.size,
          onScrollToIndex = scrollToPoster,
          onConfirm = { onDetailsClick(currentMedia) },
          onMoveDown = onHeroMoveDown
        )
      }
      // Superficie cliccabile (mouse/air-mouse) e focalizzabile con il D-pad. `indication`
      // nullo: nessun feedback visivo di bordo o alone quando riceve il focus.
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null
      ) { onDetailsClick(currentMedia) }
  ) {
    // Backdrop Image con dissolvenza incrociata cinematografica tra i titoli (~350ms)
    AnimatedContent(
      targetState = currentMedia,
      transitionSpec = {
        fadeIn(animationSpec = tween(durationMillis = 350)) togetherWith
          fadeOut(animationSpec = tween(durationMillis = 350))
      },
      label = "HeroBackdropCrossfade"
    ) { media ->
      Box(modifier = Modifier.fillMaxSize()) {
        if (!media.backdropUrl.isNullOrBlank()) {
          AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
              .data(media.backdropUrl)
              .crossfade(350)
              .build(),
            contentDescription = media.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
          )
        } else {
          val backdrop = media.backdropRes?.takeIf { it != R.drawable.banner_dune }
          if (backdrop != null) {
          Image(
            painter = painterResource(id = backdrop),
            contentDescription = media.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
          )
          } else {
            Box(
              modifier = Modifier
                .fillMaxSize()
                .background(
                  Brush.verticalGradient(
                    colors = listOf(Color(0xFF0F172A), NovaBackground)
                  )
                )
            )
          }
        }
      }
    }

    // Vignette Gradient a doppio asse: profondità e leggibilità su qualsiasi artwork.
    // Il colore di raccordo è lo sfondo della schermata, così l'Hero si fonde con la
    // prima riga di poster senza stacco.
    val vignetteColor = MaterialTheme.colorScheme.background
    Box(
      modifier = Modifier
        .fillMaxSize()
        // Orizzontale (sinistra -> destra): protegge titolo, testo e metadati a sinistra.
        .background(
          Brush.horizontalGradient(
            colors = listOf(
              vignetteColor.copy(alpha = 0.85f),
              vignetteColor.copy(alpha = 0.5f),
              Color.Transparent
            ),
            startX = 0f,
            endX = Float.POSITIVE_INFINITY
          )
        )
        // Verticale (fondo -> alto): sfuma al 100% nello sfondo verso la prima riga di poster.
        .background(
          Brush.verticalGradient(
            colors = listOf(
              Color.Transparent,
              Color.Transparent,
              vignetteColor.copy(alpha = 0.5f),
              vignetteColor
            ),
            startY = 0f,
            endY = Float.POSITIVE_INFINITY
          )
        )
    )

    // Contenuto Principale Sovrapposto: solo i metadati essenziali del titolo.
    Column(
      modifier = Modifier
        .align(Alignment.BottomStart)
        .padding(
          start = 32.dp,
          // Spazio in basso per i pallini centrati: nessuna sovrapposizione col testo.
          bottom = HERO_DOTS_RESERVED_HEIGHT,
          end = 200.dp
        )
    ) {
      // Titolo / Logo del film o serie TMDB
      if (!currentMedia.logoUrl.isNullOrBlank()) {
        SubcomposeAsyncImage(
          model = ImageRequest.Builder(LocalContext.current)
            .data(currentMedia.logoUrl)
            .crossfade(350)
            .build(),
          contentDescription = currentMedia.title,
          contentScale = ContentScale.Fit,
          alignment = Alignment.CenterStart,
          modifier = Modifier
            .heightIn(min = 44.dp, max = 76.dp)
            .widthIn(max = 420.dp),
          error = {
            Text(
              text = currentMedia.title,
              color = NovaTextPrimary,
              fontSize = 34.sp,
              fontWeight = FontWeight.ExtraBold,
              letterSpacing = 0.5.sp,
              style = titleShadowStyle
            )
          }
        )
      } else {
        Text(
          text = currentMedia.title,
          color = NovaTextPrimary,
          fontSize = 34.sp,
          fontWeight = FontWeight.ExtraBold,
          letterSpacing = 0.5.sp,
          style = titleShadowStyle
        )
      }

      Spacer(modifier = Modifier.height(10.dp))

      // Badge compatti e allineati: Anno • Classificazione • Durata/Stagioni (+ generi)
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        if (currentMedia.year > 0) {
          QualityBadge(text = currentMedia.displayYear)
        }
        currentMedia.ageRating?.let { age ->
          QualityBadge(text = "$age+")
        }
        QualityBadge(
          text = if (currentMedia.type == MediaType.SERIE_TV) {
            stringResource(R.string.hero_seasons_count, currentMedia.seasonsCount ?: 1)
          } else {
            currentMedia.formattedDuration
          }
        )
        if (currentMedia.genres.isNotEmpty()) {
          Text(
            text = currentMedia.genres.take(3).joinToString(" · "),
            color = NovaTextSecondary,
            fontSize = 13.sp
          )
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      // Sinossi
      Text(
        text = currentMedia.synopsis,
        color = NovaTextSecondary,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
      )
    }

    // Indicatori di Scorrimento: pill centrata in basso, unico segnale di posizione nel
    // carosello ora che i pulsanti non ci sono piu'.
    if (safeItems.size > 1) {
      Row(
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .padding(bottom = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        safeItems.forEachIndexed { index, _ ->
          val isSelected = index == currentIndex
          val indicatorWidth by animateDpAsState(
            targetValue = if (isSelected) 20.dp else 6.dp,
            animationSpec = tween(durationMillis = 300),
            label = "IndicatorWidth"
          )
          val indicatorColor by animateColorAsState(
            targetValue = if (isSelected) NovaCyanBright else Color.White.copy(alpha = 0.35f),
            animationSpec = tween(durationMillis = 300),
            label = "IndicatorColor"
          )

          Box(
            modifier = Modifier
              .height(6.dp)
              .width(indicatorWidth)
              .clip(RoundedCornerShape(50))
              .background(indicatorColor)
              // Click su un pallino: salto diretto al poster (riavvia il timer). Il pallino
              // non prende il focus del D-pad, che resta sulla superficie dell'Hero.
              .focusProperties { canFocus = false }
              .clickable { scrollToPoster(index) }
          )
        }
      }
    }
  }
}

/**
 * Tasti del telecomando sull'Hero (superficie unica di focus).
 *
 * - OK / ENTER / NumPadEnter: apre la scheda del titolo corrente al rilascio.
 * - RIGHT: poster successivo con wrap-around.
 * - LEFT: poster precedente; sul primissimo poster NON viene consumato, cosi' il focus esce
 *   dall'Hero e si posiziona sulla Sidebar (gestito da `MainActivity`).
 * - Ogni scorrimento riavvia il timer dell'auto-scroll tramite [onScrollToIndex].
 *
 * @return true se l'evento e' stato consumato.
 */
private fun handleHeroKeyEvent(
  keyEvent: KeyEvent,
  currentIndex: Int,
  itemCount: Int,
  onScrollToIndex: (Int) -> Unit,
  onConfirm: () -> Unit,
  onMoveDown: (() -> Boolean)? = null
): Boolean {
  return when (keyEvent.key) {
    Key.Enter, Key.NumPadEnter, Key.DirectionCenter ->
      if (keyEvent.type == KeyEventType.KeyUp) {
        onConfirm()
        true
      } else {
        false
      }

    Key.DirectionDown ->
      // Il Giù dall'Hero è un movimento verticale come quelli fra le righe: lo decide il
      // coordinatore (riga immediatamente sottostante, richiesta differita se non composta).
      // Se il coordinatore non ha una destinazione il tasto non viene consumato e resta
      // valido il `down` di focusProperties.
      if (keyEvent.type == KeyEventType.KeyDown && onMoveDown != null) {
        onMoveDown()
      } else {
        false
      }

    Key.DirectionRight ->
      if (keyEvent.type == KeyEventType.KeyDown && itemCount > 1) {
        onScrollToIndex(HeroCarouselNavigation.nextIndex(currentIndex, itemCount))
        true
      } else {
        false
      }

    Key.DirectionLeft ->
      if (keyEvent.type == KeyEventType.KeyDown &&
        HeroCarouselNavigation.canScrollToPrevious(currentIndex, itemCount)
      ) {
        onScrollToIndex(HeroCarouselNavigation.previousIndex(currentIndex))
        true
      } else {
        false
      }

    else -> false
  }
}
