package com.example.ui.components

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.R
import com.example.data.profiles.UserProfile
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaDivider
import com.example.ui.theme.NovaSurface
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.viewmodel.SidebarSection

data class NavigationMenuItem(
  val section: SidebarSection,
  val icon: ImageVector,
  val labelRes: Int
)

/**
 * Voci del menu laterale, in ordine di visualizzazione.
 * Estratte a livello di file per essere verificabili dai test unitari
 * (presenza della voce "Addon" accanto a "Impostazioni").
 */
fun sidebarMenuItems(): List<NavigationMenuItem> = listOf(
  NavigationMenuItem(SidebarSection.HOME, Icons.Default.Home, R.string.nav_home),
  NavigationMenuItem(SidebarSection.FILM, Icons.Default.Movie, R.string.nav_movies),
  NavigationMenuItem(SidebarSection.SERIE_TV, Icons.Default.Tv, R.string.nav_tv_series),
  NavigationMenuItem(SidebarSection.I_MIEI_CONTENUTI, Icons.Default.Favorite, R.string.nav_favorites),
  NavigationMenuItem(SidebarSection.CERCA, Icons.Default.Search, R.string.nav_search),
  NavigationMenuItem(SidebarSection.ADDON, Icons.Default.Extension, R.string.nav_addons),
  NavigationMenuItem(SidebarSection.IMPOSTAZIONI, Icons.Default.Settings, R.string.nav_settings)
)

/**
 * Voci del menu laterale effettivamente visibili, dato lo stato delle impostazioni.
 *
 * [showAddons] arriva da [com.example.data.prefs.StreamingEngineMode.supportsAddonsSection]:
 * in modalità Web Streaming (HTTP) la voce "Addon" sparisce del tutto, icona compresa.
 *
 * Il filtro agisce sulla LISTA prima del rendering: gli item rimanenti si ricompattano
 * (la Column usa `Arrangement.spacedBy`, nessuna altezza riservata) e la ricerca
 * geometrica del focus D-Pad UP/DOWN continua a lavorare su nodi adiacenti, senza
 * fermarsi su spazi vuoti.
 */
fun visibleSidebarMenuItems(showAddons: Boolean): List<NavigationMenuItem> =
  if (showAddons) sidebarMenuItems()
  else sidebarMenuItems().filterNot { it.section == SidebarSection.ADDON }

/**
 * Sidebar di navigazione TV.
 *
 * ARCHITETTURA STATO:
 * - [isSidebarExpanded] = stato "aperta/chiusa" controllato ESCLUSIVAMENTE dall'esterno.
 *   La sidebar NON si auto-apre mai in risposta al focus.
 * - [onCollapseRequest] = chiamato SOLO dall'handler KEYCODE_DPAD_RIGHT di ogni item.
 *   NON viene mai chiamato da onFocusChanged: i transitori hasFocus=false durante
 *   la navigazione UP/DOWN tra item adiacenti non provocano mai la chiusura.
 *
 * Il focus fisico degli item interni NON modifica mai [isSidebarExpanded].
 * Questo elimina sia il flash all'avvio sia la chiusura accidentale durante UP/DOWN.
 *
 * VISIBILITÀ CONDIZIONALE:
 * - [showAddons] = false rimuove la voce "Addon" dalla lista renderizzata (icona inclusa).
 *   Il valore arriva dalla modalità di riproduzione corrente e NON è uno stato interno:
 *   la sidebar resta un componente di sola presentazione.
 */
