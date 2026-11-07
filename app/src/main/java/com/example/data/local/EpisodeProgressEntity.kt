package com.example.data.local

import androidx.room.Entity

/**
 * Entità Room per la persistenza del progresso di riproduzione dei singoli episodi.
 * La chiave primaria composita (mediaId, seasonNumber, episodeNumber) garantisce che
 * ogni episodio di qualsiasi serie conservi la propria posizione indipendente.
 */
@Entity(
  tableName = "episode_progress",
  primaryKeys = ["mediaId", "seasonNumber", "episodeNumber"]
)
data class EpisodeProgressEntity(
  val mediaId: String,
  val seasonNumber: Int,
  val episodeNumber: Int,
  val progressMs: Long = 0L,
  val durationMs: Long = 0L,
  val updatedAt: Long = System.currentTimeMillis(),
  /**
   * Provenienza della posizione (`MEDIA_SOURCE_SIMKL` = adottata dalla sincronizzazione):
   * `null` = scritta dal locale (player o dati storici), MAI rimossa dal logout SIMKL.
   */
  val source: String? = null
)
