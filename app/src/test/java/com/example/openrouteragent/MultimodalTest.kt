package com.example.openrouteragent

import android.content.Intent
import android.net.Uri
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.util.Base64

class MultimodalTest {
    @get:Rule val folder = TemporaryFolder()

    private val catalog = """
        {"data":[
          {"id":"vendor/vision","name":"Vision","context_length":200000,"supported_parameters":["tools"],
           "architecture":{"input_modalities":["text","image","file"],"output_modalities":["text"]},
           "pricing":{"prompt":"0.000003","completion":"0.000015","image":"0.0048"},
           "expiration_date":"2026-12-01"},
          {"id":"vendor/painter","name":"Painter","context_length":32000,
           "architecture":{"input_modalities":["text","image"],"output_modalities":["image","text"]},
           "pricing":{"prompt":"0","completion":"0"}},
          {"id":"vendor/router","name":"Router","pricing":{"prompt":"-1","completion":"-1"}}
        ]}
    """.trimIndent()

    @Test
    fun theCatalogCarriesModalitiesPricesAndRetirement() {
        val (vision, painter, router) = parseModelCatalog(catalog)
        assertEquals(setOf("text", "image", "file"), vision.inputModalities)
        assertTrue(vision.readsImages && vision.readsFiles && !vision.readsAudio && !vision.makesImages)
        assertEquals(listOf("Vision", "PDF", "Tools"), capabilityLabels(vision))
        assertEquals("$3 in · $15 out per 1M tokens", priceLabel(vision.pricing))
        assertEquals("Retires on 2026-12-01", retirementLabel(vision, LocalDate.of(2026, 11, 24)))
        assertNull(retirementLabel(vision, LocalDate.of(2026, 6, 1)))
        assertEquals("Retired on 2026-12-01", retirementLabel(vision, LocalDate.of(2027, 1, 1)))
        assertTrue(painter.makesImages)
        assertEquals("Free", priceLabel(painter.pricing))
        // A router's price varies, and a model with no modalities is treated as able to try anything.
        assertNull(router.pricing)
        assertFalse(router.modalitiesKnown)
        assertTrue(router.accepts("image"))
    }

    @Test
    fun attachmentsAreCheckedAgainstTheModel() {
        val textOnly = CatalogModel("a/text", "Text model", 0, emptySet(), inputModalities = setOf("text"))
        val photo = ChatMedia("x", MEDIA_IMAGE, "image/jpeg", "cat.jpg", 1000)
        val pdf = ChatMedia("y", MEDIA_FILE, "application/pdf", "doc.pdf", 1000)
        assertEquals("Text model can't read images. Choose a model marked Vision, or remove the photo.",
            attachmentProblem(listOf(photo), textOnly))
        // PDFs go through OpenRouter's parser when the model can't read them.
        assertNull(attachmentProblem(listOf(pdf), textOnly))
        assertNotNull(attachmentProblem(List(9) { pdf }, null))
        assertNotNull(attachmentProblem(listOf(pdf.copy(bytes = 9L * 1024 * 1024)), null))
        assertEquals(MEDIA_IMAGE, attachmentKind("image/heic", "IMG.heic"))
        assertEquals(MEDIA_FILE, attachmentKind("application/octet-stream", "Report.PDF"))
        assertEquals(MEDIA_AUDIO, attachmentKind("audio/x-wav", "memo.wav"))
        assertEquals(MEDIA_TEXT, attachmentKind("application/octet-stream", "main.kt"))
        assertNull(attachmentKind("application/zip", "archive.zip"))
    }

