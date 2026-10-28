package com.example.ui.screens.player

import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.ChapterTocFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame

/**
 * Modello di un capitolo video estratto dai metadati del contenitore (MKV / MP4 / ID3).
 *
 * @property id Identificatore univoco del capitolo all'interno del flusso.
 * @property title Titolo opzionale (es. "Intro", "Scena 1", etc.).
 * @property startMs Posizione iniziale in millisecondi.
 * @property endMs Posizione finale opzionale in millisecondi.
 */
data class PlayerChapter(
  val id: String,
  val title: String?,
  val startMs: Long,
  val endMs: Long? = null
)

/**
 * Utility per l'estrazione e navigazione rapida dei capitoli da istanze [Player] Media3.
 */
object PlayerChapterUtils {

  /**
   * Estrae il titolo leggibile da un [ChapterFrame] se presente tra i subFrames.
   */
  private fun extractChapterTitle(frame: ChapterFrame): String? {
    for (k in 0 until frame.subFrameCount) {
      val sub = frame.getSubFrame(k)
      if (sub is TextInformationFrame) {
        val candidate = sub.values.firstOrNull()
        if (!candidate.isNullOrBlank()) return candidate
      }
    }
    return null
  }

  /**
   * Estrae tutti i capitoli disponibili da [player.currentTracks] e dai relativi metadati ID3/container.
   */
  fun extractChapters(player: Player): List<PlayerChapter> {
    val chapters = mutableListOf<PlayerChapter>()
    val tracks = player.currentTracks

    for (group in tracks.groups) {
      for (i in 0 until group.length) {
        val format = group.getTrackFormat(i)
        val metadata = format.metadata ?: continue
        for (j in 0 until metadata.length()) {
          when (val entry = metadata.get(j)) {
            is ChapterFrame -> {
              val title = extractChapterTitle(entry)
              chapters.add(
                PlayerChapter(
                  id = entry.chapterId,
                  title = title,
                  startMs = entry.startTimeMs.toLong(),
                  endMs = if (entry.endTimeMs > entry.startTimeMs) entry.endTimeMs.toLong() else null
                )
              )
            }
            is ChapterTocFrame -> {
              for (k in 0 until entry.subFrameCount) {
                val sub = entry.getSubFrame(k)
                if (sub is ChapterFrame) {
                  val title = extractChapterTitle(sub)
                  chapters.add(
                    PlayerChapter(
                      id = sub.chapterId,
                      title = title,
                      startMs = sub.startTimeMs.toLong(),
                      endMs = if (sub.endTimeMs > sub.startTimeMs) sub.endTimeMs.toLong() else null
                    )
                  )
                }
              }
            }
          }
        }
      }
    }

    return chapters.distinctBy { it.startMs }.sortedBy { it.startMs }
  }

  /**
   * Calcola la posizione target per il salto al capitolo successivo (D-Pad UP).
   * Restituisce null se non ci sono capitoli successivi.
   */
  fun getNextChapterPosition(chapters: List<PlayerChapter>, currentPositionMs: Long): Long? {
    if (chapters.isEmpty()) return null
    return chapters.firstOrNull { it.startMs > currentPositionMs + 500L }?.startMs
  }

  /**
   * Calcola la posizione target per il salto al capitolo precedente o inizio del corrente (D-Pad DOWN).
   * - Se ci si trova nei primi 3 secondi (<= 3000ms) dall'inizio del capitolo corrente: salta al capitolo precedente (o a 0L).
   * - Altrimenti: salta all'inizio del capitolo corrente.
   */
  fun getPreviousOrCurrentChapterPosition(chapters: List<PlayerChapter>, currentPositionMs: Long): Long? {
    if (chapters.isEmpty()) return null
    val currentIndex = chapters.indexOfLast { it.startMs <= currentPositionMs }
    if (currentIndex < 0) return 0L

    val currentChapter = chapters[currentIndex]
    return if (currentPositionMs - currentChapter.startMs <= 3_000L) {
      val prevIndex = currentIndex - 1
      if (prevIndex >= 0) chapters[prevIndex].startMs else 0L
    } else {
      currentChapter.startMs
    }
  }
}
