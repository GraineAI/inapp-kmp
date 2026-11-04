package ai.graine.inapp.kmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Kotlin moved forward and Xcode did not: the app must say so, because nothing else will. */
class LinkedIosSdkTest {
    @Test
    fun theExpectedSdkOrANewerOneIsNoProblem() {
        assertNull(LinkedIosSdk.problem("0.14.10", expected = "0.14.10"))
        assertNull(LinkedIosSdk.problem("0.14.11", expected = "0.14.10"))
        assertNull(LinkedIosSdk.problem("0.15.0", expected = "0.14.10"))
        assertNull(LinkedIosSdk.problem("1.0", expected = "0.14.10"))
    }

    @Test
    fun anOlderSdkIsReportedAndNamed() {
        val problem = assertNotNull(LinkedIosSdk.problem("0.14.9", expected = "0.14.10"))
        assertEquals("ios_sdk_older", problem.code)
        assertTrue("GraineInApp 0.14.9" in problem.message, problem.message)
        assertTrue("GraineInApp 0.14.10" in problem.message, problem.message)
        assertTrue("GraineInAppRTC" in problem.message, "the fix names both packages: ${problem.message}")
    }

    @Test
    fun anSdkTooOldToSayIsReportedAsOlderThanTheFirstThatCan() {
        val problem = assertNotNull(LinkedIosSdk.problem(null, expected = "0.14.10"))
        assertEquals("ios_sdk_older", problem.code)
        assertTrue("older than ${LinkedIosSdk.FIRST_THAT_SAYS}" in problem.message, problem.message)
    }

    @Test
    fun theComparisonIsByNumberNotByText() {
        assertTrue(LinkedIosSdk.isOlder("0.14.3", "0.14.10"), "as text, 0.14.3 sorts after 0.14.10")
        assertFalse(LinkedIosSdk.isOlder("0.14.10", "0.14.3"))
        assertFalse(LinkedIosSdk.isOlder("0.14.10", "0.14.10"))
        assertTrue(LinkedIosSdk.isOlder("0.9.0", "0.14.0"))
        assertFalse(LinkedIosSdk.isOlder("0.14", "0.14.0"), "a missing part is a zero")
        assertTrue(LinkedIosSdk.isOlder("0.14", "0.14.1"))
    }

    @Test
    fun aVersionThatCannotBeReadRaisesNoAlarm() {
        for (odd in listOf("", "unknown", "0.14.x", "0.14.10-beta", "-1.0.0", "  ")) {
            assertNull(LinkedIosSdk.problem(odd, expected = "0.14.10"), "a false warning sends someone hunting: '$odd'")
        }
    }

    @Test
    fun theSpacesAroundAVersionDoNotMatter() {
        assertNull(LinkedIosSdk.problem(" 0.14.10 ", expected = "0.14.10"))
    }

    @Test
    fun theFirstSdkThatSaysIsNotAfterTheOneThisLibraryExpects() {
        assertFalse(LinkedIosSdk.isOlder(LinkedIosSdk.EXPECTED, LinkedIosSdk.FIRST_THAT_SAYS),
            "or every app on the expected SDK would be told it is older")
    }
}
