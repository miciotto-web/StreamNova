package com.example.data.local

import androidx.room.Entity

/**
 * Progresso di paginazione del catalogo provider TMDB.
 *
 * Ogni riga indica la PROSSIMA pagina da scaricare per una coppia
 * (provider, tipo) e il `total_pages` dichiarato da TMDB, così il crawl
 * può riprendere esattamente da dove si era interrotto anche dopo la
 * chiusura dell'app.
 *
 * Chiave composita (providerId, mediaType): la paginazione di Netflix Film
 * non può in nessun modo bloccare Netflix Serie TV, Disney+, HBO, Prime o Apple.
 *  - providerId: identificativo esposto da ProviderConstants (8, 384, 337, 119, 350)
 *  - mediaType : "movie" | "tv"
 */
@Entity(tableName = "catalog_progress", primaryKeys = ["providerId", "mediaType"])
data class CatalogProgressEntity(
  val providerId: Int,
  val mediaType: String,
  /** Prossima pagina da richiedere a TMDB (1-based). */
  val nextPage: Int,
  /** `total_pages` restituito da TMDB (0 = ancora sconosciuto). */
  val totalPages: Int,
  val updatedAt: Long = System.currentTimeMillis()
)
