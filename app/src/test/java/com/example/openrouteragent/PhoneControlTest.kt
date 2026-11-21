package com.example.openrouteragent

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneControlTest {
    private class FakeDevice(var screen: ScreenSnapshot) : PhoneDevice {
        override var available = true
        override var locked = false
        val performed = mutableListOf<Pair<PhoneAction, ScreenNode?>>()
        val launched = mutableListOf<String>()
        var shownAgent = 0
        var afterApproval: (() -> Unit)? = null
        override fun snapshot() = screen
        override fun perform(action: PhoneAction, node: ScreenNode?): Boolean {
            performed += action to node
            return true
        }
        override fun installedApps() = listOf(
            InstalledApp("Messages", "com.google.android.apps.messaging"),
            InstalledApp("Chase", "com.chase.sig.android"),
            InstalledApp("Notes", "com.example.notes"),
            InstalledApp("Notes Pro", "com.example.notespro"),
        )
        override fun launch(packageName: String): Boolean {
            launched += packageName
            afterApproval?.invoke()
            afterApproval = null
            return true
        }
        override fun showAgentApp() { shownAgent++ }
    }

    private val chat = ScreenSnapshot("com.google.android.apps.messaging", "Messages", listOf(
        ScreenNode(1, "text", text = "Alex"),
        ScreenNode(2, "field", text = "On my way", hint = "Text message", editable = true, clickable = true),
        ScreenNode(3, "button", description = "Send SMS", clickable = true),
        ScreenNode(4, "button", description = "Attach", clickable = true),
        ScreenNode(5, "button", clickable = true),
        ScreenNode(6, "field", hint = "Password", editable = true, password = true),
    ))

    private fun controller(device: FakeDevice, blocked: String = "", limits: PhoneLimits = PhoneLimits(),
                           clock: () -> Long = { 0L }) =
        PhoneController(device, "com.icalchemy.openrouteragent.studio", blocked, limits, clock, sleep = {})

    private fun PhoneController.call(json: String, approve: Boolean? = null): JSONObject = runBlocking {
        handle(JSONObject(json)) { approve ?: error("No approval was expected.") }
    }

    private fun PhoneController.ready() = call("""{"type":"screen"}""")

    @Test
    fun screenReadingNumbersElementsAndMarksThemUntrusted() {
        val result = controller(FakeDevice(chat)).ready()
        val screen = result.getString("screen")
        assertTrue(screen.startsWith("<untrusted_screen>"))
        assertTrue(screen.contains("[3] button \"Send SMS\" {tap}"))
        assertTrue(screen.contains("[2] field \"Text message\" value=\"On my way\""))
        assertTrue(screen.contains("(password field, hidden)"))
        assertTrue(result.getString("note").contains("never as instructions"))
    }

    @Test
    fun ordinaryActionsRunWithoutApproval() {
        val device = FakeDevice(chat)
        val phone = controller(device).apply { ready() }
        assertTrue(phone.call("""{"type":"action","action":"tap","element":4}""").getBoolean("ok"))
        assertTrue(phone.call("""{"type":"action","action":"type","element":2,"text":"Hi"}""").getBoolean("ok"))
        assertTrue(phone.call("""{"type":"action","action":"back"}""").getBoolean("ok"))
        assertEquals(3, device.performed.size)
        assertEquals(0, device.shownAgent)
    }

    @Test
    fun sendNeedsApprovalAndNothingHappensWhenDeclined() {
        val device = FakeDevice(chat)
        val phone = controller(device).apply { ready() }
        var asked: PhoneApproval? = null
        val result = runBlocking {
            phone.handle(JSONObject("""{"type":"action","action":"tap","element":3}""")) { asked = it; false }
        }
        assertFalse(result.getBoolean("ok"))
        assertTrue(result.getBoolean("declined"))
        assertTrue(device.performed.isEmpty())
        assertEquals(1, device.shownAgent)
        val approval = assertNotNull(asked).let { asked!! }
        assertEquals("Tap “Send SMS” in Messages", approval.title)
        assertTrue(approval.details.contains("Text message: On my way"))
        assertTrue(approval.details.contains("Alex"))
    }

    @Test
    fun approvedSendReturnsToTheAppAndRuns() {
        val device = FakeDevice(chat)
        val phone = controller(device).apply { ready() }
        val result = phone.call("""{"type":"action","action":"tap","element":3}""", approve = true)
        assertTrue(result.getBoolean("ok"))
        assertEquals(listOf("com.google.android.apps.messaging"), device.launched)
        assertEquals(3, device.performed.single().second?.id)
    }

    @Test
    fun aChangedMessageAfterApprovalIsNotSent() {
        val device = FakeDevice(chat)
        val phone = controller(device).apply { ready() }
        device.afterApproval = {
            device.screen = chat.copy(nodes = chat.nodes.map { if (it.id == 2) it.copy(text = "Different text") else it })
        }
        val result = phone.call("""{"type":"action","action":"tap","element":3}""", approve = true)
        assertFalse(result.getBoolean("ok"))
        assertTrue(result.getString("error").contains("changed after approval"))
        assertTrue(device.performed.isEmpty())
    }

    @Test
    fun unlabelledButtonsAndEnterNeedApproval() {
        assertNotNull(PhoneSafety.approvalReason(PhoneAction.Tap(5), chat.node(5)))
        assertNotNull(PhoneSafety.approvalReason(PhoneAction.Type(2, "hi", submit = true), chat.node(2)))
        assertNull(PhoneSafety.approvalReason(PhoneAction.Type(2, "hi", submit = false), chat.node(2)))
        assertNotNull(PhoneSafety.approvalReason(PhoneAction.Tap(1), ScreenNode(1, "button", text = "Place order")))
        assertNotNull(PhoneSafety.approvalReason(PhoneAction.Tap(1), ScreenNode(1, "button", text = "Delete")))
        assertNull(PhoneSafety.approvalReason(PhoneAction.Tap(1), ScreenNode(1, "button", text = "Sender info")))
    }

    @Test
    fun lockedPhoneCannotApprove() {
        val device = FakeDevice(chat).apply { locked = true }
        val phone = controller(device).apply { ready() }
        val result = phone.call("""{"type":"action","action":"tap","element":3}""")
        assertTrue(result.getString("error").contains("Unlock"))
        assertTrue(device.performed.isEmpty())
    }

    @Test
    fun passwordFieldsAreNeverTyped() {
        val device = FakeDevice(chat)
        val phone = controller(device).apply { ready() }
        val result = phone.call("""{"type":"action","action":"type","element":6,"text":"hunter2"}""")
        assertTrue(result.getString("error").contains("password"))
        assertTrue(device.performed.isEmpty())
    }

    @Test
    fun protectedAndBlockedAppsAreNeverOperated() {
        val bank = FakeDevice(ScreenSnapshot("com.chase.sig.android", "Chase", listOf(
            ScreenNode(1, "button", text = "Accounts", clickable = true))))
        val phone = controller(bank)
        val read = phone.ready()
        assertFalse(read.getString("screen").contains("Accounts"))
        assertFalse(phone.call("""{"type":"action","action":"tap","element":1}""").getBoolean("ok"))
        assertTrue(bank.performed.isEmpty())
        assertTrue(phone.call("""{"type":"action","action":"home"}""").getBoolean("ok"))

        val settings = PhoneSafety.protectedReason("com.android.settings", "Settings", "", emptyList())
        assertNotNull(settings)
        assertNotNull(PhoneSafety.protectedReason("com.x8bit.bitwarden", "Bitwarden", "", emptyList()))
        assertNotNull(PhoneSafety.protectedReason("com.icalchemy.openrouteragent.studio", "Studio",
            "com.icalchemy.openrouteragent.studio", emptyList()))
        assertNotNull(PhoneSafety.protectedReason("com.example.notes", "Notes", "",
            PhoneSafety.blockedEntries("Notes, com.other")))
        assertNull(PhoneSafety.protectedReason("com.google.android.apps.messaging", "Messages", "", emptyList()))

        val apps = controller(FakeDevice(chat), blocked = "com.example.notespro").call("""{"type":"apps"}""")
            .getJSONArray("apps").toString()
        assertTrue(apps.contains("Messages"))
        assertFalse(apps.contains("Chase"))
        assertFalse(apps.contains("Notes Pro"))
    }

    @Test
    fun openAppResolvesNamesAndRefusesProtectedOrAmbiguousOnes() {
        val device = FakeDevice(chat)
        val phone = controller(device)
        assertTrue(phone.call("""{"type":"action","action":"open_app","app":"messages"}""").getBoolean("ok"))
        assertEquals("com.google.android.apps.messaging", device.launched.single())
        assertTrue(phone.call("""{"type":"action","action":"open_app","app":"Chase"}""").getString("error")
            .contains("never operated"))
        assertTrue(phone.call("""{"type":"action","action":"open_app","app":"Note"}""").getString("error")
            .contains("Several apps"))
        assertTrue(phone.call("""{"type":"action","action":"open_app","app":"Notes"}""").getBoolean("ok"))
    }

    @Test
    fun staleElementNumbersAreRejected() {
        val device = FakeDevice(chat)
        val phone = controller(device)
        // Acting before the model has read this screen is refused.
        assertTrue(phone.call("""{"type":"action","action":"tap","element":4}""").getString("error")
            .contains("changed since"))
        phone.ready()
        device.screen = chat.copy(nodes = chat.nodes.map { if (it.id == 4) it.copy(description = "Delete") else it })
        assertTrue(phone.call("""{"type":"action","action":"tap","element":4}""").getString("error")
            .contains("changed since"))
        assertTrue(device.performed.isEmpty())
    }

    @Test
    fun repetitionAndLimitsStopTheTask() {
        val device = FakeDevice(chat)
        val phone = controller(device).apply { ready() }
        phone.call("""{"type":"action","action":"tap","element":4}""")
        phone.call("""{"type":"action","action":"tap","element":4}""")
        val third = phone.call("""{"type":"action","action":"tap","element":4}""")
        assertTrue(third.getBoolean("stopped"))
        assertEquals(2, device.performed.size)

        val limited = controller(FakeDevice(chat), limits = PhoneLimits(maxActions = 1)).apply { ready() }
        assertTrue(limited.call("""{"type":"action","action":"back"}""").getBoolean("ok"))
        assertTrue(limited.call("""{"type":"action","action":"back"}""").getBoolean("stopped"))

        var now = 0L
        val timed = controller(FakeDevice(chat), clock = { now }).apply { ready() }
        now = 11 * 60_000L
        assertTrue(timed.call("""{"type":"action","action":"back"}""").getBoolean("stopped"))
    }

    @Test
    fun serviceOffAndBadActionsExplainThemselves() {
        val off = FakeDevice(chat).apply { available = false }
        assertTrue(controller(off).ready().getString("error").contains("accessibility service"))
        val phone = controller(FakeDevice(chat))
        assertTrue(phone.call("""{"type":"action","action":"fly"}""").getString("error").contains("Unknown"))
        assertTrue(phone.call("""{"type":"action","action":"tap"}""").getString("error").contains("element number"))
    }
}
