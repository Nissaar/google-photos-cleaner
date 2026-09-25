package xyz.photocleaner.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class RpcArgsTest {

    /** Google already accepts these exact bytes; building them differently must not change them. */
    @Test
    fun `wire format of the real calls is unchanged`() {
        assertEquals(
            """[null,1,["k1","k2"],3]""",
            RpcArgs.of(null, 1, listOf("k1", "k2"), 3).toString(),
        )
        assertEquals(
            """["page",1700000000000,200,null,1,3]""",
            RpcArgs.of("page", 1_700_000_000_000L, 200, null, 1, 3).toString(),
        )
        assertEquals("""["To Be Deleted",null,2]""", RpcArgs.of("To Be Deleted", null, 2).toString())
    }

    @Test
    fun `awkward album names survive the round trip`() {
        for (name in listOf("line\nbreak", "tab\there", "quote \" and \\ slash", "bell\u0007", "emoji 🗑️", "it's")) {
            val json = RpcArgs.of(name, null, 2).toString()
            val back = Json.parseToJsonElement(json).jsonArray[0].jsonPrimitive.content
            assertEquals(name, back)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unsupported values fail loudly instead of becoming null`() {
        RpcArgs.of(1.5)
    }
}
