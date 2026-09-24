package dev.erik.voiceinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SttClientContractTest {
    @Test
    fun modelIsGrokVoiceTranscribe20() {
        assertEquals("grok-voice-transcribe-2.0", GrokStt.MODEL)
    }

    @Test
    fun multipartFileFieldIsLast() {
        val files =
            listOf(
                "GrokSttClient.kt",
                "XaiOauth.kt",
            ).map { name ->
                listOf(
                    File("src/main/java/dev/erik/voiceinput/$name"),
                    File("app/src/main/java/dev/erik/voiceinput/$name"),
                ).first { it.isFile }
            }
        files.forEach { file ->
            val src = file.readText()
            val parts =
                Regex("""addFormDataPart\("([^"]+)"""")
                    .findAll(src)
                    .map { it.groupValues[1] }
                    .toList()
            assertTrue("${file.name} should post a file part", parts.contains("file"))
            assertEquals(
                "${file.name}: file field must be last",
                "file",
                parts.last(),
            )
            assertTrue("${file.name} should send model", parts.contains("model"))
            assertTrue(src.contains("GrokStt.MODEL"))
        }
    }
}
