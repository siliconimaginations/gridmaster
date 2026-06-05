package com.gridmaster.engine.powerflow

import com.gridmaster.engine.model.Bus
import com.gridmaster.engine.model.GridNetwork
import com.gridmaster.engine.model.Line
import com.gridmaster.engine.model.TwoWindingsTransformer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit tests for [ViolationScanner] — no PowSyBl solver required. */
class ViolationScannerTest {
    private val scanner =
        ViolationScanner(
            ViolationThresholds(
                warningPercent = 90.0,
                alarmPercent = 100.0,
                criticalPercent = 110.0,
                voltageMinPu = 0.95,
                voltageMaxPu = 1.05,
            ),
        )

    // -------------------------------------------------------------------------
    // Voltage violations
    // -------------------------------------------------------------------------

    @Test
    fun `no voltage violation when bus is within limits`() {
        val snapshot = emptyNetwork().copy(buses = listOf(bus("B1", voltagePu = 1.0)))
        assertThat(scanner.scan(snapshot).filterIsInstance<NetworkViolation.VoltageViolation>()).isEmpty()
    }

    @Test
    fun `voltage violation WARNING when bus is just below minimum`() {
        // 0.94 pu: deviation 0.01 < alarmBand(0.03) → WARNING
        val snapshot = emptyNetwork().copy(buses = listOf(bus("B1", voltagePu = 0.94)))
        val violations = scanner.scan(snapshot).filterIsInstance<NetworkViolation.VoltageViolation>()
        assertThat(violations).hasSize(1)
        assertThat(violations.first().busId).isEqualTo("B1")
        assertThat(violations.first().severity).isEqualTo(ViolationSeverity.WARNING)
    }

    @Test
    fun `voltage violation ALARM when bus is moderately below minimum`() {
        // 0.92 pu: deviation 0.03 == alarmBand → ALARM
        val snapshot = emptyNetwork().copy(buses = listOf(bus("B1", voltagePu = 0.92)))
        val violations = scanner.scan(snapshot).filterIsInstance<NetworkViolation.VoltageViolation>()
        assertThat(violations).hasSize(1)
        assertThat(violations.first().severity).isEqualTo(ViolationSeverity.ALARM)
    }

    @Test
    fun `voltage violation CRITICAL when bus is severely below minimum`() {
        // 0.90 pu: deviation 0.05 == criticalBand → CRITICAL
        val snapshot = emptyNetwork().copy(buses = listOf(bus("B1", voltagePu = 0.90)))
        val violations = scanner.scan(snapshot).filterIsInstance<NetworkViolation.VoltageViolation>()
        assertThat(violations).hasSize(1)
        assertThat(violations.first().busId).isEqualTo("B1")
        assertThat(violations.first().severity).isEqualTo(ViolationSeverity.CRITICAL)
    }

    @Test
    fun `voltage violation CRITICAL when bus is severely above maximum`() {
        // 1.10 pu: deviation 0.05 == criticalBand → CRITICAL
        val snapshot = emptyNetwork().copy(buses = listOf(bus("B1", voltagePu = 1.10)))
        val violations = scanner.scan(snapshot).filterIsInstance<NetworkViolation.VoltageViolation>()
        assertThat(violations).hasSize(1)
        assertThat(violations.first().severity).isEqualTo(ViolationSeverity.CRITICAL)
    }

    @Test
    fun `voltage violation WARNING when bus is just above maximum`() {
        // 1.06 pu: deviation 0.01 < alarmBand → WARNING
        val snapshot = emptyNetwork().copy(buses = listOf(bus("B1", voltagePu = 1.06)))
        val violations = scanner.scan(snapshot).filterIsInstance<NetworkViolation.VoltageViolation>()
        assertThat(violations).hasSize(1)
        assertThat(violations.first().severity).isEqualTo(ViolationSeverity.WARNING)
    }

    @Test
    fun `no voltage violation when bus voltage is null (before first solve)`() {
        val snapshot = emptyNetwork().copy(buses = listOf(bus("B1", voltagePu = null)))
        assertThat(scanner.scan(snapshot)).isEmpty()
    }

    // -------------------------------------------------------------------------
    // Thermal violations — lines
    // -------------------------------------------------------------------------

    @Test
    fun `no thermal violation when line is within rating`() {
        val snapshot = emptyNetwork().copy(lines = listOf(line("L1", currentA = 300.0, ratingA = 500.0)))
        assertThat(scanner.scan(snapshot).filterIsInstance<NetworkViolation.ThermalViolation>()).isEmpty()
    }

    @Test
    fun `WARNING when line loading is between 90 and 100 percent`() {
        val snapshot = emptyNetwork().copy(lines = listOf(line("L1", currentA = 460.0, ratingA = 500.0)))
        val violations = scanner.scan(snapshot).filterIsInstance<NetworkViolation.ThermalViolation>()
        assertThat(violations).hasSize(1)
        assertThat(violations.first().severity).isEqualTo(ViolationSeverity.WARNING)
        assertThat(violations.first().loadingPercent).isEqualTo(92.0)
    }

