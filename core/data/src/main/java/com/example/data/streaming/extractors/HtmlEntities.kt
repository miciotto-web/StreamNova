package com.example.data.streaming.extractors

/**
 * Decodifica **singola** delle entità HTML usate negli attributi URL.
 *
 * Interessa solo l'ampere commerciale e le sue forme numeriche: sono le uniche
 * che alterano una query string (`?a=1&amp;b=2`). Una sola passata evita la
 * doppia decodifica: `&amp;amp;` diventa `&amp;`, non `&`.
 */
internal object HtmlEntities {

  private val AMPERSAND = Regex("""&(?:amp|#0*38|#x0*26);""", RegexOption.IGNORE_CASE)

  fun decodeAttribute(value: String): String =
    AMPERSAND.replace(value) { "&" }
}
