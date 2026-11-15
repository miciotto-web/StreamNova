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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Card STUDIO dell'hub «Studios» della Home (sotto l'hub dei provider).
 *
 * Stessa architettura di [ProviderCard] (dimensioni fisse 200x112dp, scala di focus animata,
 * bordo ciano sul perimetro) ma con l'immagine di brand dello studio: gradiente perimetrato
 * dai colori ufficiali ([StudioDefinition.headerBrush]) e LOGO ORIGINALE del brand centrato,
 * senza alcun filtro colore. Il logo mantiene il rapporto d'aspetto nativo (wordmark larghi
 * restano bassi, loghi compatti più alti) con [ContentScale.Fit].
 */
@Composable
fun StudioCard(
    studio: StudioDefinition,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    /** true quando questa card è il bersaglio del ripristino focus al ritorno dalla scheda Studio. */
    isRestoreTarget: Boolean = false,
    /** Invocato quando la card riceve DAVVERO il focus ed è il bersaglio del ripristino. */
    onRestoreFocusConfirmed: (() -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val internalFocusRequester = remember { FocusRequester() }
    val effectiveFocusRequester = focusRequester ?: internalFocusRequester
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.06f else 1.0f,
        animationSpec = tween(durationMillis = 200),
        label = "studioScale"
    )
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .width(200.dp)
            .height(112.dp)
            .focusRequester(effectiveFocusRequester)
            // La card focused deve coprire le card vicine, non venire coperta
            .zIndex(if (isFocused) 10f else 1f)
            .scale(scale)
            .onFocusChanged {
                isFocused = it.isFocused
                // Rilascio del target di ripristino SOLO all'evento reale di focus (stesso
                // protocollo di [ProviderCard]): il click su una card già focalizzata non
                // rifà scattare onFocusChanged, quindi il target sopravvive alla navigazione.
                if (it.isFocused && isRestoreTarget) onRestoreFocusConfirmed?.invoke()
            }
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
            .background(studio.headerBrush)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = studio.logoResId),
            contentDescription = studio.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .height(studioLogoHeight(studio))
                .padding(horizontal = 18.dp, vertical = 0.dp)
        )
    }
}

/**
 * Altezza del riquadro logo dentro la card: i wordmark stretti e alti (DC, Warner) e i
 * monumenti (20th Century Fox) hanno più respiro verticale dei wordmark orizzontali.
 * Con [ContentScale.Fit] l'immagine non esce mai dal riquadro né si deforma.
 */
private fun studioLogoHeight(studio: StudioDefinition): Dp {
    return when (studio.id) {
        "dc", "warner", "twentieth_century_fox", "dreamworks", "ghibli", "universal", "amc", "fx" -> 84.dp
        "disney", "lucasfilm", "legendary", "a24", "marvel" -> 72.dp
        else -> 58.dp
    }
}
