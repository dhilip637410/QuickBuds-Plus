package com.spizganed.quickbuds.bluetooth

import com.spizganed.quickbuds.bluetooth.FastPairBattery.Levels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class FastPairBatteryTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** Flags, a 4-byte account key filter, a 1-byte salt, then the battery field. */
    private fun advert(batteryType: Int, left: Int, right: Int, case: Int) =
        bytes(0x00, 0x40, 1, 2, 3, 4, 0x11, 0x55, 0x30 or batteryType, left, right, case)

    @Test fun readsLeftRightAndCase() {
        val l = FastPairBattery.parse(advert(3, 85, 0x80 or 60, 40))!!
        assertEquals(Levels(85, 60, 40, false, true, false), l)
    }

    @Test fun readsTheHideUiForm() {
        assertEquals(70, FastPairBattery.parse(advert(4, 70, 71, 72))!!.left)
    }

    @Test fun unknownPartsAreMinusOne() {
        val l = FastPairBattery.parse(advert(3, 90, 0x7F, 0x7F))!!
        assertEquals(Levels(90, -1, -1, false, false, false), l)
    }

    @Test fun noBatteryFieldIsNull() {
        assertNull(FastPairBattery.parse(bytes(0x00, 0x40, 1, 2, 3, 4, 0x11, 0x55)))
    }

    @Test fun discoverableModelIdIsNull() {
        assertNull(FastPairBattery.parse(bytes(0x2C, 0xB2, 0x2E)))   // a 3-byte model id
    }

    @Test fun truncatedIsNull() {
        assertNull(FastPairBattery.parse(bytes(0x00, 0x40, 1, 2)))
    }

    @Test fun allUnknownIsNull() {
        assertNull(FastPairBattery.parse(advert(3, 0x7F, 0x7F, 0x7F)))
    }

    /** Message Stream bodies as an Airdopes 800 sends them (`03 03 00 03` + these). */
    @Test fun readsMessageStreamBattery() {
        assertEquals(Levels(100, 100, 99, false, false, false), FastPairBattery.levels(bytes(0x64, 0x64, 0x63)))
        assertEquals(Levels(-1, 100, 99, false, false, false), FastPairBattery.levels(bytes(0x7F, 0x64, 0x63)))
        assertNull(FastPairBattery.levels(bytes(0x64, 0x64)))
    }

    private val ours = Levels(80, 75, 50, false, false, false)
    private val theirs = Levels(20, 25, 90, false, false, false)

    @Test fun oneCloseBroadcastIsUsed() {
        val p = FastPairBattery.Picker()
        p.onAdvert("A", -55, ours, 0)
        assertEquals(ours, p.pick(1_000, standardLevel = 75))
    }

    @Test fun twoBroadcastersThatBothFitAndAreEquallyCloseAreAmbiguous() {
        val p = FastPairBattery.Picker()
        p.onAdvert("A", -55, ours, 0)
        p.onAdvert("B", -60, Levels(78, 74, 40, false, false, false), 500)
        assertNull(p.pick(1_000, standardLevel = 76))
    }

    @Test fun aSecondBroadcastThatDoesNotFitIsNoProblem() {
        val p = FastPairBattery.Picker()
        p.onAdvert("A", -55, ours, 0)
        p.onAdvert("B", -60, theirs, 500)   // someone else's, 20-25%
        assertEquals(ours, p.pick(1_000, standardLevel = 75))
    }

    @Test fun aClearlyCloserBroadcastWins() {
        val p = FastPairBattery.Picker()
        p.onAdvert("A", -45, ours, 0)
        p.onAdvert("B", -70, Levels(78, 74, 40, false, false, false), 500)
        assertEquals(ours, p.pick(1_000, standardLevel = 76))
    }

    @Test fun oneBudInTheCaseWithTheCaseReportedIsUsed() {
        // boAt Airdopes 800, 2026-10-13: Android's own record said left unknown, right 70, case 14.
        val boat = Levels(-1, 70, 14, false, false, false)
        val p = FastPairBattery.Picker()
        p.onAdvert("A", -50, boat, 0)
        assertEquals(boat, p.pick(1_000, standardLevel = 70))
    }

    @Test fun theSupportedBudsOwnBroadcastIsSetAside() {
        val nord = Levels(80, 80, 100, false, false, false)   // what QuickBuds reads from them over RFCOMM
        val boat = Levels(70, 72, 14, false, false, false)
        val p = FastPairBattery.Picker()
        p.onAdvert("N", -40, nord, 0)
        p.onAdvert("B", -55, boat, 0)
        assertEquals(boat, p.pick(1_000, standardLevel = 75, own = nord))
    }

    @Test fun theVerdictSaysWhy() {
        val p = FastPairBattery.Picker()
        assertEquals("no broadcast heard", p.decide(0, 70).reason)
        p.onAdvert("A", -50, ours, 0)
        assertTrue(p.decide(1_000, null).reason.startsWith("no standard level"))
        assertTrue(p.decide(1_000, 20).reason.startsWith("none fits"))
    }

    @Test fun aFarBroadcastIsNotTrusted() {
        val p = FastPairBattery.Picker()
        p.onAdvert("B", -88, theirs, 0)
        assertNull(p.pick(1_000, standardLevel = null))
    }

    @Test fun levelsThatDisagreeWithTheStandardLevelAreRejected() {
        val p = FastPairBattery.Picker()
        p.onAdvert("B", -50, theirs, 0)   // someone else's, 20-25%, while ours report 80%
        assertNull(p.pick(1_000, standardLevel = 80))
    }

    @Test fun oldBroadcastsAgeOut() {
        val p = FastPairBattery.Picker(windowMs = 30_000)
        p.onAdvert("B", -60, theirs, 0)
        p.onAdvert("A", -55, ours, 40_000)
        assertEquals(ours, p.pick(41_000, standardLevel = 78))
    }

    @Test fun withoutTheDeviceOwnLevelNothingIsTrusted() {
        // A neckband that has not reported its battery yet: nothing to check a broadcast against.
        val p = FastPairBattery.Picker()
        p.onAdvert("A", -50, ours, 0)
        assertNull(p.pick(1_000, standardLevel = null))
    }

    @Test fun listensForOnePairOfEarbudsOnly() {
        assertTrue(FastPairBattery.allowed(listOf(DeviceCatalog.Kind.TWS), cardOn = true))
        assertTrue(FastPairBattery.allowed(listOf(null), cardOn = true))
    }

    @Test fun noListeningForANeckbandHeadphonesOrASpeaker() {
        for (k in listOf(DeviceCatalog.Kind.NECKBAND, DeviceCatalog.Kind.HEADPHONES, DeviceCatalog.Kind.SPEAKER)) {
            assertFalse(k.name, FastPairBattery.allowed(listOf(k), cardOn = true))
        }
    }

    @Test fun noListeningWhenItCouldMislead() {
        val tws = DeviceCatalog.Kind.TWS
        assertFalse(FastPairBattery.allowed(listOf(tws, tws), true))   // whose broadcast?
        assertFalse(FastPairBattery.allowed(emptyList(), true))
        assertFalse(FastPairBattery.allowed(listOf(tws), cardOn = false))
    }

    @Test fun aOneSidedBroadcastIsNotAPairOfEarbuds() {
        // Captured 2026-10-09: "L=-1 C=-1 R=70" was given to a neckband.
        val p = FastPairBattery.Picker()
        p.onAdvert("A", -50, Levels(-1, 70, -1, false, false, false), 0)
        assertNull(p.pick(1_000, standardLevel = 70))
    }
}
