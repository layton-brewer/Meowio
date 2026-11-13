package com.nuvio.tv.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAssistantTest {
    @Test
    fun readsPicksFromASuccessfulAnswer() {
        val body = """
            {"stop_reason":"end_turn","content":[
              {"type":"thinking","thinking":""},
              {"type":"text","text":"{\"picks\":[{\"title\":\"Arrival\",\"year\":2016,\"type\":\"movie\",\"reason\":\"Quiet sci-fi.\"},{\"title\":\"Dark\",\"year\":2017,\"type\":\"series\",\"reason\":\"Twisty.\"},{\"title\":\" \",\"year\":1,\"type\":\"movie\",\"reason\":\"\"}]}"}
            ]}
        """.trimIndent()
        val result = AiAssistant.parseResponse(200, body) as AiResult.Picks
        assertEquals(listOf("Arrival", "Dark"), result.picks.map { it.title })
        assertEquals(2016, result.picks[0].year)
        assertFalse(result.picks[0].isSeries)
        assertTrue(result.picks[1].isSeries)
    }

    @Test
    fun explainsFailures() {
        val badKey = AiAssistant.parseResponse(401, """{"error":{"message":"invalid x-api-key"}}""")
        assertEquals(AiResult.Failure(AiFailure.BAD_KEY, "invalid x-api-key"), badKey)
        assertEquals(AiFailure.RATE_LIMITED, (AiAssistant.parseResponse(429, "") as AiResult.Failure).kind)
        assertEquals(
            AiFailure.DECLINED,
            (AiAssistant.parseResponse(200, """{"stop_reason":"refusal","content":[]}""") as AiResult.Failure).kind
        )
        assertEquals(AiFailure.UNEXPECTED, (AiAssistant.parseResponse(200, "not json") as AiResult.Failure).kind)
    }

    @Test
    fun requestCarriesTasteHintsButNeverTheKey() {
        val body = AiAssistant.requestBody(
            prompt = "something funny",
            context = AiViewerContext(recentlyWatched = listOf("Severance", " "), library = listOf("Severance", "Heat"))
        )
        assertTrue(body.contains("claude-opus-5-5"))
        assertTrue(body.contains("something funny"))
        assertTrue(body.contains("Severance"))
        assertTrue(body.contains("Heat"))
        assertFalse(body.contains("x-api-key"))
        assertEquals("secret", AiAssistant.headers(" secret ")["x-api-key"])
    }
}
