package com.example.openrouteragent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.json.JSONObject

class ProjectFileStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun projectFoldersKeepTheSameRelativeFilenameIndependent() {
        val store = ProjectFileStore(folder.root)
        val alpha = ProjectFileArea("alpha", "chat-one")
        val beta = ProjectFileArea("beta", "chat-two")
        store.writeText(alpha, "docs/plan.md", "alpha plan", null)
        store.writeText(beta, "docs/plan.md", "beta plan", null)
        store.createFolder(alpha, "figures")

        assertEquals(listOf("docs", "figures"), store.list(alpha, "").map { it.path })
        assertEquals(listOf("docs/plan.md"), store.list(alpha, "docs").map { it.path })
        assertEquals("alpha plan", store.preview(ProjectFileArea("alpha", "another-chat"), "docs/plan.md").text)
        assertEquals("beta plan", store.preview(beta, "docs/plan.md").text)
        assertEquals("projects/alpha/docs/plan.md", alpha.remotePrefix + "docs/plan.md")
    }

    @Test
    fun textChangesRequireTheCurrentRevisionOrAUniqueExactMatch() {
        val store = ProjectFileStore(folder.root)
        val area = ProjectFileArea("alpha", "chat-one")
        store.writeText(area, "plan.txt", "before before", null)
        assertThrows(IllegalArgumentException::class.java) { store.writeText(area, "plan.txt", "new", null) }
        assertThrows(IllegalArgumentException::class.java) { store.editText(area, "plan.txt", "before", "after") }
        store.writeText(area, "plan.txt", "first draft", "before before")
        assertThrows(IllegalArgumentException::class.java) {
            store.writeText(area, "plan.txt", "overwritten", "before before")
        }
        store.editText(area, "plan.txt", "first draft", "ready to share")
        assertEquals("ready to share", store.preview(area, "plan.txt").text)
    }

    @Test
    fun importedFilesKeepOriginalsAndPathsCannotEscapeTheProject() {
        val store = ProjectFileStore(folder.root)
        val area = ProjectFileArea("alpha", "chat-one")
        listOf("../escape.txt", "/escape.txt", "nested/../escape.txt", "nested\\escape.txt", "nested//escape.txt")
            .forEach { path ->
                assertThrows(IllegalArgumentException::class.java) { store.writeText(area, path, "bad", null) }
            }
        val data = byteArrayOf(0, 1, 2, -1)
        assertEquals("assets/sample.bin", store.importCopy(area, "assets/sample.bin") { data.inputStream() })
        assertEquals("assets/sample (2).bin", store.importCopy(area, "assets/sample.bin") { data.inputStream() })
        assertNull(store.preview(area, "assets/sample.bin").text)
        assertTrue(!folder.root.resolve("escape.txt").exists())
    }

    @Test
    fun sandboxIdentityIsStablePerProjectKeyAndNetworkSettings() {
        val config = AgentConfig(apiKey = "key-one")
        assertEquals(projectSandboxId("alpha", config), projectSandboxId("alpha", config))
        assertNotEquals(projectSandboxId("alpha", config), projectSandboxId("beta", config))
        assertNotEquals(projectSandboxId("alpha", config), projectSandboxId("alpha", config.copy(apiKey = "key-two")))
        assertNotEquals(projectSandboxId("alpha", config),
            projectSandboxId("alpha", config.copy(allowSandboxNetwork = true, allowedDomainsCsv = "example.com")))
        assertTrue(projectSandboxId("alpha", config).matches(Regex("[A-Za-z0-9_-]{1,40}")))
    }

    @Test
    fun appFileToolResultDescribesTheFileThatWasActuallyWritten() {
        val store = ProjectFileStore(folder.root)
        val area = ProjectFileArea("alpha", "chat-one")
        val args = JSONObject().put("path", "notes/plan.md").put("text", "First draft")
        assertTrue(store.performAction(area, "write_project_file", args).getBoolean("created"))
        val edit = JSONObject().put("path", "notes/plan.md").put("oldText", "First").put("newText", "Second")
        assertTrue(store.performAction(area, "edit_project_file", edit).getBoolean("edited"))
        assertEquals("Second draft", store.performAction(area, "read_project_file", args).getString("text"))
        assertEquals("Second draft", folder.root.resolve("projects/alpha/notes/plan.md").readText())
    }

    @Test
    fun androidRunOptionsCarryWorkspaceFileIdsAndTheirSandboxNames() {
        val attachment = WorkspaceFileRef("or_file_abc12345678", "projects/alpha/docs/notes.md", "text/markdown",
            9, false)
        val files = OpenRouterRunFiles("projects/alpha", "studio_alpha", listOf(attachment))
        val payload = runtimeOptions(
            config = AgentConfig(enableWorkspaceFiles = true), workspace = Workspace(containerId = "old_chat"),
            prompt = "Read notes.md", model = CatalogModel("mock/model", "Mock", 0, setOf("tools")),
            files = files, appActions = true,
        )
        assertEquals("projects/alpha", payload.getString("projectFolder"))
        assertEquals("studio_alpha", payload.getString("containerId"))
        assertEquals("or_file_abc12345678", payload.getJSONArray("fileIds").getString(0))
        assertEquals("12345678-notes.md", payload.getJSONArray("attachmentNames").getString(0))
        assertTrue(payload.getBoolean("appActions"))
    }
}