    @Test
    fun mediaIsStoredOncePerChatAndRemovedWithIt() {
        val store = ChatMediaStore(folder.root)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3)
        val draft = store.store(store.drafts, png, "image/png")
        assertTrue(isMediaFileName(draft) && draft.endsWith(".png"))
        assertEquals(draft, store.store(store.drafts, png, "image/png"))
        assertEquals(draft, store.find("chat-1", draft)?.name)
        val media = ChatMedia(draft, MEDIA_IMAGE, "image/png", "dot.png", png.size.toLong())
        store.adopt("chat-1", listOf(media))
        store.discardDrafts(listOf(media))
        assertEquals(store.chatDir("chat-1"), store.find("chat-1", draft)?.parentFile)
        assertNull(store.find("chat-2", draft))
        assertNull(store.find("chat-1", "../secret.png"))
        assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(png), store.encoded("chat-1", draft, true))

        // Images the model makes arrive as base64, sometimes with a wrong or missing type.
        val made = store.storeEncoded("chat-1", Base64.getEncoder().encodeToString(png), dataUrl = false)
        assertEquals(draft, made)
        assertEquals(made, store.storeEncoded("chat-1", "data:image/jpeg;base64," +
            Base64.getEncoder().encodeToString(png), dataUrl = true))
        assertNull(store.storeEncoded("chat-1", "data:text/plain;base64,aGVsbG8=", dataUrl = true))
        assertNull(store.storeEncoded("chat-1", "not base64 at all!", dataUrl = false))
        store.deleteChat("chat-1")
        assertNull(store.find("chat-1", draft))
        assertEquals("", store.encoded("chat-1", draft, true))
    }

    @Test
    fun runOptionsListAttachmentsAndModelModalities() {
        val model = CatalogModel("vendor/vision", "Vision", 0, setOf("tools"), setOf("text", "image"), setOf("text"))
        val photo = ChatMedia("a".repeat(64) + ".jpg", MEDIA_IMAGE, "image/jpeg", "cat.jpg", 10)
        val note = ChatMedia("b".repeat(64) + ".txt", MEDIA_TEXT, "text/plain", "todo.txt", 8)
        val options = runtimeOptions(AgentConfig(apiKey = "k", model = model.id), Workspace(), "", model,
            files = OpenRouterRunFiles(media = listOf(RunMedia(photo), RunMedia(note, "buy milk"))))
        val attachments = options.getJSONArray("attachments")
        assertEquals("image", attachments.getJSONObject(0).getString("kind"))
        assertEquals(photo.file, attachments.getJSONObject(0).getString("file"))
        assertFalse(attachments.getJSONObject(0).has("text"))
        assertEquals("buy milk", attachments.getJSONObject(1).getString("text"))
        val details = options.getJSONObject("modelDetails").getJSONObject(model.id)
        assertEquals("image", details.getJSONArray("input").getString(1))
        assertEquals("text", details.getJSONArray("output").getString(0))
    }

    @Test
    fun oldChatsWithoutMediaStillOpen() {
        val old = """{"id":"a","messages":[{"id":"m","isUser":true,"text":"hi"}],
            "interruptedTurn":{"prompt":"p","reply":"r","reasoningSummary":"","error":null}}"""
        val workspace = Json { ignoreUnknownKeys = true }.decodeFromString(Workspace.serializer(), old)
        assertTrue(workspace.messages.single().media.isEmpty())
        assertTrue(workspace.interruptedTurn!!.media.isEmpty())
        val withPhoto = workspace.copy(messages = listOf(ChatEntry(isUser = true, text = "",
            media = listOf(ChatMedia("f", MEDIA_IMAGE, "image/jpeg", "beach.jpg")))), interruptedTurn = null)
        assertEquals("beach.jpg", chatTitle(withPhoto))
    }
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class ShareIntentTest {
    @Test
    fun sharesKeepTextAndOtherAppsContentOnly() {
        val photo = Uri.parse("content://com.example.gallery/photo/1")
        val own = Uri.parse("content://com.icalchemy.openrouteragent.files/shared/x.jpg")
        val file = Uri.parse("file:///data/data/com.icalchemy.openrouteragent/no_backup/conversations.db")
        val multiple = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(photo, own, file, photo))
        assertEquals(null to listOf(photo), sharedContent(multiple, "com.icalchemy.openrouteragent.files"))

        val text = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Article").putExtra(Intent.EXTRA_TEXT, "https://example.com/story")
        assertEquals("Article\n\nhttps://example.com/story" to emptyList<Uri>(), sharedContent(text, "own"))
        assertNull(sharedContent(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, file), "own"))
        assertNull(sharedContent(Intent(Intent.ACTION_VIEW), "own"))
    }
}
