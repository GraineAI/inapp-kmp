package ai.graine.inapp.kmp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The face stage's clock (contract/avatar-stage.json): the readings StageTest replays on every
 * target are the contract's, every one of them.
 */
class StageVendoredTest {
    @Test
    fun theClockReadingsAreTheContracts() {
        // Set by the build where this library sits beside the shared contract (the monorepo).
        val dir = System.getProperty("graine.contractDir")
        assumeTrue("an exported checkout carries no contract folder: the copy in StageTest is the only one", dir != null)
        val contract = File(dir!!, "avatar-stage.json")
        assertTrue("$contract is missing", contract.isFile)
        val clock = JSONObject(contract.readText()).getJSONArray("clock")
        val readings = (0 until clock.length()).map { i -> clock.getJSONObject(i).let { it.getLong("ms") to it.getString("text") } }
        assertEquals("StageTest's STAGE_CLOCK must be contract/avatar-stage.json's clock: copy it over", readings, STAGE_CLOCK)
    }
}