    @Test
    fun `ALARM when line loading is exactly 100 percent`() {
        val snapshot = emptyNetwork().copy(lines = listOf(line("L1", currentA = 500.0, ratingA = 500.0)))
        val violations = scanner.scan(snapshot).filterIsInstance<NetworkViolation.ThermalViolation>()
        assertThat(violations.first().severity).isEqualTo(ViolationSeverity.ALARM)
    }

    @Test
    fun `CRITICAL when line loading exceeds 110 percent`() {
        val snapshot = emptyNetwork().copy(lines = listOf(line("L1", currentA = 560.0, ratingA = 500.0)))
        val violations = scanner.scan(snapshot).filterIsInstance<NetworkViolation.ThermalViolation>()
        assertThat(violations.first().severity).isEqualTo(ViolationSeverity.CRITICAL)
        assertThat(violations.first().equipmentType).isEqualTo(EquipmentType.LINE)
    }

    @Test
    fun `no thermal violation when line has no rating`() {
        val snapshot = emptyNetwork().copy(lines = listOf(line("L1", currentA = 999.0, ratingA = null)))
        assertThat(scanner.scan(snapshot).filterIsInstance<NetworkViolation.ThermalViolation>()).isEmpty()
    }

    @Test
    fun `no thermal violation when line current is null (before first solve)`() {
        val snapshot = emptyNetwork().copy(lines = listOf(line("L1", currentA = null, ratingA = 500.0)))
        assertThat(scanner.scan(snapshot).filterIsInstance<NetworkViolation.ThermalViolation>()).isEmpty()
    }

    @Test
    fun `thermal violation uses the higher of from and to current`() {
        val snapshot =
            emptyNetwork().copy(
                lines =
                    listOf(
                        Line(
                            "L1",
                            "L1",
                            "B1",
                            "B2",
                            ratingA = 500.0,
                            currentFromA = 200.0,
                            currentToA = 480.0,
                            resistanceOhm = 0.1,
                            reactanceOhm = 1.0,
                            shuntCapacitanceSiemens = 0.0,
                        ),
                    ),
            )
        val violations = scanner.scan(snapshot).filterIsInstance<NetworkViolation.ThermalViolation>()
        assertThat(violations).hasSize(1)
        assertThat(violations.first().currentA).isEqualTo(480.0)
        assertThat(violations.first().severity).isEqualTo(ViolationSeverity.WARNING)
    }

    @Test
    fun `transformer thermal violation is detected`() {
        // ratingMva=100 MVA at 220 kV HV → ~262 A HV-side rating
        // currentFromA=300 A (HV side, B1 at 220 kV) exceeds rating → violation
        val twt =
            TwoWindingsTransformer(
                id = "TX1",
                name = "TX1",
                fromBusId = "B1",
                toBusId = "B2",
                ratingMva = 100.0,
                currentFromA = 300.0, // > ~262 A (HV-side rating)
                currentToA = null,
                resistanceOhm = 0.1,
                reactanceOhm = 1.0,
                nominalVoltageHvKv = 220.0,
                nominalVoltageLvKv = 110.0,
            )
        val snapshot =
            emptyNetwork().copy(
                buses =
                    listOf(
                        Bus(id = "B1", name = "B1", nominalVoltageKv = 220.0),
                        Bus(id = "B2", name = "B2", nominalVoltageKv = 110.0),
                    ),
                twoWindingsTransformers = listOf(twt),
            )
        val violations = scanner.scan(snapshot).filterIsInstance<NetworkViolation.ThermalViolation>()
        assertThat(violations).hasSize(1)
        assertThat(violations.first().equipmentType).isEqualTo(EquipmentType.TWO_WINDINGS_TRANSFORMER)
    }

    @Test
    fun `multiple violations are all returned`() {
        val snapshot =
            emptyNetwork().copy(
                buses = listOf(bus("B1", voltagePu = 0.90), bus("B2", voltagePu = 1.0)),
                lines = listOf(line("L1", currentA = 560.0, ratingA = 500.0)),
            )
        assertThat(scanner.scan(snapshot)).hasSize(2)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun bus(
        id: String,
        voltagePu: Double?,
    ) = Bus(id = id, name = id, nominalVoltageKv = 220.0, voltageMagnitudePu = voltagePu)

    private fun line(
        id: String,
        currentA: Double?,
        ratingA: Double?,
    ) = Line(
        id = id,
        name = id,
        fromBusId = "B1",
        toBusId = "B2",
        ratingA = ratingA,
        currentFromA = currentA,
        currentToA = null,
        resistanceOhm = 0.1,
        reactanceOhm = 1.0,
        shuntCapacitanceSiemens = 0.0,
    )

    private fun emptyNetwork() =
        GridNetwork(
            id = "test",
            name = "test",
            buses = emptyList(),
            lines = emptyList(),
            twoWindingsTransformers = emptyList(),
            threeWindingsTransformers = emptyList(),
            generators = emptyList(),
            loads = emptyList(),
            shuntCompensators = emptyList(),
        )
}
