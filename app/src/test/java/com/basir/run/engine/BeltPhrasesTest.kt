package com.basir.run.engine

import com.basir.run.belt.BeltState
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import run.basir.engine.BeltLanguage
import run.basir.engine.Motor

/** Everything the belt page and the belt link say has a recorded phrase (Phrase.forText), so it is heard in the recorded voice. */
class BeltPhrasesTest {
    @Test
    fun everyBeltCueMeaningHasARecordedPhrase() {
        for (p in BeltLanguage().catalog()) assertNotNull(p.meaningAr, Phrase.forText(p.meaningAr))
    }

    @Test
    fun everyLinkStateAndMotorNameHasARecordedPhrase() {
        for (s in BeltState.values()) assertNotNull(s.ar, Phrase.forText(s.ar))
        for (m in Motor.values()) "المحرك ${m.ordinal + 1}، ${m.arabic}".let { assertNotNull(it, Phrase.forText(it)) }
    }

    @Test
    fun beltPhrasesHaveTashkeelForRecording() {
        val tsv = java.io.File("../docs/voice/phrases_tashkeel.tsv").takeIf { it.exists() } ?: java.io.File("docs/voice/phrases_tashkeel.tsv")
        val ids = tsv.readLines().drop(1).map { it.substringBefore('\t') }.toSet()
        val missing = Phrase.values().filter { it.id.startsWith("belt_") && it.id !in ids }
        assertTrue("no tashkeel for: ${missing.map { it.id }}", missing.isEmpty())
    }
}
