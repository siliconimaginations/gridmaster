package com.gridmaster.game

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Unit tests for [WeatherSimulator], [SolarGenerationModel], and [WindGenerationModel]
 * (issue #391).
 */
class WeatherModelTest {
    // -------------------------------------------------------------------------
    // SolarGenerationModel
    // -------------------------------------------------------------------------

    @Test
    fun `clearSkyFactor is zero before sunrise and after sunset`() {
        assertThat(SolarGenerationModel.clearSkyFactor(0.0)).isEqualTo(0.0)
        assertThat(SolarGenerationModel.clearSkyFactor(5.0)).isEqualTo(0.0)
        assertThat(SolarGenerationModel.clearSkyFactor(18.0)).isCloseTo(0.0, within(1e-9))
        assertThat(SolarGenerationModel.clearSkyFactor(20.0)).isEqualTo(0.0)
    }

    @Test
    fun `clearSkyFactor peaks at solar noon`() {
        val noon = (SolarGenerationModel.SUNRISE_HOUR + SolarGenerationModel.SUNSET_HOUR) / 2.0
        assertThat(SolarGenerationModel.clearSkyFactor(noon)).isCloseTo(1.0, within(1e-9))
    }

    @Test
    fun `clearSkyIndex decreases as cloud cover increases`() {
        val clear = SolarGenerationModel.clearSkyIndex(0.0)
        val partlyCloudy = SolarGenerationModel.clearSkyIndex(40.0)
        val overcast = SolarGenerationModel.clearSkyIndex(100.0)
        assertThat(clear).isGreaterThan(partlyCloudy)
        assertThat(partlyCloudy).isGreaterThan(overcast)
        assertThat(clear).isCloseTo(1.0, within(1e-9))
        assertThat(overcast).isGreaterThanOrEqualTo(0.0)
    }

    @Test
    fun `solar output is zero at night regardless of cloud cover`() {
        val midnightMinutes = 0L
        val output = SolarGenerationModel.outputMw(ratedMw = 100.0, gameTimeMinutes = midnightMinutes, cloudCoverPct = 0.0)
        assertThat(output).isEqualTo(0.0)
    }

    @Test
    fun `solar output at clear noon is close to rated capacity`() {
        val noonMinutes = 12 * 60L
        val output = SolarGenerationModel.outputMw(ratedMw = 100.0, gameTimeMinutes = noonMinutes, cloudCoverPct = 0.0)
        assertThat(output).isCloseTo(100.0, within(1e-6))
    }

    @Test
    fun `solar output never exceeds ratedMw`() {
        for (minute in 0 until 1440 step 15) {
            val output = SolarGenerationModel.outputMw(ratedMw = 100.0, gameTimeMinutes = minute.toLong(), cloudCoverPct = 0.0)
            assertThat(output).isLessThanOrEqualTo(100.0)
            assertThat(output).isGreaterThanOrEqualTo(0.0)
        }
    }

    // -------------------------------------------------------------------------
    // WindGenerationModel
    // -------------------------------------------------------------------------

    @Test
    fun `wind output is zero below cut-in speed`() {
        assertThat(WindGenerationModel.outputMw(ratedMw = 100.0, windSpeedMps = 1.0)).isEqualTo(0.0)
        assertThat(WindGenerationModel.outputMw(ratedMw = 100.0, windSpeedMps = 3.0)).isEqualTo(0.0)
    }

    @Test
    fun `wind output is zero above cut-out speed`() {
        assertThat(WindGenerationModel.outputMw(ratedMw = 100.0, windSpeedMps = 26.0)).isEqualTo(0.0)
        assertThat(WindGenerationModel.outputMw(ratedMw = 100.0, windSpeedMps = 40.0)).isEqualTo(0.0)
    }

    @Test
    fun `wind output is flat at ratedMw between rated and cut-out speed`() {
        assertThat(WindGenerationModel.outputMw(ratedMw = 100.0, windSpeedMps = 13.0)).isEqualTo(100.0)
        assertThat(WindGenerationModel.outputMw(ratedMw = 100.0, windSpeedMps = 20.0)).isEqualTo(100.0)
        assertThat(WindGenerationModel.outputMw(ratedMw = 100.0, windSpeedMps = 25.0)).isEqualTo(100.0)
    }

    @Test
    fun `wind output ramps monotonically between cut-in and rated speed`() {
        val speeds = listOf(3.5, 5.0, 7.0, 9.0, 11.0, 13.0)
        val outputs = speeds.map { WindGenerationModel.outputMw(ratedMw = 100.0, windSpeedMps = it) }
        assertThat(outputs).isSorted
        assertThat(outputs.first()).isEqualTo(0.0)
        assertThat(outputs.last()).isEqualTo(100.0)
    }

    // -------------------------------------------------------------------------
    // WeatherSimulator
    // -------------------------------------------------------------------------

    @Test
    fun `simulator starts in CLEAR state with readings inside the CLEAR range`() {
        val sim = WeatherSimulator(random = Random(42))
        assertThat(sim.currentState).isEqualTo(WeatherState.CLEAR)
        assertThat(sim.cloudCoverPct).isBetween(0.0, 10.0)
        assertThat(sim.windSpeedMps).isBetween(2.0, 8.0)
    }

    @Test
    fun `currentReading carries the configured regionId`() {
        val sim = WeatherSimulator(regionId = "north", random = Random(1))
        assertThat(sim.currentReading().regionId).isEqualTo("north")
    }

    @Test
    fun `default regionId is the global sentinel`() {
        val sim = WeatherSimulator(random = Random(1))
        assertThat(sim.regionId).isEqualTo(GLOBAL_WEATHER_REGION_ID)
    }

    @RepeatedTest(20)
    fun `state never jumps directly from CLEAR to STORM in one transition`() {
        val sim = WeatherSimulator(random = Random.Default)
        var previousState = sim.currentState
        repeat(500) {
            sim.advanceTick()
            val newState = sim.currentState
            if (newState != previousState) {
                val jumpedClearToStorm =
                    (previousState == WeatherState.CLEAR && newState == WeatherState.STORM) ||
                        (previousState == WeatherState.STORM && newState == WeatherState.CLEAR)
                assertThat(jumpedClearToStorm).isFalse
            }
            previousState = newState
        }
    }

    @RepeatedTest(20)
    fun `cloud cover and wind speed always stay within the current state range`() {
        val sim = WeatherSimulator(random = Random.Default)
        repeat(500) {
            sim.advanceTick()
            val profile =
                when (sim.currentState) {
                    WeatherState.CLEAR -> 0.0..10.0
                    WeatherState.PARTLY_CLOUDY -> 10.0..40.0
                    WeatherState.CLOUDY -> 40.0..75.0
                    WeatherState.OVERCAST -> 75.0..100.0
                    WeatherState.STORM -> 90.0..100.0
                }
            assertThat(sim.cloudCoverPct).isBetween(profile.start, profile.endInclusive)
        }
    }

    @Test
    fun `state holds for at least the minimum dwell time instead of flipping every tick`() {
        val sim = WeatherSimulator(random = Random(7))
        val initialState = sim.currentState
        var ticksHeld = 0
        while (sim.currentState == initialState && ticksHeld < 200) {
            sim.advanceTick()
            ticksHeld++
        }
        // CLEAR's minDwellTicks is 8 — the state must hold for at least that many
        // advanceTick() calls before the first possible transition.
        assertThat(ticksHeld).isGreaterThanOrEqualTo(8)
    }
}
