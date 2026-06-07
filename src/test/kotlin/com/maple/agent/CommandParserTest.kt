package com.maple.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class CommandParserTest {
    @Test
    fun `extracts executable commands and filters signatures`() {
        val commands = CommandParser.extractExecutableCommands(
            """
            Available: !move(direction,ticks) !mineBlock(x: number, y: number, z: number)
            Plan:
            !moveTo(12,64,-3)
            !jump()
            """.trimIndent()
        )

        assertEquals(listOf("!moveTo(12,64,-3)", "!jump()"), commands)
    }

    @Test
    fun `parses quoted comma arguments`() {
        val params = CommandParser.parsePositionalParams(""""hello, world", true, 3.5, 'left,right'""")

        assertEquals("hello, world", params["param0"])
        assertEquals(true, params["param1"])
        assertEquals(3.5, params["param2"])
        assertEquals("left,right", params["param3"])
    }

    @Test
    fun `parses escaped quotes inside quoted arguments`() {
        val params = CommandParser.parsePositionalParams(""""say \"hi\"", false""")

        assertEquals("say \"hi\"", params["param0"])
        assertEquals(false, params["param1"])
    }
}
