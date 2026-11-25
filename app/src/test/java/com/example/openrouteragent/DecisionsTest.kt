package com.example.openrouteragent

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DecisionsTest {
    private val photo = DecisionPart(DECISION_IMAGE, mimeType = "image/jpeg", base64 = "AAAA", name = "a.jpg")
    private val photo2 = DecisionPart(DECISION_IMAGE, mimeType = "image/jpeg", base64 = "BBBB", name = "b.jpg")
    private val clip = DecisionPart(DECISION_AUDIO, mimeType = "audio/mpeg", base64 = "CCCC", name = "c.mp3")
    private val video = DecisionPart(DECISION_VIDEO, mimeType = "video/mp4", base64 = "DDDD", name = "d.mp4")

    @Test
    fun choiceWithPhotosPutsEachPhotoAfterItsLabel() {
        val ask = DecisionAsk("typesafe/jev-1.13", "Which photo should I post?", DecisionKind.Choice,
            options = listOf(DecisionOption("A", listOf(photo)), DecisionOption("B", listOf(photo2))))
        val body = decisionRequestBody(ask)
        assertEquals("typesafe/jev-1.13", body.getString("model"))
        val state = body.getJSONArray("state")
        assertEquals("Option A:", state.getString(0))
        assertEquals("image_url", state.getJSONObject(1).getString("type"))
        assertEquals("data:image/jpeg;base64,AAAA", state.getJSONObject(1).getJSONObject("image_url").getString("url"))
        assertEquals("Option B:", state.getString(2))
        val question = body.getJSONObject("questions").getJSONObject("decision")
        assertEquals("choice", question.getString("type"))
        assertEquals("Which photo should I post?", question.getString("instructions"))
        assertEquals("The image shown after \"Option A:\".", question.getJSONObject("criteria").getString("A"))
    }

    @Test
    fun textChoiceUsesTheTextAsCriteriaAndSkipsEmptyOptions() {
        val ask = DecisionAsk("m", "Best name?", DecisionKind.Choice, options = listOf(
            DecisionOption("A", listOf(DecisionPart.text(" Nova "))),
            DecisionOption("B", listOf(DecisionPart.text("Atlas"))),
            DecisionOption("C", emptyList()),
        ))
        val criteria = decisionRequestBody(ask).getJSONObject("questions").getJSONObject("decision").getJSONObject("criteria")
        assertEquals(setOf("A", "B"), criteria.keySet())
        assertEquals("Nova", criteria.getString("A"))
    }

    @Test
    fun yesNoAndScoreSendTheSubjectAndLevels() {
        val yesNo = decisionRequestBody(DecisionAsk("m", "Is it ripe?", DecisionKind.YesNo, subject = listOf(photo)))
        val question = yesNo.getJSONObject("questions").getJSONObject("decision")
        assertEquals("noul", question.getString("type"))
        assertFalse(question.has("criteria"))
        assertEquals("image_url", yesNo.getJSONArray("state").getJSONObject(0).getString("type"))

        val score = decisionRequestBody(DecisionAsk("m", "How tidy?", DecisionKind.Score,
            subject = listOf(DecisionPart.text("A room")), levels = listOf("Messy", " ", "Tidy")))
        val levels = score.getJSONObject("questions").getJSONObject("decision").getJSONArray("criteria")
        assertEquals(listOf("Messy", "Tidy"), (0 until levels.length()).map(levels::getString))
        assertEquals("A room", score.getJSONArray("state").getString(0))
    }

    @Test
    fun audioAndVideoUseTheirContentParts() {
        val audio = clip.stateEntry() as JSONObject
        assertEquals("input_audio", audio.getString("type"))
        assertEquals("CCCC", audio.getJSONObject("input_audio").getString("data"))
        assertEquals("mp3", audio.getJSONObject("input_audio").getString("format"))
        val movie = video.stateEntry() as JSONObject
        assertEquals("data:video/mp4;base64,DDDD", movie.getJSONObject("video_url").getString("url"))
        assertEquals("m4a", decisionAudioFormat("audio/mp4"))
        assertNull(decisionAudioFormat("audio/x-unknown"))
    }

    @Test
    fun problemsAreCaughtBeforeSending() {
        val one = DecisionAsk("m", "Q", DecisionKind.Choice, options = listOf(DecisionOption("A", listOf(photo)),
            DecisionOption("B", emptyList())))
        assertEquals("Fill in at least two options.", one.problem())
        assertEquals("Type the question to decide.", one.copy(question = " ").problem())
        assertEquals("Add the text, photo, video or audio to judge.",
            DecisionAsk("m", "Q", DecisionKind.YesNo).problem())
        assertEquals("List at least two score levels.",
            DecisionAsk("m", "Q", DecisionKind.Score, subject = listOf(photo), levels = listOf("Only")).problem())
        val five = DecisionAsk("m", "Q", DecisionKind.Choice,
            options = ('A'..'E').map { DecisionOption(it.toString(), listOf(DecisionPart.text("x"))) })
        assertEquals("Use up to 4 options.", five.problem())
        val ok = DecisionAsk("m", "Q", DecisionKind.Choice,
            options = listOf(DecisionOption("A", listOf(photo)), DecisionOption("B", listOf(photo2))))
        assertNull(ok.problem())
    }

    @Test
    fun parsesChoiceYesNoScoreAndRefusal() {
        val choice = parseDecisionResponse("""{"id":"d1","model":"typesafe/jev-1.13","provider":"TypeSafe",
            "usage":{"cost":0.0004},"answers":{"decision":{"type":"choice","choice":"B",
            "probabilities":{"A":0.2,"B":0.8},"confidence":0.7}}}""")
        assertEquals("B", choice.answer?.choice)
        assertEquals(0.8, choice.answer!!.probabilities["B"]!!, 1e-9)
        assertEquals(0.0004, choice.cost!!, 1e-12)
        assertEquals("TypeSafe", choice.provider)

        val yes = parseDecisionResponse("""{"answers":{"decision":{"type":"noul","noul":0.86}}}""").answer!!
        assertEquals(true, yes.yes)
        assertEquals(false, yes.copy(noul = 0.1).yes)

        val score = parseDecisionResponse("""{"answers":{"decision":{"type":"score","score":1.7,
            "legend":{"0":"Messy","1":"Okay","2":"Tidy"},"probabilities":{"0":0.1,"1":0.2,"2":0.7}}}}""").answer!!
        assertEquals(2 to "Tidy", score.scoreLevel)

        assertTrue(parseDecisionResponse("""{"answers":{"decision":{"type":"refusal"}}}""").answer!!.refused)
    }

    @Test
    fun errorMessagesExplainA400AndHideTheKey() {
        val message = decisionErrorMessage(400,
            """{"error":{"code":400,"message":"Model does not support video input for key sk-or-secret"}}""", "sk-or-secret")
        assertTrue(message.startsWith("OpenRouter HTTP 400. This model can't take this decision as asked."))
        assertTrue(message.contains("OpenRouter said: Model does not support video input"))
        assertFalse(message.contains("sk-or-secret"))
        assertTrue(decisionErrorMessage(402, "", "k").contains("credit"))
        assertTrue(decisionErrorMessage(413, "", "k").contains("too large"))
    }

    @Test
    fun unsupportedMediaIsNamedWithModelsThatCan() {
        val jev = CatalogModel("typesafe/jev-1.13", "Jev 1.13", 8192, emptySet(), inputModalities = setOf("text", "image"))
        val omni = CatalogModel("cloudflare/clef-omni", "Clef Omni", 8192, emptySet(),
            inputModalities = setOf("text", "image", "audio", "video"))
        assertEquals(listOf(DECISION_AUDIO), unsupportedDecisionMedia(jev, listOf(photo, clip)))
        assertEquals(emptyList<String>(), unsupportedDecisionMedia(omni, listOf(photo, clip, video)))
        assertEquals(emptyList<String>(), unsupportedDecisionMedia(null, listOf(clip)))
        assertEquals("Jev 1.13 can't read audio. Try Clef Omni.",
            unsupportedMediaMessage("Jev 1.13", listOf(DECISION_AUDIO), listOf(jev, omni)))
    }

    @Test
    fun toolArgumentsWithTwoAttachmentsMakeThemTheOptions() {
        val args = JSONObject().put("question", "Which is sharper?").put("kind", "choice").put("attached", true)
            .put("options", JSONArray(listOf("Left photo")))
        val ask = decisionAskFromTool(args, "", listOf(photo, photo2))
        assertEquals(DEFAULT_DECISION_MODEL, ask.model)
        assertEquals(listOf("A", "B"), ask.options.map { it.label })
        assertEquals(listOf(DecisionPart.text("Left photo"), photo), ask.options[0].parts)
        assertEquals(listOf(photo2), ask.options[1].parts)
        assertNull(ask.problem())
    }

    @Test
    fun toolArgumentsWithoutAttachmentsUseTextOptions() {
        val args = JSONObject().put("question", "Pick").put("kind", "choice").put("model", "cloudflare/clef-flash")
            .put("options", JSONArray(listOf("Tea", "Coffee"))).put("attached", false)
        val ask = decisionAskFromTool(args, "typesafe/jev-1.14", listOf(photo))
        assertEquals("cloudflare/clef-flash", ask.model)
        assertEquals(emptyList<DecisionPart>(), ask.subject)
        assertEquals("Coffee", ask.options[1].parts.single().text)

        val yesNo = decisionAskFromTool(JSONObject().put("question", "Is this spam?").put("kind", "yes_no")
            .put("content", "WIN A PRIZE"), "typesafe/jev-1.14", emptyList())
        assertEquals(DecisionKind.YesNo, yesNo.kind)
        assertEquals("typesafe/jev-1.14", yesNo.model)
        assertEquals("WIN A PRIZE", yesNo.subject.single().text)

        try {
            decisionAskFromTool(JSONObject().put("question", "Q").put("kind", "rank"), "", emptyList())
            fail("An unknown kind should be refused.")
        } catch (e: IllegalArgumentException) {
            assertEquals("kind must be choice, yes_no or score.", e.message)
        }
    }

    @Test
    fun toolReplyCarriesTheAnswerAndCost() {
        val ask = DecisionAsk("m", "Pick", DecisionKind.Choice, options = listOf(
            DecisionOption("A", listOf(DecisionPart.text("Tea"))), DecisionOption("B", listOf(DecisionPart.text("Coffee")))))
        val result = DecisionResult(mapOf("decision" to DecisionAnswer("choice", choice = "B",
            probabilities = mapOf("A" to 0.3, "B" to 0.7), confidence = 0.6)), model = "typesafe/jev-1.13", cost = 0.001)
        val reply = JSONObject(decisionToolReply(ask, result))
        assertEquals("B", reply.getString("choice"))
        assertEquals("Coffee", reply.getString("choice_text"))
        assertEquals(0.7, reply.getJSONObject("probabilities").getDouble("B"), 1e-9)
        assertEquals(0.001, reply.getDouble("cost_usd"), 1e-12)

        val refused = JSONObject(decisionToolReply(ask, DecisionResult(mapOf("decision" to DecisionAnswer("refusal")))))
        assertTrue(refused.getBoolean("refused"))
        assertEquals("boom", JSONObject(decisionToolError("boom")).getString("error"))
    }

    @Test
    fun toolSpecIsAValidAskToolAndTheNameIsReserved() {
        val spec = decisionToolSpec()
        assertEquals(DECISION_TOOL_NAME, spec.getString("name"))
        assertEquals("ask", spec.getString("handler"))
        val schema = JSONObject(spec.getString("schema"))
        assertEquals(listOf("question", "kind"), schema.getJSONArray("required").let { a -> (0 until a.length()).map(a::getString) })
        assertTrue(CustomAgentTool(name = DECISION_TOOL_NAME, description = "x").problem() != null)
    }

    @Test
    fun theToolFollowsItsSettingAndTheToolSwitch() {
        assertFalse(decisionToolEnabled(AgentConfig()))
        val on = AgentConfig(decisionsTool = true)
        assertTrue(decisionToolEnabled(on))
        assertTrue(on.agentToolsEnabled())
        assertFalse(decisionToolEnabled(on.copy(sdk = on.sdk.copy(toolChoice = "none"))))
        assertFalse(on.withoutTools().decisionsTool)
    }

    @Test
    fun clientSendsTheDecisionWithAttributionAndReportsErrors() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"answers":{"decision":{"type":"noul","noul":0.9}},"model":"m"}"""))
            server.enqueue(MockResponse().setResponseCode(400)
                .setBody("""{"error":{"code":400,"message":"capability mismatch: audio"}}"""))
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"typesafe/jev-1.13","name":"Jev 1.13",
                "architecture":{"input_modalities":["text","image"],"output_modalities":["decisions"]}}]}"""))
            server.start()
            val client = OpenRouterDecisionsClient(server.url("/api/alpha/decisions"),
                server.url("/api/v1/models?output_modalities=decisions"))
            val ask = DecisionAsk("m", "Is it spam?", DecisionKind.YesNo, subject = listOf(DecisionPart.text("WIN")))

            assertEquals(true, client.decide("sk-test", ask).answer?.yes)
            val sent = server.takeRequest()
            assertEquals("POST", sent.method)
            assertEquals("/api/alpha/decisions", sent.path)
            assertEquals("Bearer sk-test", sent.getHeader("Authorization"))
            assertEquals(OpenRouterAttribution.REFERER, sent.getHeader("HTTP-Referer"))
            assertEquals("noul", JSONObject(sent.body.readUtf8()).getJSONObject("questions")
                .getJSONObject("decision").getString("type"))

            try {
                client.decide("sk-test", ask)
                fail("A 400 should throw.")
            } catch (e: ApiStatusException) {
                assertEquals(400, e.code)
                assertTrue(e.message!!.contains("capability mismatch: audio"))
            }
            server.takeRequest()

            val models = client.models("sk-test")
            assertEquals("typesafe/jev-1.13", models.single().id)
            assertTrue("image" in models.single().inputModalities)
            assertEquals("/api/v1/models?output_modalities=decisions", server.takeRequest().path)
        }
    }
}
