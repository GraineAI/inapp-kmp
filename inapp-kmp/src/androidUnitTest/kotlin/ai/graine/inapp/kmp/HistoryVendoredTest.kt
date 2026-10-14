package ai.graine.inapp.kmp

import ai.graine.inapp.GraineChatTurn as NativeChatTurn
import ai.graine.inapp.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Date

/**
 * Conversations continue (contract §2.6) on Android: the vendored vectors are
 * the contract's, and the Android SDK's turns cross as the contract says.
 */
class HistoryVendoredTest {

    @Test
    fun theVendoredVectorsAreTheContracts() {
        // Set by the build where this library sits beside the shared contract (the monorepo).
        val dir = System.getProperty("graine.contractDir")
        assumeTrue("an exported checkout carries no contract folder: the vendored copy is the only one", dir != null)
        val contract = File(dir!!, "history.json")
        assertTrue("$contract is missing", contract.isFile)
        assertEquals(
            "src/commonTest/contract/history.json must be a copy of sdk/contract/history.json: copy it over",
            contract.readText(),
            HISTORY_JSON,
        )
    }

    @Test
    fun everyRestoredTurnCrossesFromTheAndroidSdkAsTheContractSays() {
        val turns = HistoryVectors.turns
        assertTrue(turns.size >= 20)
        turns.forEachIndexed { i, e ->
            val native = NativeChatTurn(
                id = e.id ?: "t${i + 1}",
                role = if (e.user) Role.CUSTOMER else Role.AGENT,
                text = e.text,
                at = Date((e.at ?: HistoryVectors.anyTime).toEpochMilliseconds()),
                delivery = when (e.delivery) {
                    GraineDelivery.SENDING -> NativeChatTurn.Delivery.SENDING
                    GraineDelivery.FAILED -> NativeChatTurn.Delivery.FAILED
                    else -> NativeChatTurn.Delivery.SENT
                },
                restored = e.restored,
                channel = if (e.channel == GraineChatChannel.VOICE) NativeChatTurn.Channel.VOICE else NativeChatTurn.Channel.CHAT,
            )
            HistoryVectors.assertCarried(e, AndroidMapping.turn(native))
        }
    }
}
