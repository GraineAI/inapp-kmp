package ai.graine.inapp.kmp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * AN APP ON ITS OWN GRAINE INSTALL (docs: "Use your own Graine install").
 *
 * This library chooses no address itself: its configuration's `baseUrl` reaches
 * the Android and iOS SDKs as it was given, and every socket a conversation
 * opens is chosen there — where a session that names no conversation host
 * (`wsHost`) opens nothing for an install, never Graine's cloud (the Android
 * SDK's OwnInstallTest, the iOS SDK's OwnInstallTests). This pins both halves
 * of that on this side: the address is handed over untouched, and nothing here
 * names a socket host of its own to fall back to.
 */
class OwnInstallTest {
    private val own = "https://agents.example.com"

    @Test
    fun theInstallsAddressReachesTheAndroidSdkAsGiven() {
        assertEquals(own, AndroidMapping.config(GraineConfig(baseUrl = own, publishableKey = "pk_live_x")).baseUrl)
        assertEquals("$own/", AndroidMapping.config(GraineConfig(baseUrl = "$own/", publishableKey = "pk_live_x")).baseUrl)
    }

    @Test
    fun nothingInThisLibraryChoosesASocketHostOfItsOwn() {
        val sdk = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "inapp-kmp/src").isDirectory && File(it, "inapp-kmp-compose/src").isDirectory }
        assertTrue("the library's modules beside this one", sdk != null)
        val sources = listOf("inapp-kmp/src", "inapp-kmp-compose/src")
            .flatMap { File(sdk!!, it).listFiles().orEmpty().filter { set -> set.name.endsWith("Main") } }
            .flatMap { set -> set.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue("the runtime sources were read", sources.size > 10)
        val offenders = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                val code = line.trim()
                if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) return@mapIndexedNotNull null
                if (Regex("""wss?://|/chat/v1/|/control/v1/|/embed/v1/""").containsMatchIn(code)) "${file.name}:${i + 1}: $code" else null
            }
        }
        assertEquals("a socket address chosen here, not by the platform SDKs", emptyList<String>(), offenders)
    }
}
