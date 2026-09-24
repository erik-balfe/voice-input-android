package dev.erik.voiceinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationMarkTest {
    @Test
    fun builtInDefaultsMatchSpec() {
        assertEquals("надиктовано, распознано моделью", DictationMark.defaultPhrase("ru"))
        assertEquals("dictated, transcribed by a model", DictationMark.defaultPhrase("en"))
        assertEquals("айтылып, модель таныды", DictationMark.defaultPhrase("kk"))
        assertEquals("айтылып, модель тааныды", DictationMark.defaultPhrase("ky"))
        assertEquals("diktiert, vom Modell erkannt", DictationMark.defaultPhrase("de"))
        assertEquals("dicté, transcrit par un modèle", DictationMark.defaultPhrase("fr"))
        assertEquals("dictado, transcrito por un modelo", DictationMark.defaultPhrase("es"))
        assertEquals("dikte, model tanıdı", DictationMark.defaultPhrase("tr"))
        assertEquals("口述，模型转写", DictationMark.defaultPhrase("zh"))
        assertEquals("إملاء، تعرّف عليه نموذج", DictationMark.defaultPhrase("ar"))
    }

    @Test
    fun unknownLanguageFallsBackToEnglish() {
        assertEquals(
            DictationMark.defaultPhrase("en"),
            DictationMark.defaultPhrase("pt"),
        )
        assertEquals(
            DictationMark.defaultPhrase("en"),
            DictationMark.defaultPhrase(""),
        )
    }

    @Test
    fun regionalTagsUsePrimaryLanguage() {
        assertEquals(DictationMark.defaultPhrase("zh"), DictationMark.defaultPhrase("zh-CN"))
        assertEquals(DictationMark.defaultPhrase("en"), DictationMark.defaultPhrase("en_US"))
        assertEquals(DictationMark.defaultPhrase("ru"), DictationMark.defaultPhrase("ru-RU"))
    }

    @Test
    fun unreadFieldInsertsAtCursorAfterBlankLine() {
        val plan = DictationMark.plan(fieldText = null, phrase = "mark")
        assertFalse(plan.moveToEnd)
        assertEquals("\n\nmark", plan.text)
    }

    @Test
    fun emptyFieldInsertsPhraseAtEnd() {
        val plan = DictationMark.plan(fieldText = "", phrase = "mark")
        assertTrue(plan.moveToEnd)
        assertEquals("mark", plan.text)
    }

    @Test
    fun contentGetsBlankLineThenPhraseAtEnd() {
        val plan = DictationMark.plan(fieldText = "hello", phrase = "mark")
        assertTrue(plan.moveToEnd)
        assertEquals("\n\nmark", plan.text)
    }

    @Test
    fun existingTrailingNewlinesDoNotStackExtraBlanks() {
        assertEquals(
            "\nmark",
            DictationMark.plan(fieldText = "hello\n", phrase = "mark").text,
        )
        assertEquals(
            "mark",
            DictationMark.plan(fieldText = "hello\n\n", phrase = "mark").text,
        )
        assertEquals(
            "mark",
            DictationMark.plan(fieldText = "hello\n\n\n", phrase = "mark").text,
        )
    }

    @Test
    fun autoAppendAfterTranscriptionUsesSameBlankLineSuffix() {
        val afterInsert = "spoken words "
        val plan = DictationMark.plan(fieldText = afterInsert, phrase = "mark")
        assertEquals("\n\nmark", plan.text)
    }
}
