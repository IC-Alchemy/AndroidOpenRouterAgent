package com.example.openrouteragent

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.RandomAccessFile

class OpenRouterFilesApiTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun listsWorkspaceFilesAndFollowsAnOpaqueCursorAcrossAnEmptyPage() = withServer { server, client ->
        server.enqueue(MockResponse().setBody(
            """{"data":[$uploadedMetadata],"has_more":true,"cursor":"page/+?="}"""
        ))
        server.enqueue(MockResponse().setBody("""{"data":[],"has_more":true,"cursor":"next"}"""))
        server.enqueue(MockResponse().setBody("""{"data":[],"has_more":false,"cursor":"unused"}"""))

        val first = client.listWorkspaceFiles("test-key", null)
        val empty = client.listWorkspaceFiles("test-key", first.cursor)
        val last = client.listWorkspaceFiles("test-key", empty.cursor)

        assertEquals(
            WorkspaceFileRef("or_file_uploaded", "Data/Résumé.csv", "text/csv", 11L, false, "2026-08-25T00:00:00Z"),
            first.files.single()
        )
        assertEquals("page/+?=", first.cursor)
        assertTrue(empty.files.isEmpty())
        assertEquals("next", empty.cursor)
        assertNull(last.cursor)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertEquals("/api/v1/files?limit=100", request.path)
        assertEquals("/api/v1/files?limit=100&cursor=page%2F%2B%3F%3D", server.takeRequest().path)
        assertEquals("/api/v1/files?limit=100&cursor=next", server.takeRequest().path)
    }

    @Test
    fun uploadsExactNestedFilenameAndBytesWithKnownContentLength() = withServer { server, client ->
        val bytes = "k,v\r\nx,é\r\n".encodeToByteArray()
        val localFile = temp.newFile().apply { writeBytes(bytes) }
        server.enqueue(MockResponse().setResponseCode(201).setBody(uploadedMetadata))

        val result = client.uploadWorkspaceFile("test-key", "Data/Résumé.csv", localFile)

        assertEquals("or_file_uploaded", result.id)
        assertEquals("Data/Résumé.csv", result.filename)
        assertFalse(result.downloadable)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/files", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertNull(request.getHeader("Transfer-Encoding"))
        assertEquals(request.bodySize, request.getHeader("Content-Length")?.toLong())
        val boundary = checkNotNull(request.getHeader("Content-Type"))
            .removePrefix("multipart/form-data; boundary=")
        val expected = Buffer().writeUtf8(
            "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"Data/Résumé.csv\"\r\n" +
                "Content-Type: application/octet-stream\r\nContent-Length: 11\r\n\r\n"
        ).write(bytes).writeUtf8("\r\n--$boundary--\r\n").readByteArray()
        assertArrayEquals(expected, request.body.readByteArray())
    }

    @Test
    fun rejectsEmptyAndOversizedFilesBeforeMakingARequest() = withServer { server, client ->
        val empty = temp.newFile()
        val tooLarge = temp.newFile()
        RandomAccessFile(tooLarge, "rw").use { it.setLength(104_857_601L) }
        for (file in listOf(empty, tooLarge)) {
            val failure = runCatching { client.uploadWorkspaceFile("test-key", "file.txt", file) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun validatesRelativePathsAndRejectsInvalidNamesBeforeRequestingUpload() = withServer { server, client ->
        val invalid = listOf(
            "", "/a", "a/", "a//b", "./a", "a/../b", "a/.", "x".repeat(256),
            "a<b", "a>b", "a:b", "a\"b", "a|b", "a?b", "a*b", "a\\b", "a\u0000b", "a\u007Fb"
        )
        val valid = listOf("Data/Résumé.csv", "データ/📄.txt", "Case % and #.txt", "x".repeat(255))
        val file = temp.newFile().apply { writeText("data") }
        for (name in invalid) {
            assertNotNull("Expected invalid path: $name", workspaceFilePathError(name))
            val failure = runCatching { client.uploadWorkspaceFile("test-key", name, file) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        }
        for (name in valid) {
            assertNull("Expected valid path: $name", workspaceFilePathError(name))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun promotesAContainerFileAndReadsAndDeletesTheReturnedWorkspaceFile() = withServer { server, client ->
        val promotedMetadata = """
            {"id":"or_file_saved","type":"file","filename":"out/result.txt","mime_type":"text/plain",
            "size_bytes":70000,"created_at":"2026-08-25T00:00:00Z","downloadable":true}
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(201).setBody(promotedMetadata))
        server.enqueue(MockResponse().setBody(promotedMetadata))
        val content = "x".repeat(69_999) + "\n"
        repeat(2) { server.enqueue(MockResponse().setBody(content)) }
        server.enqueue(MockResponse().setResponseCode(204))

        val saved = client.promoteContainerFile("test-key", "sess_test-1", "cfile_abc-d_E")
        assertEquals("or_file_saved", saved.id)
        assertTrue(saved.downloadable)
        assertEquals(saved, client.workspaceFile("test-key", saved.id))
        val preview = client.previewWorkspaceFile("test-key", saved.id)
        assertEquals("x".repeat(65_536), preview.text)
        assertTrue(preview.truncated)
        val download = ByteArrayOutputStream()
        client.downloadWorkspaceFile("test-key", saved.id) { download }
        assertArrayEquals(content.encodeToByteArray(), download.toByteArray())
        client.deleteWorkspaceFile("test-key", saved.id)

        val promotion = server.takeRequest()
        assertEquals("POST", promotion.method)
        assertEquals("/api/v1/containers/sess_test-1/files/cfile_abc-d_E/promote", promotion.path)
        assertEquals(0L, promotion.bodySize)
        for (path in listOf("/api/v1/files/or_file_saved", "/api/v1/files/or_file_saved/content",
            "/api/v1/files/or_file_saved/content")) {
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals(path, request.path)
        }
        val deletion = server.takeRequest()
        assertEquals("DELETE", deletion.method)
        assertEquals("/api/v1/files/or_file_saved", deletion.path)
    }

    @Test
    fun reportsWorkspaceErrorsWithoutOpeningDestinationOrDisclosingKey() = withServer { server, client ->
        server.enqueue(MockResponse().setResponseCode(400)
            .setBody("""{"error":{"message":"The uploaded file is not downloadable. Token test-key."}}"""))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(413))

        var openedDestination = false
        val download = runCatching {
            client.downloadWorkspaceFile("test-key", "or_file_uploaded") {
                openedDestination = true
                ByteArrayOutputStream()
            }
        }.exceptionOrNull()
        val deletion = runCatching { client.deleteWorkspaceFile("test-key", "or_file_missing") }.exceptionOrNull()
        val promotion = runCatching {
            client.promoteContainerFile("test-key", "sess_test", "cfile_large")
        }.exceptionOrNull()

        assertFalse(openedDestination)
        assertTrue(download is IOException && download.message.orEmpty().contains("HTTP 400"))
        assertTrue(download?.message.orEmpty().contains("The uploaded file is not downloadable."))
        assertFalse(download?.message.orEmpty().contains("test-key"))
        assertTrue(deletion is IOException && deletion.message.orEmpty().contains("HTTP 404"))
        assertTrue(promotion is IOException && promotion.message.orEmpty().contains("HTTP 413"))
    }

    @Test
    fun rejectsInvalidFileIdentitiesBeforeRequestingContent() = withServer { server, client ->
        for (id in listOf("cfile_other", "or_file_", "or_file_abc/..", "or_file_abc%2Fsecret", "..")) {
            val failure = runCatching { client.previewWorkspaceFile("test-key", id) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        }
        val badPromotion = runCatching {
            client.promoteContainerFile("test-key", "..", "cfile_abc")
        }.exceptionOrNull()
        assertTrue(badPromotion is IllegalArgumentException)
        assertEquals(0, server.requestCount)
    }

    private fun withServer(block: suspend (MockWebServer, OpenRouterAgentClient) -> Unit) {
        MockWebServer().use { server ->
            runBlocking { block(server, OpenRouterAgentClient(server.url("/api/v1/"))) }
        }
    }

    private val uploadedMetadata = """
        {"id":"or_file_uploaded","type":"file","filename":"Data/Résumé.csv","mime_type":"text/csv",
        "size_bytes":11,"created_at":"2026-08-25T00:00:00Z","downloadable":false}
    """.trimIndent()
}
