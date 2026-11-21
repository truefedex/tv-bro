package com.phlox.tvwebbrowser.profile

import com.phlox.tvwebbrowser.profile.ProfileLock.LockDelay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileLockTest {
    private class MapStore : ProfilePin.Store {
        val values = HashMap<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(values: Map<String, String?>) {
            for ((key, value) in values) if (value == null) this.values.remove(key) else this.values[key] = value
        }
    }

    private var clock = 1_000_000_000L
    private val store = MapStore()
    private val lock = ProfileLock(store) { clock }
    private val pin get() = lock.pin

    // the PIN

    @Test
    fun nothingIsLockedUntilAPinIsSet() {
        assertFalse(lock.enabled)
        assertFalse(lock.kidsActive)
        lock.enterKids()
        assertFalse(lock.kidsActive)
        lock.onMainLeftScreen()
        lock.onMainColdStart()
        assertFalse(lock.kidsActive)
    }

    @Test
    fun theRightPinPassesAndAWrongOneDoesNot() {
        pin.set("2580")
        assertEquals(ProfilePin.Result.Right, pin.check("2580"))
        assertTrue(pin.check("2581") is ProfilePin.Result.Wrong)
        assertTrue(pin.check("258") is ProfilePin.Result.Wrong)
        assertTrue(pin.check("25800") is ProfilePin.Result.Wrong)
    }

    @Test
    fun onlyASaltedHashIsKept() {
        pin.set("2580")
        assertFalse(store.values.values.any { "2580" in it })
        val first = store.values["pin_hash"]
        pin.set("2580")
        //a new salt each time
        assertNotEquals(first, store.values["pin_hash"])
        assertEquals(ProfilePin.Result.Right, pin.check("2580"))
    }

    @Test
    fun onlyFourDigitsAreAPin() {
        assertTrue(ProfilePin.isValid("0000"))
        assertFalse(ProfilePin.isValid("123"))
        assertFalse(ProfilePin.isValid("12a4"))
        assertFalse(ProfilePin.isValid("12345"))
    }

    @Test
    fun wrongTriesLeadToAPauseThatGrows() {
        pin.set("2580")
        repeat(ProfilePin.FREE_TRIES - 1) {
            val wrong = pin.check("0000") as ProfilePin.Result.Wrong
            assertEquals(0, wrong.waitMs)
            assertEquals(ProfilePin.FREE_TRIES - 1 - it, wrong.triesLeft)
        }
        assertEquals(30_000L, (pin.check("0000") as ProfilePin.Result.Wrong).waitMs)
        //the right PIN waits too
        assertTrue(pin.check("2580") is ProfilePin.Result.Wait)
        clock += 30_000
        assertEquals(60_000L, (pin.check("0000") as ProfilePin.Result.Wrong).waitMs)
        clock += 60_000
        assertEquals(ProfilePin.Result.Right, pin.check("2580"))
        //and starts the count again
        assertEquals(0, (pin.check("0000") as ProfilePin.Result.Wrong).waitMs)
    }

    @Test
    fun thePauseIsThirtyMinutesAtMost() {
        assertEquals(0L, ProfilePin.pauseAfter(ProfilePin.FREE_TRIES - 1))
        assertEquals(30 * 60_000L, ProfilePin.pauseAfter(ProfilePin.FREE_TRIES + 20))
        assertEquals(30 * 60_000L, ProfilePin.pauseAfter(1000))
    }

    @Test
    fun thePauseSurvivesARestartAndAClockTurnedBack() {
        pin.set("2580")
        repeat(ProfilePin.FREE_TRIES) { pin.check("0000") }
        val again = ProfilePin(store) { clock }
        assertEquals(30_000L, again.waitLeft())
        clock -= 3_600_000
        assertEquals(30_000L, again.waitLeft())
        clock += 29_000
        assertEquals(1_000L, again.waitLeft())
    }

    // the lock

    @Test
    fun turningTheProfileOnLeavesTheMainOneOpen() {
        lock.enable("2580")
        assertTrue(lock.enabled)
        assertFalse(lock.kidsActive)
    }

    @Test
    fun changingThePinKeepsTheProfileShown() {
        lock.enable("2580")
        lock.enterKids()
        lock.enable("1111")
        assertTrue(lock.kidsActive)
        assertEquals(ProfilePin.Result.Right, pin.check("1111"))
    }

    @Test
    fun leavingTheScreenLocksTheMainProfile() {
        lock.enable("2580")
        lock.onMainLeftScreen()
        assertTrue(lock.kidsActive)
        lock.enterMain()
        assertFalse(lock.kidsActive)
    }

    @Test
    fun aDelayKeepsTheMainProfileForAWhile() {
        lock.enable("2580")
        lock.lockDelay = LockDelay.MINUTES_5
        lock.onMainLeftScreen()
        assertFalse(lock.kidsActive)
        clock += 4 * 60_000
        lock.onMainBackOnScreen()
        assertFalse(lock.kidsActive)

        lock.onMainLeftScreen()
        clock += 5 * 60_000
        lock.onMainBackOnScreen()
        assertTrue(lock.kidsActive)
    }

    @Test
    fun aClockTurnedBackLocks() {
        lock.enable("2580")
        lock.lockDelay = LockDelay.MINUTES_30
        lock.onMainLeftScreen()
        clock -= 60_000
        lock.onMainBackOnScreen()
        assertTrue(lock.kidsActive)
    }

    @Test
    fun onlyTheUserLocksWhenAskedSo() {
        lock.enable("2580")
        lock.lockDelay = LockDelay.MANUAL
        lock.onMainLeftScreen()
        clock += 24 * 3_600_000L
        lock.onMainBackOnScreen()
        lock.onMainColdStart()
        assertFalse(lock.kidsActive)
        lock.enterKids()
        assertTrue(lock.kidsActive)
    }

    /** The device was switched off while the main profile was shown: nothing told that it left the screen */
    @Test
    fun aNewProcessLocksTheMainProfile() {
        lock.enable("2580")
        lock.onMainColdStart()
        assertTrue(lock.kidsActive)
    }

    /** The system killed the process in the background: the delay still counts from when it left the screen */
    @Test
    fun aNewProcessKeepsTheDelay() {
        lock.enable("2580")
        lock.lockDelay = LockDelay.MINUTES_30
        lock.onMainLeftScreen()
        clock += 10 * 60_000
        ProfileLock(store) { clock }.onMainColdStart()
        assertFalse(lock.kidsActive)

        lock.onMainLeftScreen()
        clock += 31 * 60_000
        ProfileLock(store) { clock }.onMainColdStart()
        assertTrue(lock.kidsActive)
    }

    @Test
    fun turningTheProfileOffUnlocksEverything() {
        lock.enable("2580")
        lock.enterKids()
        lock.disable()
        assertFalse(lock.enabled)
        assertFalse(lock.kidsActive)
        //turned on again, it starts open
        lock.enable("1111")
        assertFalse(lock.kidsActive)
    }

    @Test
    fun theKidsProfileRefusesWhatReachesOutOfIt() {
        val kids = ProfilePolicy(kids = true)
        assertFalse(kids.settings || kids.incognito || kids.downloads || kids.sendToTv || kids.externalApps ||
            kids.deleteHistory || kids.pictureInPicture || kids.appUpdates || kids.homeChannels || kids.autofill)
        val main = ProfilePolicy(kids = false)
        assertTrue(main.settings && main.incognito && main.downloads && main.sendToTv && main.externalApps &&
            main.deleteHistory && main.pictureInPicture && main.appUpdates && main.homeChannels && main.autofill)
    }

    @Test
    fun kidsDownloadsAreAllowedUntilTurnedOff() {
        assertTrue(lock.kidsDownloads)
        lock.kidsDownloads = false
        assertFalse(lock.kidsDownloads)
        assertFalse(ProfileLock(store) { clock }.kidsDownloads)
        lock.kidsDownloads = true
        assertTrue(lock.kidsDownloads)
    }

    /** Downloads of the kids profile are its own: the folder of all files and sharing them stay of the main profile */
    @Test
    fun theKidsProfileDownloadsOnlyWhileAllowed() {
        assertTrue(ProfilePolicy(kids = true, kidsDownloads = true).downloads)
        assertFalse(ProfilePolicy(kids = true, kidsDownloads = true).deviceFiles)
        assertTrue(ProfilePolicy(kids = false).deviceFiles)
    }
}
