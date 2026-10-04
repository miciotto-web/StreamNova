package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R

/**
 * Card piattaforma a dimensioni fisse 200x112dp (16:9 orizzontale, stile TV banner).
 *
 * Struttura a Box singolo:
 * - scala di focus animata senza alterare il layout,
 * - bordo ciano disegnato esattamente sul perimetro della card (nessun artefatto),
 * - gradiente di brand sullo sfondo,
 * - logo ufficiale orizzontale e trasparente centrato (mai l'icona quadrata dell'app).
 */
@Composable
fun ProviderCard(
    provider: StreamingProvider,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.06f else 1.0f,
        animationSpec = tween(durationMillis = 200),
        label = "scale"
    )
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .width(200.dp)
            .height(112.dp)
            // La card focused deve coprire le card vicine, non venire coperta
            .zIndex(if (isFocused) 10f else 1f)
            .scale(scale)
            .onFocusChanged { isFocused = it.isFocused }
            // OK/Enter della tastiera D-pad -> onClick (consumato prima di clickable)
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
            // Cornice aderente al perimetro della card (stroke disegnato dentro i bounds)
            .border(
                width = if (isFocused) 2.5.dp else 1.dp,
                color = if (isFocused) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.08f),
                shape = shape
            )
            .clip(shape)
            .background(getProviderBrush(provider))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        val logoModel = getProviderLogoUrlOrResource(provider)
        val logoModifier = Modifier
            .fillMaxWidth(0.70f)
            .height(getProviderLogoHeight(provider))

        when {
            logoModel is Int -> Image(
                painter = painterResource(id = logoModel),
                contentDescription = provider.name,
                contentScale = ContentScale.Fit,
                modifier = logoModifier
            )
            logoModel != null -> AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(logoModel)
                    .crossfade(true)
                    .build(),
                contentDescription = provider.name,
                contentScale = ContentScale.Fit,
                modifier = logoModifier
            )
        }
    }
}

/**
 * Logo orizzontale trasparente del brand (drawable locale) con fallback sull'URL TMDB.
 * Non usa mai l'icona quadrata dell'app (`logoResId`): ha sfondo bianco/nero incorporato.
 */
private fun getProviderLogoUrlOrResource(provider: StreamingProvider): Any? {
    return when (provider.tmdbProviderId) {
        8 -> R.drawable.logo_provider_netflix
        9, 119 -> R.drawable.logo_provider_prime
        337 -> R.drawable.logo_provider_disney
        384, 49, 1899 -> R.drawable.logo_provider_hbomax
        350 -> R.drawable.logo_provider_appletv
        531 -> R.drawable.logo_provider_paramount
        283 -> R.drawable.logo_provider_crunchyroll
        else -> provider.logoUrl
    }
}

/**
 * Altezza del riquadro logo: i wordmark molto larghi (Netflix/Prime) restano bassi,
 * i loghi più compatti (Disney+ con arco, HBO Max) più alti. Il tutto con ContentScale.Fit.
 */
private fun getProviderLogoHeight(provider: StreamingProvider): Dp {
    return when (provider.tmdbProviderId) {
        337 -> 56.dp
        384, 49, 1899 -> 50.dp
        350 -> 44.dp
        else -> 40.dp
    }
}

/**
 * Sfondo a gradiente radiale/lineare del brand, come da riferimento grafico.
 */
private fun getProviderBrush(provider: StreamingProvider): Brush {
    return when (provider.tmdbProviderId) {
        // Netflix: radiale scuro carbone
        8 -> Brush.radialGradient(
            listOf(
                Color(0xFF1A1A1A),
                Color(0xFF050505)
            )
        )
        // Prime Video: radiale blu centrale
        9, 119 -> Brush.radialGradient(
            listOf(
                Color(0xFF003A70),
                Color(0xFF051426),
                Color(0xFF020710)
            )
        )
        // Disney+: radiale blu navy
        337 -> Brush.radialGradient(
            listOf(
                Color(0xFF0C244D),
                Color(0xFF041021),
                Color(0xFF01060E)
            )
        )
        // HBO Max: radiale violaceo
        384, 49, 1899 -> Brush.radialGradient(
            listOf(
                Color(0xFF320C57),
                Color(0xFF150526),
                Color(0xFF08020F)
            )
        )
        // Apple TV: lineare lilla/violaceo
        350 -> Brush.linearGradient(
            listOf(
                Color(0xFF4A4066),
                Color(0xFF251F33),
                Color(0xFF181421)
            )
        )
        // Paramount+: radiale blu
        531 -> Brush.radialGradient(
            listOf(
                Color(0xFF0055B8),
                Color(0xFF00224D),
                Color(0xFF030A14)
            )
        )
        // Crunchyroll: radiale arancione
        283 -> Brush.radialGradient(
            listOf(
                Color(0xFFF47521),
                Color(0xFF5E2B06),
                Color(0xFF140A04)
            )
        )
        else -> Brush.radialGradient(
            listOf(
                Color(0xFF2B2D30),
                Color(0xFF141517)
            )
        )
    }
}
