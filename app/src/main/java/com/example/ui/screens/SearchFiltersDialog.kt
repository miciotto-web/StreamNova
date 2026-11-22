package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.MediaType
import com.example.data.search.SearchRefinement
import com.example.data.search.SearchSort
import com.example.ui.theme.NovaCardBg
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaDivider
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary

/**
 * Pannello dei filtri della Ricerca cinematografica evoluta.
 *
 * Ogni filtro è opzionale e combinabile. Il pannello modifica una COPIA locale
 * ([draft]) e applica solo alla conferma: nessuna ricerca parte mentre l'utente
 * sta ancora scegliendo. I generi sono multi-selezione, gli altri filtri singoli.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdvancedSearchFiltersDialog(
  refinement: SearchRefinement,
  onApply: (SearchRefinement) -> Unit,
  onReset: () -> Unit,
  onDismiss: () -> Unit
) {
  var draft by remember(refinement) { mutableStateOf(refinement) }

  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = {
      TextButton(onClick = { onApply(draft) }) { Text("Applica") }
    },
    dismissButton = {
      Row {
        TextButton(onClick = onReset) { Text("Azzera") }
        TextButton(onClick = onDismiss) { Text("Annulla") }
      }
    },
    title = {
      Text(
        text = "Filtri di ricerca",
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold
      )
    },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        FilterSection("Tipo") {
          listOf(
            "Tutti" to null,
            "Film" to MediaType.FILM,
            "Serie TV" to MediaType.SERIE_TV
          ).forEachIndexed { index, (label, type) ->
            val selected = draft.type == type
            DialogFilterChip(
              label = label,
              selected = selected,
              // Il focus TV parte dal PRIMO chip del dialog: senza richiesta
              // esplicita il focus resta sul bottone del pannello dietro al
              // dialog e le voci risultano "non selezionabili".
              takeInitialFocus = index == 0,
              onClick = { draft = draft.copy(type = type) }
            )
          }
        }

        FilterSection("Generi (inclusi)") {
          GENRES.forEach { genre ->
            val selected = draft.genres.any { it.equals(genre, ignoreCase = true) }
            DialogFilterChip(
              label = genre,
              selected = selected,
              onClick = {
                draft = draft.copy(
                  genres = if (selected) {
                    draft.genres.filterNot { it.equals(genre, ignoreCase = true) }.toSet()
                  } else {
                    draft.genres + genre
                  }
                )
              }
            )
          }
        }

        FilterSection("Generi esclusi") {
          EXCLUDED_GENRES.forEach { genre ->
            val selected = draft.excludedGenres.any { it.equals(genre, ignoreCase = true) }
            DialogFilterChip(
              label = genre,
              selected = selected,
              onClick = {
                draft = draft.copy(
                  excludedGenres = if (selected) {
                    draft.excludedGenres.filterNot { it.equals(genre, ignoreCase = true) }.toSet()
                  } else {
                    draft.excludedGenres + genre
                  }
                )
              }
            )
          }
        }

        FilterSection("Anno") {
          YEAR_RANGES.forEach { (label, range) ->
            val selected = draft.yearFrom == range?.first && draft.yearTo == range?.second
            DialogFilterChip(
              label = label,
              selected = selected,
              onClick = {
                draft = draft.copy(
                  yearFrom = range?.first,
                  yearTo = range?.second
                )
              }
            )
          }
        }

        FilterSection("Voto minimo") {
          listOf(6f, 7f, 8f).forEach { rating ->
            DialogFilterChip(
              label = "$rating+",
              selected = draft.minRating == rating,
              onClick = { draft = draft.copy(minRating = rating) }
            )
          }
          DialogFilterChip(
            label = "Qualsiasi",
            selected = draft.minRating == null,
            onClick = { draft = draft.copy(minRating = null) }
          )
        }

        FilterSection("Lingua originale") {
          LANGUAGES.forEach { (code, label) ->
            DialogFilterChip(
              label = label,
              selected = if (code == null) draft.language.isNullOrBlank()
              else draft.language.equals(code, ignoreCase = true),
              onClick = { draft = draft.copy(language = code) }
            )
          }
        }

        FilterSection("Ordina per") {
          SearchSort.entries.forEach { sort ->
            DialogFilterChip(
              label = sort.label,
              selected = draft.sort == sort,
              onClick = { draft = draft.copy(sort = sort) }
            )
          }
        }

        FilterSection("Stato") {
          DialogFilterChip(
            label = "Solo mai visti",
            selected = draft.onlyUnwatched,
            onClick = { draft = draft.copy(onlyUnwatched = !draft.onlyUnwatched) }
          )
          DialogFilterChip(
            label = "Solo watchlist",
            selected = draft.onlyWatchlist,
            onClick = { draft = draft.copy(onlyWatchlist = !draft.onlyWatchlist) }
          )
        }

        Text(
          text = "Suggerimento: puoi scrivere i filtri anche nella barra, es. " +
            "\"genere:azione anno:2010-2020 voto:7 -genere:horror\" oppure " +
            "\"attore:Tom Hanks\", \"regista:Nolan\", \"saga:Harry Potter\".",
          color = NovaTextMuted,
          fontSize = 11.sp
        )
      }
    }
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(title: String, content: @Composable () -> Unit) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Text(
      text = title,
      color = NovaTextSecondary,
      fontSize = 12.sp,
      fontWeight = FontWeight.SemiBold
    )
    Spacer(modifier = Modifier.height(6.dp))
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      content()
    }
  }
}

@Composable
private fun DialogFilterChip(label: String, selected: Boolean, onClick: () -> Unit, takeInitialFocus: Boolean = false) {
  // Focus TV visibile: bordo ciano + zoom con TvFocusableBox. Un chip con il
  // solo `clickable` non mostra alcuna indicazione di focus su Android TV.
  val initialFocusRequester = remember(takeInitialFocus) { FocusRequester() }
  LaunchedEffect(takeInitialFocus) {
    if (takeInitialFocus) {
      repeat(10) {
        try {
          initialFocusRequester.requestFocus()
        } catch (_: IllegalStateException) {
          return@LaunchedEffect
        }
        kotlinx.coroutines.delay(30)
      }
    }
  }
  com.example.ui.components.TvFocusableBox(
    modifier = if (takeInitialFocus) Modifier.focusRequester(initialFocusRequester) else Modifier,
    shape = RoundedCornerShape(50),
    focusedScale = 1.07f,
    borderWidth = if (selected) 1.5.dp else 1.dp,
    unfocusedBorderColor = if (selected) NovaCyanBright else NovaDivider,
    onClick = onClick
  ) { isFocused ->
    Text(
      text = label,
      color = if (isFocused || selected) NovaCyanBright else NovaTextSecondary,
      fontSize = 12.sp,
      fontWeight = if (selected || isFocused) FontWeight.Bold else FontWeight.Medium,
      modifier = Modifier
        .background(
          when {
            isFocused -> NovaCyan.copy(alpha = 0.30f)
            selected -> NovaCyan.copy(alpha = 0.22f)
            else -> Color.White.copy(alpha = 0.06f)
          },
          RoundedCornerShape(50)
        )
        .padding(horizontal = 12.dp, vertical = 7.dp)
    )
  }
}

/**
 * Suggerimenti dalle ricerche recenti (Nova Discovery), mostrati sotto la barra
 * quando la query è vuota. Un click applica la ricerca; "Cancella" rimuove la
 * cronologia (azione esplicita).
 */
