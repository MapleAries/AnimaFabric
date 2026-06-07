package com.maple.llm

import kotlin.test.Test
import kotlin.test.assertEquals

class JsonExtractorTest {
    @Test
    fun `extracts first valid object from surrounding prose`() {
        val result = JsonExtractor.extractFirstObject("""Before {"tool":"jump"} after {"tool":"stop"}""")

        assertEquals("""{"tool":"jump"}""", result)
    }

    @Test
    fun `ignores braces inside json strings`() {
        val result = JsonExtractor.extractFirstObject("""Text {"message":"use {literal} braces","tool":"sendMessage"} done""")

        assertEquals("""{"message":"use {literal} braces","tool":"sendMessage"}""", result)
    }

    @Test
    fun `extracts last action object containing required keys`() {
        val result = JsonExtractor.extractLastObjectContaining(
            """Draft {"note":"ignore"} final {"goal":"go","steps":[]}""",
            listOf("tool", "pipeline", "action", "goal")
        )

        assertEquals("""{"goal":"go","steps":[]}""", result)
    }
}
