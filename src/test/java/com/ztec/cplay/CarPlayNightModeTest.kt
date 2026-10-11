package com.ztec.cplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayNightModeTest {
    @Test fun overnightScheduleIncludesStartAndExcludesEnd() {
        val schedule = CarPlayNightSchedule(1080, 360)
        assertFalse(schedule.isNight(1079))
        assertTrue(schedule.isNight(1080))
        assertTrue(schedule.isNight(0))
        assertTrue(schedule.isNight(359))
        assertFalse(schedule.isNight(360))
    }
    @Test fun daytimeAndMatchingBoundaries() {
        val schedule = CarPlayNightSchedule(480, 600)
        assertFalse(schedule.isNight(479))
        assertTrue(schedule.isNight(480))
        assertTrue(schedule.isNight(599))
        assertFalse(schedule.isNight(600))
        for (minute in 0..1439) assertFalse(CarPlayNightSchedule(360, 360).isNight(minute))
    }
    @Test fun forcedModesIgnoreSystemAndSystemModeFollowsIt() {
        for (system in listOf(false, true)) {
            assertFalse(NightModeSettings.resolve(CarPlayNightMode.DAY, CarPlayNightSchedule(), system, 0))
            assertTrue(NightModeSettings.resolve(CarPlayNightMode.NIGHT, CarPlayNightSchedule(), system, 720))
            assertEquals(system, NightModeSettings.resolve(CarPlayNightMode.SYSTEM, CarPlayNightSchedule(), system, 720))
        }
        assertTrue(NightModeSettings.resolve(CarPlayNightMode.SCHEDULE, CarPlayNightSchedule(), false, 0))
        assertEquals(CarPlayNightMode.SYSTEM, CarPlayNightMode.fromKey("unknown"))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidTimesAreRejected() {
        CarPlayNightSchedule(-1, 600)
    }
}