@Composable
fun SearchSuggestionChips(
  suggestions: List<String>,
  onPick: (String) -> Unit,
  onClear: () -> Unit,
  focusRequesterProvider: () -> FocusRequester
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    Text(
      text = "Ricerche recenti",
      color = NovaTextMuted,
      fontSize = 12.sp
    )
    suggestions.take(5).forEach { suggestion ->
      com.example.ui.components.TvFocusableBox(
        shape = RoundedCornerShape(50),
        focusedScale = 1.07f,
        borderWidth = 1.dp,
        unfocusedBorderColor = NovaDivider,
        onClick = { onPick(suggestion) }
      ) { isFocused ->
        Text(
          text = suggestion,
          color = if (isFocused) NovaCyanBright else NovaTextSecondary,
          fontSize = 12.sp,
          modifier = Modifier
            .background(
              if (isFocused) NovaCyan.copy(alpha = 0.30f) else NovaCardBg,
              RoundedCornerShape(50)
            )
            .padding(horizontal = 10.dp, vertical = 5.dp)
        )
      }
    }
    Spacer(modifier = Modifier.weight(1f))
    com.example.ui.components.TvFocusableBox(
      shape = RoundedCornerShape(50),
      focusedScale = 1.07f,
      borderWidth = 1.dp,
      unfocusedBorderColor = NovaDivider,
      onClick = onClear
    ) { isFocused ->
      Text(
        text = "Cancella",
        color = if (isFocused) NovaCyanBright else NovaTextMuted,
        fontSize = 11.sp,
        modifier = Modifier
          .background(
            if (isFocused) NovaCyan.copy(alpha = 0.30f) else Color.Transparent,
            RoundedCornerShape(50)
          )
          .padding(horizontal = 6.dp, vertical = 4.dp)
      )
    }
  }
}

private val GENRES = listOf(
  "Azione", "Avventura", "Animazione", "Commedia", "Crime", "Documentario",
  "Drammatico", "Famiglia", "Fantascienza", "Fantasy", "Horror", "Mistero",
  "Romantico", "Thriller", "Guerra", "Western"
)

private val EXCLUDED_GENRES = listOf(
  "Horror", "Animazione", "Documentario", "Romantico", "Guerra"
)

/** Intervalli anno preimpostati; null = nessun vincolo. */
private val YEAR_RANGES = listOf(
  "Qualsiasi" to null,
  "Novità (2023+)" to (2023 to null),
  "Anni 2020" to (2020 to 2029),
  "Anni 2010" to (2010 to 2019),
  "Anni 2000" to (2000 to 2009),
  "Classici (<2000)" to (null to 1999)
)

/** Lingue originali supportate dal filtro (codice ISO → etichetta). */
private val LANGUAGES = listOf(
  null to "Qualsiasi",
  "it" to "Italiano",
  "en" to "Inglese",
  "fr" to "Francese",
  "es" to "Spagnolo",
  "de" to "Tedesco",
  "ja" to "Giapponese",
  "ko" to "Coreano"
)
