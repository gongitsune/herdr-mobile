package io.herdrmobile.android.ui

import io.herdrmobile.core.workspace.Space
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*
import io.herdrmobile.core.model.HcJson
import io.herdrmobile.core.model.Terminal

class NewTerminalTest {
    @Test fun desktopLabelsUseLocalSpaceIdAndFreshTab() {
        val local = Space("sp-local", "Local", "/work", emptyList())
        val remote = Space("machine/sp-remote", "Remote", "/work", emptyList(), "machine")
        assertEquals(mapOf("herdr.space" to "sp-local", "herdr.tab" to "tb-new", "herdr.seen" to "0"), newTabLabels(local, "tb-new"))
        assertEquals("sp-remote", newTabLabels(remote, "tb-new")["herdr.space"])
        assertNotEquals(newTabLabels(local, "tb-one"), newTabLabels(local, "tb-two"))
    }
    @Test fun launchParamsKeepTermAndTargetForEveryChoice() {
        val space = Space("mac/sp1", "Remote", "/work", emptyList(), "mac")
        for (choice in 0..3) {
            val params = newTerminalParams(space, choice, "printf 'hello'")
            assertEquals("mac", params["on"]?.jsonPrimitive?.content)
            assertEquals(true, params["keep"]?.jsonPrimitive?.boolean)
            assertEquals("xterm-256color", params["env"]?.jsonObject?.get("TERM")?.jsonPrimitive?.content)
            assertEquals("sp1", params["labels"]?.jsonObject?.get("herdr.space")?.jsonPrimitive?.content)
            assertTrue(params["labels"]!!.jsonObject["herdr.tab"]!!.jsonPrimitive.content.matches(Regex("tb[0-9a-f]{32}")))
            if (choice == 3) { assertEquals("printf 'hello'", params["line"]?.jsonPrimitive?.content); assertNull(params["argv"]) }
            else assertNotNull(params["argv"])
        }
        assertNotEquals(freshTabId(), freshTabId())
    }
    @Test fun agentsLoadHostLoginPathAndLeaveInteractiveShell() {
        for ((choice, agent) in listOf(1 to "claude", 2 to "codex")) {
            val argv = newTerminalArgv(choice)
            assertEquals(listOf("sh", "-c"), argv.take(2))
            assertEquals(agent, argv.last())
            assertTrue(argv[2].contains("-lic"))
            assertTrue(argv[2].contains("\"\$@\"; exec \"\$0\" -i"))
        }
        assertEquals(listOf("sh", "-c", "exec \"\${SHELL:-/bin/sh}\" -i"), newTerminalArgv(0))
    }
    @Test fun forwardedAddressIsUsedVerbatimAsListId() {
        // core remote.rs forward_once/localize and mirror install both qualify address.
        val response = HcJson.decodeFromString<Terminal>("""{"address":"mac/t42","state":"live"}""")
        assertEquals("mac/t42", response.id)
    }
}
