package com.example.data.streaming.extractors

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressione del `StackOverflowError` sul payload packed reale di Uqload:
 * il vecchio parser usava un regex ricorsivo `(?:[^'\\]|\\.)*` che andava in
 * overflow su stringhe lunghe. Il parser lineare deve gestire payload di
 * decine di KB senza ricorsione.
 */
class JsPackedTest {

  @Test
  fun decodificaUnPayloadGrandeSenzaStackOverflow() {
    val count = 5_000
    val dictionary = (1..count).joinToString("|") { "word$it" }
    val payload = (1..count).joinToString(" ") { it.toString() }
    val block = "eval(function(p,a,c,k,e,d){return p}('$payload',10,$count,'$dictionary'.split('|'),0,{}))"

    val unpacked = JsPacked.unpack(block)

    assertNotNull("il payload grande non è stato decodificato", unpacked)
    assertTrue(unpacked!!.contains("word$count"))
  }

  @Test
  fun accettaArgomentiConVirgoletteDoppie() {
    val block = """eval(function(p,a,c,k,e,d){return p}("0:\"1\"",10,2,"file|https://cdn.example/video.mp4".split("|"),0,{}))"""

    val unpacked = JsPacked.unpack(block)

    assertNotNull(unpacked)
    assertTrue(unpacked!!.contains("""file:"https://cdn.example/video.mp4""""))
  }

  @Test
  fun gestisceEscapeNelPayload() {
    val dictionary = "file|https://cdn.example/a.mp4"
    val block = """eval(function(p,a,c,k,e,d){return p}('0:"1"',10,2,'$dictionary'.split('|'),0,{}))"""

    val unpacked = JsPacked.unpack(block)

    assertNotNull(unpacked)
    assertTrue(unpacked!!.contains("https://cdn.example/a.mp4"))
  }
}
