package com.example.openrouteragent

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitHubProjectTest {
    @get:Rule val temp = TemporaryFolder()
    private val credentials = object : GitHubCredentials {
        private val values = mutableMapOf<String, String>()
        override fun read(id: String) = values[id].orEmpty()
        override fun save(id: String, token: String) { values[id] = token }
    }

    @Test fun listingFetchesOnlyMetadataAndDownloadedTextWorksAfterRestart() = runBlocking {
        MockWebServer().use { server ->
            enqueueConnection(server)
            server.enqueue(json("""{"truncated":false,"tree":[
                {"path":"README.md","mode":"100644","type":"blob","size":4,"sha":"$blob"},
                {"path":"app.apk","mode":"100644","type":"blob","size":17663292,"sha":"$apk"}
            ]}"""))
            val root = temp.newFolder()
            val client = GitHubClient(server.url("/"))
            val service = GitHubProjects(root, credentials, client)
            service.connect("p", "https://github.com/acme/tools", "github_pat_fixture", "")
            val listing = service.list("p")
            assertEquals(listOf("app.apk", "README.md"), listing.entries.map { it.path })
            assertEquals(5, server.requestCount)
            assertFalse(listing.entries.first().canRead)
            runCatching { service.read("p", "app.apk") }.onSuccess { fail("Large file was downloaded") }
            assertEquals(5, server.requestCount)
            server.enqueue(MockResponse().setBody("old\n"))
            assertEquals("old\n", service.read("p", "README.md"))
            assertEquals("old\n", GitHubProjects(root, credentials, client).read("p", "README.md"))
            assertEquals(6, server.requestCount)
        }
    }

    @Test fun truncatedTreeLoadsSubdirectoriesAndLabelsIncompleteSearch() = runBlocking {
        MockWebServer().use { server ->
            enqueueConnection(server)
            server.enqueue(json("""{"truncated":true,"tree":[]}"""))
            server.enqueue(json("""{"truncated":false,"tree":[{"path":"src","mode":"040000","type":"tree","sha":"$subtree"}]}"""))
            val service = GitHubProjects(temp.newFolder(), credentials, GitHubClient(server.url("/")))
            service.connect("p", "https://github.com/acme/tools", "github_pat_fixture", "")
            assertEquals("Current folder", service.list("p", query = "main").scope)
            server.enqueue(json("""{"truncated":false,"tree":[{"path":"Main.kt","mode":"100644","type":"blob","size":4,"sha":"$blob"}]}"""))
            assertEquals(listOf("src/Main.kt"), service.list("p", "src", "main").entries.map { it.path })
            assertEquals(7, server.requestCount)
        }
    }

    @Test fun aDeclinedOrStaleReviewSendsNoWrites() = runBlocking {
        MockWebServer().use { server ->
            val service = connected(server)
            service.enableAgent("p", true)
            val access = checkNotNull(service.session("p"))
            service.create("p", "notes.md", "original", access)
            val denied = runCatching {
                service.performAction(access, "github_publish", JSONObject().put("message", "Update notes")) { review, _ ->
                    assertEquals("notes.md", review.drafts.single().path)
                    throw IllegalStateException("Denied")
                }
            }
            assertTrue(denied.isFailure)
            val review = service.preparePublish("p", "Update notes", access)
            service.edit("p", "notes.md", "original", "updated", access)
            assertTrue(runCatching { service.submit(review, access) }.isFailure)
            assertEquals("updated", service.read("p", "notes.md", access))
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun publishingSendsOneMultiFileCommitAndOpeningPrIsSeparate() = runBlocking {
        MockWebServer().use { server ->
            val service = connected(server)
            service.create("p", "a.txt", "one")
            service.create("p", "b.txt", "two")
            val review = service.preparePublish("p", "Add notes")
            enqueuePublish(server)
            server.enqueue(json("""{"data":{"updateRefs":{"clientMutationId":"$candidate"}}}"""))
            assertEquals("https://github.com/acme/tools/commit/$candidate", service.submit(review))
            assertEquals(emptyList<GitHubDraft>(), service.project("p")?.drafts)
            assertEquals(11, server.requestCount)
            val recorded = List(11) { server.takeRequest() }
            val writes = recorded.filter { it.method == "POST" }
            assertEquals(listOf("/repos/acme/tools/git/trees", "/repos/acme/tools/git/commits", "/graphql"),
                writes.map { it.path })
            val treeWrite = JSONObject(writes[0].body.readUtf8())
            assertEquals(tree, treeWrite.getString("base_tree"))
            assertEquals(2, treeWrite.getJSONArray("tree").length())
            val ref = JSONObject(writes[2].body.readUtf8()).getJSONObject("variables").getJSONObject("input")
                .getJSONArray("refUpdates").getJSONObject(0)
            assertEquals("refs/heads/${review.branch}", ref.getString("name"))
            assertEquals("0".repeat(40), ref.getString("beforeOid"))
            assertFalse(ref.getBoolean("force"))

            val pr = service.preparePr("p", "Add notes", "Two files")
            assertEquals(11, server.requestCount)
            enqueueRepository(server)
            server.enqueue(json("[]"))
            server.enqueue(refResponse(candidate))
            val pullUrl = "https://github.com/acme/tools/pull/7"
            server.enqueue(json("""{"html_url":"$pullUrl"}"""))
            assertEquals(pullUrl, service.submit(pr))
            val prRequests = List(4) { server.takeRequest() }
            val payload = JSONObject(prRequests.last().body.readUtf8())
            assertEquals("main", payload.getString("base"))
            assertEquals(review.branch, payload.getString("head"))

            enqueueRepository(server)
            server.enqueue(json("""[{"state":"open","html_url":"$pullUrl"}]"""))
            assertEquals(pullUrl, service.submit(service.preparePr("p", "Add notes", "Two files")))
            assertEquals(17, server.requestCount)

            enqueueRepository(server)
            server.enqueue(json("""[{"state":"closed","html_url":"$pullUrl"}]"""))
            server.enqueue(refResponse(candidate))
            val newPull = "https://github.com/acme/tools/pull/8"
            server.enqueue(json("""{"html_url":"$newPull"}"""))
            assertEquals(newPull, service.submit(service.preparePr("p", "Try again", "")))
            assertEquals(21, server.requestCount)
        }
    }

    @Test fun aMovedBasePreservesDraftsBeforeGitHubWrites() = runBlocking {
        MockWebServer().use { server ->
            val service = connected(server)
            service.create("p", "new.txt", "draft")
            val review = service.preparePublish("p", "Add a file")
            enqueueRepository(server)
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(refResponse(candidate))
            val failed = runCatching { service.submit(review) }
            assertTrue(failed.exceptionOrNull()?.message.orEmpty().contains("base branch moved"))
            assertEquals("draft", service.read("p", "new.txt"))
            assertEquals(8, server.requestCount)
            assertTrue(List(8) { server.takeRequest() }.all { it.method == "GET" })
        }
    }

    @Test fun aLostBranchUpdateResponseReconcilesAfterRestart() = runBlocking {
        MockWebServer().use { server ->
            val folder = temp.newFolder()
            val service = connected(server, folder)
            service.create("p", "new.txt", "draft")
            val review = service.preparePublish("p", "Add a file")
            enqueuePublish(server)
            repeat(2) { server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)) }
            assertTrue(runCatching { service.submit(review) }.isFailure)
            val reopened = GitHubProjects(folder, credentials, GitHubClient(server.url("/")))
            assertEquals("draft", reopened.read("p", "new.txt"))
            assertEquals(candidate, reopened.project("p")?.pending?.candidate?.commit)
            server.enqueue(refResponse(candidate))
            enqueueHead(server, candidate, subtree)
            assertEquals(candidate, reopened.refresh("p").head.commit)
            assertEquals(review.branch, reopened.project("p")?.workBranch)
            assertTrue(reopened.project("p")?.drafts.orEmpty().isEmpty())
            assertEquals(15, server.requestCount)
        }
    }

    @Test fun aRunBudgetRejectsExcessDownloadsAndEvictionKeepsDrafts() = runBlocking {
        MockWebServer().use { server ->
            enqueueConnection(server)
            val entries = listOf("one" to blob, "two" to apk).joinToString(",") { (path, id) ->
                """{"path":"$path","mode":"100644","type":"blob","size":600,"sha":"$id"}"""
            }
            server.enqueue(json("""{"truncated":false,"tree":[$entries]}"""))
            val root = temp.newFolder()
            val client = GitHubClient(server.url("/"))
            val service = GitHubProjects(root, credentials, client, cacheLimit = 1100, runLimit = 600)
            service.connect("p", "https://github.com/acme/tools", "github_pat_fixture", "")
            service.enableAgent("p", true)
            val access = checkNotNull(service.session("p"))
            val original = "a".repeat(600)
            server.enqueue(MockResponse().setBody(original))
            service.edit("p", "one", original, "kept", access)
            assertTrue(runCatching { service.read("p", "two", access) }.isFailure)
            assertEquals(6, server.requestCount)
            server.enqueue(MockResponse().setBody("b".repeat(600)))
            service.read("p", "two", service.session("p"))
            val reopened = GitHubProjects(root, credentials, client)
            assertEquals("kept", reopened.read("p", "one"))
            assertEquals(original, reopened.preparePublish("p", "Keep").drafts.single().originalText)
            assertEquals("b".repeat(600), reopened.read("p", "two"))
            assertEquals(7, server.requestCount)
        }
    }

    @Test fun pendingFirstPublishCanRefreshAfterBaseMoves() = runBlocking {
        MockWebServer().use { server ->
            val folder = temp.newFolder()
            val service = connected(server, folder)
            service.create("p", "new.txt", "draft")
            enqueuePublish(server)
            repeat(2) { server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)) }
            assertTrue(runCatching { service.submit(service.preparePublish("p", "Add file")) }.isFailure)
            // Refresh through a fresh client: the dropped connections mark the IPv4 route as failed in the
            // old client, which then tries [::1] first and, with retries off, never falls back.
            val reopened = GitHubProjects(folder, credentials, GitHubClient(server.url("/")))
            server.enqueue(MockResponse().setResponseCode(404))
            enqueueHead(server, candidate, subtree)
            server.enqueue(json("""{"truncated":false,"tree":[]}"""))
            val refreshed = reopened.refresh("p")
            assertEquals(candidate, refreshed.head.commit)
            assertNull(refreshed.pending)
            assertEquals("draft", refreshed.drafts.single().text)
            assertFalse(refreshed.drafts.single().conflict)
        }
    }

    @Test fun deletedWorkBranchRefreshesBaseAndPreservesDrafts() = runBlocking {
        MockWebServer().use { server ->
            val service = connected(server)
            service.create("p", "a.txt", "published")
            enqueuePublish(server)
            server.enqueue(json("""{"data":{"updateRefs":{"clientMutationId":"$candidate"}}}"""))
            val first = service.preparePublish("p", "Initial draft")
            service.submit(first)
            assertEquals("published", service.read("p", "a.txt"))
            service.create("p", "b.txt", "next draft")
            server.enqueue(MockResponse().setResponseCode(404))
            enqueueHead(server, head, tree)
            server.enqueue(json("""{"truncated":false,"tree":[]}"""))
            val refreshed = service.refresh("p")
            assertEquals("main", refreshed.branch)
            assertEquals("next draft", refreshed.drafts.single().text)
            assertNotEquals(first.branch, service.preparePublish("p", "Next draft").branch)
        }
    }

    @Test fun upstreamOverlapRequiresResolutionAndFreshReview() = runBlocking {
        MockWebServer().use { server ->
            enqueueConnection(server)
            server.enqueue(json("""{"truncated":false,"tree":[{"path":"notes","type":"blob","mode":"100644","size":3,"sha":"$blob"}]}"""))
            server.enqueue(MockResponse().setBody("old"))
            val service = GitHubProjects(temp.newFolder(), credentials, GitHubClient(server.url("/")))
            service.connect("p", "https://github.com/acme/tools", "github_pat_fixture", "")
            service.edit("p", "notes", "old", "draft")
            val previousReview = service.preparePublish("p", "Notes")
            enqueueHead(server, candidate, subtree)
            server.enqueue(json("""{"truncated":false,"tree":[{"path":"notes","type":"blob","mode":"100644","size":6,"sha":"$apk"}]}"""))
            assertTrue(service.refresh("p").drafts.single().conflict)
            assertTrue(runCatching { service.preparePublish("p", "Notes") }.isFailure)
            assertTrue(runCatching { service.submit(previousReview) }.isFailure)
            server.enqueue(MockResponse().setBody("remote"))
            service.resolve("p", "notes", "merged")
            val resolved = service.preparePublish("p", "Notes").drafts.single()
            assertEquals("remote", resolved.originalText)
            assertEquals("merged", resolved.text)
            assertFalse(resolved.conflict)
        }
    }

    @Test fun theAgentCanPopulateAnExistingEmptyTextFile() = runBlocking {
        MockWebServer().use { server ->
            enqueueConnection(server)
            server.enqueue(json("""{"truncated":false,"tree":[{"path":"empty","type":"blob","mode":"100644","size":0,"sha":"$blob"}]}"""))
            server.enqueue(MockResponse().setBody(""))
            val service = GitHubProjects(temp.newFolder(), credentials, GitHubClient(server.url("/")))
            service.connect("p", "https://github.com/acme/tools", "github_pat_fixture", "")
            service.edit("p", "empty", "", "first line")
            assertEquals("first line", service.read("p", "empty"))
            assertTrue(runCatching { service.edit("p", "empty", "", "incorrect") }.isFailure)
        }
    }

    @Test fun aLostPrResponseCanResolveToTheMatchingClosedPrAfterRestart() = runBlocking {
        MockWebServer().use { server ->
            val folder = temp.newFolder()
            val service = connected(server, folder)
            service.create("p", "new.txt", "draft")
            enqueuePublish(server)
            server.enqueue(json("""{"data":{"updateRefs":{"clientMutationId":"$candidate"}}}"""))
            service.submit(service.preparePublish("p", "Draft"))
            enqueueRepository(server)
            server.enqueue(json("[]"))
            server.enqueue(refResponse(candidate))
            repeat(2) { server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)) }
            assertTrue(runCatching { service.submit(service.preparePr("p", "My PR", "One file")) }.isFailure)

            val reopened = GitHubProjects(folder, credentials, GitHubClient(server.url("/")))
            val pending = checkNotNull(reopened.project("p")?.pendingPr)
            val created = java.time.Instant.ofEpochSecond(pending.submittedAt).toString()
            val link = "https://github.com/acme/tools/pull/8"
            server.enqueue(json("""[{
                "state":"closed","head":{"sha":"$candidate"},"title":"My PR","body":"One file",
                "created_at":"$created","html_url":"$link"
            }]"""))
            enqueueHead(server, candidate, subtree)
            assertEquals(link, reopened.refresh("p").lastLink)
            assertNull(reopened.project("p")?.pendingPr)
            assertEquals(19, server.requestCount)
            val requests = List(19) { server.takeRequest() }
            assertEquals(1, requests.count { it.method == "POST" && it.path == "/repos/acme/tools/pulls" })
        }
    }

    private fun enqueueHead(server: MockWebServer, commit: String, root: String) {
        server.enqueue(refResponse(commit))
        server.enqueue(json("""{"sha":"$commit","tree":{"sha":"$root"}}"""))
    }

    private suspend fun connected(server: MockWebServer, folder: java.io.File = temp.newFolder()): GitHubProjects {
        enqueueConnection(server)
        server.enqueue(json("""{"truncated":false,"tree":[]}"""))
        return GitHubProjects(folder, credentials, GitHubClient(server.url("/"))).also {
            it.connect("p", "https://github.com/acme/tools", "github_pat_fixture", "")
        }
    }

    private fun enqueuePublish(server: MockWebServer) {
        enqueueRepository(server)
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(refResponse(head))
        server.enqueue(json("""{"sha":"$subtree"}"""))
        server.enqueue(json("""{"sha":"$candidate"}"""))
    }

    private fun refResponse(oid: String) = json("""{"object":{"sha":"$oid"}}""")

    private fun enqueueRepository(server: MockWebServer) {
        server.enqueue(json("""{"node_id":"R_test","full_name":"acme/tools","default_branch":"main"}"""))
    }

    private fun enqueueConnection(server: MockWebServer) {
        server.enqueue(json("""{"id":42,"login":"dev"}"""))
        server.enqueue(json("""{"node_id":"R_test","full_name":"acme/tools","default_branch":"main"}"""))
        enqueueHead(server, head, tree)
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private val head = "1".repeat(40)
    private val tree = "2".repeat(40)
    private val blob = "3".repeat(40)
    private val apk = "4".repeat(40)
    private val subtree = "5".repeat(40)
    private val candidate = "6".repeat(40)
}
