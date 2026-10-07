package com.example.esp8266prj

import org.junit.Assert.*
import org.junit.Test

class WeatherStateTest {
    @Test fun freshnessRejectsMissingStaleAndFutureTelemetry() {
        assertFalse(WeatherState().fresh(100000))
        assertTrue(WeatherState(updatedAt = 80000).fresh(100000))
        assertFalse(WeatherState(updatedAt = 79999).fresh(100000))
        assertTrue(WeatherState(updatedAt = 105000).fresh(100000))
        assertFalse(WeatherState(updatedAt = 105001).fresh(100000))
    }

    @Test fun rainLevelFollowsFlowThresholds() {
        assertEquals(RainLevel.NO_DATA, WeatherState(flow = null).level)
        assertEquals(RainLevel.NO_DATA, WeatherState(flow = 0.0).level)
        assertEquals(RainLevel.NO_DATA, WeatherState(flow = -0.1).level)
        assertEquals(RainLevel.LIGHT, WeatherState(flow = 0.001).level)
        assertEquals(RainLevel.LIGHT, WeatherState(flow = 0.499).level)
        assertEquals(RainLevel.HEAVY, WeatherState(flow = 0.5).level)
        assertEquals(RainLevel.HEAVY, WeatherState(flow = 4.2).level)
    }
}