@Composable
fun SidebarNavigation(
  currentSection: SidebarSection,
  onSectionSelected: (SidebarSection) -> Unit,
  isSidebarExpanded: Boolean,
  onCollapseRequest: () -> Unit,
  itemFocusRequesters: Map<SidebarSection, FocusRequester> = remember {
    SidebarSection.entries.associateWith { FocusRequester() }
  },
  showAddons: Boolean = true,
  /**
   * Ritorno al contenuto (RIGHT dalla sidebar): il CHIAMANTE chiude la sidebar e trasferisce
   * il focus sull'elemento di provenienza della Home.
   *
   * Se `null` resta il comportamento storico (`onCollapseRequest` + ricerca geometrica a
   * destra), inadatto alla Home: la voce della sidebar è in cima alla colonna, quindi la
   * ricerca geometrica sceglie l'Hero o una riga diversa da quella di partenza.
   */
  onReturnRequest: (() -> Unit)? = null,
  /**
   * Profilo attivo mostrato in fondo alla sidebar (avatar + nome).
   * null = blocco assente (preview/test senza profili inizializzati).
   */
  activeProfile: UserProfile? = null,
  /**
   * Click/OK sul blocco profilo: apre il selettore dei profili. null = blocco di sola
   * visualizzazione (preview/test).
   */
  onProfileClick: (() -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  val focusManager = LocalFocusManager.current

  // Uscita verso il contenuto: unico punto che chiude la sidebar su RIGHT. Il percorso
  // preferito è quello del chiamante (ripristino deterministico della provenienza); il
  // ripiego geometrico resta solo per gli usi che non lo forniscono.
  val returnToContent: () -> Unit = onReturnRequest ?: {
    onCollapseRequest()
    focusManager.moveFocus(FocusDirection.Right)
  }

  // L'animazione parte già dallo stato corretto (60dp) perché isSidebarExpanded
  // è inizializzato a false dal chiamante prima del primo frame.
  val sidebarWidth by animateDpAsState(
    targetValue = if (isSidebarExpanded) 220.dp else 60.dp,
    animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
    label = "SidebarWidth"
  )

  val menuItems = visibleSidebarMenuItems(showAddons)

  // CONTAINER ESTERNO: larghezza animata 60dp <-> 220dp.
  // - `clipToBounds()`: taglia i figli ai bordi correnti del container: durante la
  //   chiusura il contenuto viene semplicemente nascosto, mai ricalcolato.
  // - Il container NON misura i figli con la larghezza animata: il contenuto interno
  //   conserva una larghezza stabile di 220dp e viene solo tagliato dal clip.
  Box(
    contentAlignment = Alignment.TopStart,
    modifier = modifier
      .fillMaxHeight()
      .width(sidebarWidth)
      .zIndex(100f)
      .shadow(elevation = if (isSidebarExpanded) 16.dp else 0.dp)
      // Nessun onFocusChanged sul wrapper: durante la navigazione UP/DOWN
      // tra item adiacenti, Compose genera transitori hasFocus=false sul parent
      // che causerebbero chiusure accidentali. La chiusura avviene ESCLUSIVAMENTE
      // tramite KEYCODE_DPAD_RIGHT nell'handler di ogni singolo item.
      .background(
        Brush.horizontalGradient(
          colors = listOf(
            NovaSurface,
            NovaSurface.copy(alpha = 0.98f),
            NovaSurface.copy(alpha = 0.92f)
          )
        )
      )
      .clipToBounds()
  ) {
    // CONTENUTO INTERNO: larghezza STABILE e fissa a 220dp in ogni momento.
    // `wrapContentWidth(unbounded = true, align = Start)` misura il contenuto con
    // larghezza illimitata: le label e il wordmark conservano la loro larghezza
    // naturale anche mentre il container si restringe, e il contenuto resta ancorato
    // al bordo sinistro. Il container esterno, con `clipToBounds`, lo nasconde
    // progressivamente senza mai farlo ricalcolare.
    Column(
      modifier = Modifier
        .wrapContentWidth(align = Alignment.Start, unbounded = true)
        .width(220.dp)
        .fillMaxHeight()
        .padding(vertical = 24.dp, horizontal = 4.dp),
      verticalArrangement = Arrangement.SpaceBetween,
      horizontalAlignment = Alignment.Start
     ) {
       // Header: icona sempre visibile + testo animato
       Row(
         modifier = Modifier.padding(horizontal = 4.dp),
         verticalAlignment = Alignment.CenterVertically
       ) {
         // Icona sempre visibile (sidebar chiusa e aperta)
         Image(
           painter = painterResource(id = R.drawable.streamnova_icon),
           contentDescription = "StreamNova",
           modifier = Modifier.size(40.dp),
           contentScale = ContentScale.Fit
         )

         AnimatedVisibility(
           visible = isSidebarExpanded,
           enter = fadeIn(animationSpec = tween(durationMillis = 200)),
           exit = fadeOut(animationSpec = tween(durationMillis = 150))
         ) {
           Spacer(modifier = Modifier.width(12.dp))
           Image(
             painter = painterResource(id = R.drawable.streamnova_text),
             contentDescription = "StreamNova",
             modifier = Modifier.height(22.dp),
             contentScale = ContentScale.Fit
           )
         }
       }

       Spacer(modifier = Modifier.height(20.dp))

       // Navigation Items
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        menuItems.forEach { item ->
          val isSelected = currentSection == item.section

          val itemRequester = itemFocusRequesters[item.section]
          val itemModifier = if (itemRequester != null) Modifier.focusRequester(itemRequester) else Modifier

          TvFocusableBox(
            modifier = itemModifier
              .height(48.dp)
              .focusProperties {
                canFocus = isSidebarExpanded || currentSection != SidebarSection.CERCA
              }
              .onKeyEvent { keyEvent ->
                // RIGHT dalla sidebar → chiude la sidebar e trasferisce il focus al contenuto.
                // Questo è l'UNICO trigger di uscita: chiamarlo qui in modo esplicito evita
                // qualsiasi ambiguità con i transitori di focus UP/DOWN.
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                  keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                ) {
                  returnToContent()
                  true
                } else {
                  false
                }
              },
            shape = RoundedCornerShape(50),
            focusedScale = 1.04f,
            onClick = {
              if (item.section == SidebarSection.CERCA) {
                onCollapseRequest()
              }
              onSectionSelected(item.section)
            }
          ) { isFocused ->
            val bgColor by animateColorAsState(
              targetValue = when {
                isFocused -> NovaCyan.copy(alpha = 0.25f)
                isSelected -> Color(0x3300A3FF)
                else -> Color.Transparent
              },
              label = "SidebarItemBg"
            )

            val contentColor by animateColorAsState(
              targetValue = when {
                isFocused -> NovaCyanBright
                isSelected -> NovaCyan
                else -> NovaTextSecondary
              },
              label = "SidebarItemColor"
            )

            Row(
              modifier = Modifier
                .height(48.dp)
                .background(bgColor, RoundedCornerShape(50))
                .padding(horizontal = 14.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              val label = stringResource(item.labelRes)
              Icon(
                imageVector = item.icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(24.dp)
              )

              AnimatedVisibility(
                visible = isSidebarExpanded,
                enter = fadeIn(animationSpec = tween(durationMillis = 200)),
                exit = fadeOut(animationSpec = tween(durationMillis = 150))
              ) {
                Text(
                  text = label,
                  color = if (isSelected || isFocused) NovaTextPrimary else NovaTextMuted,
                  fontSize = 14.sp,
                  fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium,
                  modifier = Modifier.padding(start = 14.dp)
                )
              }
            }
          }
        }
      }

      // Fondo della sidebar: identità del profilo attivo. Con [onProfileClick]
      // l'intero blocco è un target del D-pad: OK apre il selettore profili, così il
      // cambio utente non richiede di passare dalle Impostazioni.
      if (activeProfile != null) {
        SidebarProfileBlock(
          profile = activeProfile,
          expanded = isSidebarExpanded,
          onClick = onProfileClick,
          onReturnRequest = returnToContent
        )
      }
    }
  }
}

