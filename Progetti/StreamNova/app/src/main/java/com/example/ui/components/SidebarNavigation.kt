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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.R
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
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
 */
@Composable
fun SidebarNavigation(
  currentSection: SidebarSection,
  onSectionSelected: (SidebarSection) -> Unit,
  isSidebarExpanded: Boolean,
  onCollapseRequest: () -> Unit,
  modifier: Modifier = Modifier
) {
  val focusManager = LocalFocusManager.current

  // L'animazione parte già dallo stato corretto (60dp) perché isSidebarExpanded
  // è inizializzato a false dal chiamante prima del primo frame.
  val sidebarWidth by animateDpAsState(
    targetValue = if (isSidebarExpanded) 220.dp else 60.dp,
    animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
    label = "SidebarWidth"
  )

  val menuItems = sidebarMenuItems()

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
      // Logo & App Name
      Column(
        modifier = Modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.Start
      ) {
        Box(
          modifier = Modifier.size(44.dp),
          contentAlignment = Alignment.Center
        ) {
          Image(
            painter = painterResource(id = R.drawable.ic_streamnova_logo),
            contentDescription = "StreamNova Logo",
            modifier = Modifier.size(44.dp),
            contentScale = ContentScale.Fit
          )
        }

        AnimatedVisibility(
          visible = isSidebarExpanded,
          enter = fadeIn(animationSpec = tween(durationMillis = 200)),
          exit = fadeOut(animationSpec = tween(durationMillis = 150))
        ) {
          StreamNovaWordmark(
            modifier = Modifier.padding(top = 8.dp),
            fontSize = 18.sp
          )
        }
      }

      Spacer(modifier = Modifier.height(24.dp))

      // Navigation Items
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        menuItems.forEach { item ->
          val isSelected = currentSection == item.section

          TvFocusableBox(
            modifier = Modifier
              .height(48.dp)
              .focusProperties {
                canFocus = isSidebarExpanded || currentSection != SidebarSection.CERCA
              }
              .onKeyEvent { keyEvent ->
                // RIGHT dalla sidebar → chiude la sidebar e sposta il focus verso Home.
                // Questo è l'UNICO trigger di onCollapseRequest: chiamarlo qui in modo
                // esplicito evita qualsiasi ambiguità con i transitori di focus UP/DOWN.
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                  keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                ) {
                  onCollapseRequest()
                  focusManager.moveFocus(FocusDirection.Right)
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
    }
  }
}
