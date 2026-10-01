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
}
