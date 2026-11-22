package com.example.openrouteragent

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SkillsDirectoryClientTest {
    @Test
    fun telemetryOptOutEnvironmentAndCliCommandAlwaysSetAdisableTelemetry() {
        assertEquals("1", SKILLS_NO_TELEMETRY_ENV["ADISABLE_TELEMETRY"])
        assertEquals("1", SKILLS_NO_TELEMETRY_ENV["DISABLE_TELEMETRY"])
        assertEquals("1", SKILLS_NO_TELEMETRY_ENV["DO_NOT_TRACK"])

        val command = skillsCliInstallCommand(
            RemoteSkillSummary(
                id = "vercel-labs/agent-skills/vercel-react-best-practices",
                skillId = "vercel-react-best-practices",
                name = "vercel-react-best-practices",
                source = "vercel-labs/agent-skills",
                installs = 1234,
            ),
        )
        assertTrue(command.contains("ADISABLE_TELEMETRY=1"))
        assertTrue(command.contains("npx skills add vercel-labs/agent-skills --skill vercel-react-best-practices"))
    }

    @Test
    fun formatsInstallCountsLikeVercelSkillsCli() {
        assertEquals("", formatSkillInstalls(0))
        assertEquals("1 install", formatSkillInstalls(1))
        assertEquals("42 installs", formatSkillInstalls(42))
        assertEquals("24.5K installs", formatSkillInstalls(24_531))
        assertEquals("1.5M installs", formatSkillInstalls(1_500_000))
    }

    @Test
    fun searchesSkillsShWithDntHeaderAndMergesMultiTermPartialFallback() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    assertEquals("1", request.getHeader("DNT"))
                    assertFalse(path.contains("telemetry"))
                    return when {
                        path.startsWith("/api/search?q=react%20perf") -> MockResponse().setBody("""
                            {"skills":[{"id":"a/b/exact","skillId":"exact","name":"exact","source":"a/b","installs":100}]}
                        """.trimIndent())
                        path.startsWith("/api/search?q=perf") -> MockResponse().setBody("""
                            {"skills":[
                              {"id":"a/b/exact","skillId":"exact","name":"exact","source":"a/b","installs":100},
                              {"id":"vercel-labs/agent-skills/vercel-react-best-practices","skillId":"vercel-react-best-practices","name":"vercel-react-best-practices","source":"vercel-labs/agent-skills","installs":921811}
                            ]}
                        """.trimIndent())
                        path.startsWith("/api/search?q=react") -> MockResponse().setBody("""
                            {"skills":[
                              {"id":"vercel-labs/agent-skills/vercel-react-best-practices","skillId":"vercel-react-best-practices","name":"vercel-react-best-practices","source":"vercel-labs/agent-skills","installs":921811},
                              {"id":"org/repo/react-hooks","skillId":"react-hooks","name":"react-hooks","source":"org/repo","installs":500}
                            ]}
                        """.trimIndent())
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()

            val client = SkillsDirectoryClient(searchBaseUrl = server.url("/"))
            assertEquals(emptyList<RemoteSkillSummary>(), client.search("   "))
            assertEquals(0, server.requestCount)

            val results = client.search("react perf")
            assertEquals(
                listOf("exact", "vercel-react-best-practices", "react-hooks"),
                results.map { it.skillId },
            )
            assertEquals("vercel-labs/agent-skills", results[1].source)
            assertEquals(921811, results[1].installs)
        }
    }

    @Test
    fun fetchesSkillFromDirectGitHubPathAndTreeFallbackForReview() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/raw/owner/direct/HEAD/skills/my-skill/SKILL.md" -> MockResponse().setBody("""
                        ---
                        name: my-skill
                        description: Direct skill description.
                        ---
                        # Direct Skill
                        Follow step one.
                    """.trimIndent())

                    "/api/repos/vercel-labs/agent-skills/git/trees/HEAD?recursive=1" -> MockResponse().setBody("""
                        {
                          "tree": [
                            {"path": "README.md", "type": "blob"},
                            {"path": "skills/other-skill/SKILL.md", "type": "blob"},
                            {"path": "skills/react-best-practices/SKILL.md", "type": "blob"}
                          ]
                        }
                    """.trimIndent())

                    "/raw/vercel-labs/agent-skills/HEAD/skills/react-best-practices/SKILL.md" -> MockResponse().setBody("""
                        ---
                        name: vercel-react-best-practices
                        description: React performance rules.
                        ---
                        # React Best Practices
                        Avoid waterfalls.
                    """.trimIndent())

                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()

            val client = SkillsDirectoryClient(
                searchBaseUrl = server.url("/"),
                rawGitHubBaseUrl = server.url("/raw/"),
                gitHubApiBaseUrl = server.url("/api/"),
            )

            val direct = client.fetchSkill(
                RemoteSkillSummary("owner/direct/my-skill", "my-skill", "my-skill", "owner/direct", 10),
            )
            assertEquals("my-skill", direct.name)
            assertEquals("Direct skill description.", direct.description)
            assertFalse(direct.enabled)

            val nested = client.fetchSkill(
                RemoteSkillSummary(
                    "vercel-labs/agent-skills/vercel-react-best-practices",
                    "vercel-react-best-practices",
                    "vercel-react-best-practices",
                    "vercel-labs/agent-skills",
                    999,
                ),
            )
            assertEquals("vercel-react-best-practices", nested.name)
            assertEquals("React performance rules.", nested.description)
            assertTrue(nested.body.contains("Avoid waterfalls."))
            assertFalse(nested.enabled)
        }
    }

    @Test
    fun fetchesSkillFromWellKnownV1AndV2AndRejectsOversizedFiles() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/.well-known/agent-skills/index.json" -> MockResponse().setBody("""
                        {
                          "skills": [
                            {"name": "v1-skill", "description": "Legacy entry", "files": ["SKILL.md"]},
                            {"name": "v2-skill", "type": "skill-md", "description": "V2 entry", "url": "/published/v2/SKILL.md"},
                            {"name": "huge-skill", "type": "skill-md", "description": "Huge", "url": "/published/huge/SKILL.md"}
                          ]
                        }
                    """.trimIndent())

                    "/.well-known/agent-skills/v1-skill/SKILL.md" -> MockResponse().setBody("""
                        ---
                        name: v1-skill
                        description: From v1 well-known.
                        ---
                        V1 body
                    """.trimIndent())

                    "/published/v2/SKILL.md" -> MockResponse().setBody("""
                        ---
                        name: v2-skill
                        description: From v2 well-known.
                        ---
                        V2 body
                    """.trimIndent())

                    "/published/huge/SKILL.md" -> MockResponse().setBody("x".repeat(SKILL_IMPORT_BYTES + 100))
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()

            val client = SkillsDirectoryClient(
                searchBaseUrl = server.url("/"),
                wellKnownBase = { server.url("/") },
            )

            val v1 = client.fetchSkill(RemoteSkillSummary("example.com/v1-skill", "v1-skill", "v1-skill", "example.com"))
            assertEquals("v1-skill", v1.name)
            assertEquals("V1 body", v1.body)
            assertFalse(v1.enabled)

            val v2 = client.fetchSkill(RemoteSkillSummary("example.com/v2-skill", "v2-skill", "v2-skill", "example.com"))
            assertEquals("v2-skill", v2.name)
            assertEquals("V2 body", v2.body)
            assertFalse(v2.enabled)

            val oversized = runCatching {
                client.fetchSkill(RemoteSkillSummary("example.com/huge-skill", "huge-skill", "huge-skill", "example.com"))
            }.exceptionOrNull()
            assertTrue(oversized is IllegalArgumentException)

            val missing = runCatching {
                client.fetchSkill(RemoteSkillSummary("example.com/missing", "missing", "missing", "example.com"))
            }.exceptionOrNull()
            assertTrue(missing is IOException)
        }
    }
}