@Composable
private fun SidebarProfileBlock(
  profile: UserProfile,
  expanded: Boolean,
  onClick: (() -> Unit)?,
  onReturnRequest: () -> Unit
) {
  // Stesso nome localizzato della gestione profili: il principale automatico mostra
  // l'etichetta della lingua corrente finché non viene rinominato.
  val displayName = if (profile.isPrimary && !profile.hasCustomName) {
    stringResource(R.string.profiles_default_name)
  } else {
    profile.name
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(top = 4.dp)
  ) {
    Box(
      modifier = Modifier
        .padding(horizontal = 12.dp)
        .fillMaxWidth()
        .height(1.dp)
        .background(NovaDivider.copy(alpha = 0.7f))
    )
    if (onClick != null) {
      TvFocusableBox(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        focusedScale = 1.02f,
        modifier = Modifier
          .fillMaxWidth()
          // Il blocco entra nella navigazione solo a sidebar aperta: da chiusa resta
          // l'avatar informativo e il D-pad continua a muoversi tra le voci del menu.
          .focusProperties { canFocus = expanded }
          .onKeyEvent { keyEvent ->
            // RIGHT dal profilo → chiude la sidebar e torna al contenuto, come per le
            // altre voci del menu.
            if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
              keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            ) {
              onReturnRequest()
              true
            } else {
              false
            }
          }
      ) { isFocused ->
        SidebarProfileRow(
          profile = profile,
          displayName = displayName,
          expanded = expanded,
          isFocused = isFocused
        )
      }
    } else {
      SidebarProfileRow(
        profile = profile,
        displayName = displayName,
        expanded = expanded,
        isFocused = false
      )
    }
  }
}

@Composable
private fun SidebarProfileRow(
  profile: UserProfile,
  displayName: String,
  expanded: Boolean,
  isFocused: Boolean
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(
        if (isFocused) NovaCyan.copy(alpha = 0.22f) else Color.Transparent,
        RoundedCornerShape(50)
      )
      .height(44.dp)
      .padding(horizontal = 14.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    ProfileAvatarBadge(
      emoji = profile.avatarEmoji,
      initial = profile.initial,
      avatar = profile.avatar,
      size = 32.dp,
      fontSize = 14.sp
    )
    AnimatedVisibility(
      visible = expanded,
      modifier = Modifier.weight(1f),
      enter = fadeIn(animationSpec = tween(durationMillis = 200)),
      exit = fadeOut(animationSpec = tween(durationMillis = 150))
    ) {
      Text(
        text = displayName,
        color = if (isFocused) NovaCyanBright else NovaTextPrimary,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
          .fillMaxWidth()
          .padding(start = 12.dp)
      )
    }
  }
}
