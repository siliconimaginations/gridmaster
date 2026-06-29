package com.gridmaster.engine.network

import com.gridmaster.engine.model.Bus
import com.gridmaster.engine.model.Generator
import com.gridmaster.engine.model.GridNetwork
import com.gridmaster.engine.model.Line
import com.gridmaster.engine.model.Load
import com.gridmaster.engine.model.NetworkMutation
import com.gridmaster.engine.model.Region
import com.gridmaster.engine.model.ShuntCompensator
import com.gridmaster.engine.model.ThreeWindingsTransformer
import com.gridmaster.engine.model.TwoWindingsTransformer
import com.powsybl.iidm.network.Network
import com.powsybl.iidm.network.ShuntCompensatorLinearModel
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import kotlin.math.sqrt
import com.powsybl.iidm.network.TwoWindingsTransformer as IidmTwoWindingsTransformer

@Component
class IidmNetworkMapperImpl(
    private val metadataProvider: GeneratorMetadataProvider = DefaultGeneratorMetadataProvider(),
) : IidmNetworkMapper {
    private val log = LoggerFactory.getLogger(IidmNetworkMapperImpl::class.java)

    // -------------------------------------------------------------------------
    // toGridNetwork
    // -------------------------------------------------------------------------

    override fun toGridNetwork(
        network: Network,
        regions: List<Region>,
    ): GridNetwork {
        val warnings = mutableListOf<String>()
        val busRegionIndex = buildBusRegionIndex(regions)

        val buses = mapBuses(network, busRegionIndex, warnings)
        val lines = mapLines(network, warnings)
        val twoWT = mapTwoWindingsTransformers(network, warnings)
        val threeWT = mapThreeWindingsTransformers(network, warnings)
        val generators = mapGenerators(network, warnings)
        val loads = mapLoads(network, warnings)
        val shunts = mapShuntCompensators(network, warnings)

        return GridNetwork(
            id = network.id,
            name = network.nameOrId,
            buses = buses,
            lines = lines,
            twoWindingsTransformers = twoWT,
            threeWindingsTransformers = threeWT,
            generators = generators,
            loads = loads,
            shuntCompensators = shunts,
            regions = regions,
            warnings = warnings,
        )
    }

    private fun buildBusRegionIndex(regions: List<Region>): Map<String, String> =
        buildMap {
            regions.forEach { region ->
                region.busIds.forEach { busId -> put(busId, region.id) }
            }
        }

    private fun mapBuses(
        network: Network,
        busRegionIndex: Map<String, String>,
        warnings: MutableList<String>,
    ): List<Bus> {
        val buses = mutableListOf<Bus>()
        for (vl in network.voltageLevels) {
            for (bus in vl.busBreakerView.buses) {
                val vMagnitudePu =
                    bus.v.orNull()?.let { v ->
                        val nominalV = vl.nominalV
                        if (nominalV > 0.0) v / nominalV else null
                    }
                buses +=
                    Bus(
                        id = bus.id,
                        name = bus.nameOrId,
                        nominalVoltageKv = vl.nominalV,
                        voltageMagnitudePu = vMagnitudePu,
                        voltageAngleDeg = bus.angle.orNull(),
                        regionId = busRegionIndex[bus.id],
                    )
            }
        }
        return buses
    }

    private fun mapLines(
        network: Network,
        warnings: MutableList<String>,
    ): List<Line> =
        network.lines.mapNotNull { line ->
            val fromBusId = line.terminal1.busBreakerView.connectableBus?.id
            val toBusId = line.terminal2.busBreakerView.connectableBus?.id
            if (fromBusId == null || toBusId == null) {
                warnings += "Line ${line.id} has a terminal with no connectable bus; skipping"
                return@mapNotNull null
            }
            Line(
                id = line.id,
                name = line.nameOrId,
                fromBusId = fromBusId,
                toBusId = toBusId,
                ratingA =
                    line.currentLimits1.orElse(null)?.permanentLimit
                        ?: line.currentLimits2.orElse(null)?.permanentLimit,
                currentFromA = line.terminal1.i.orNull(),
                currentToA = line.terminal2.i.orNull(),
                activePowerFromMw = line.terminal1.p.orNull(),
                reactivePowerFromMvar = line.terminal1.q.orNull(),
                connected = line.terminal1.isConnected && line.terminal2.isConnected,
                resistanceOhm = line.r,
                reactanceOhm = line.x,
                shuntCapacitanceSiemens = line.b1 + line.b2,
            )
        }

    private fun mapTwoWindingsTransformers(
        network: Network,
        warnings: MutableList<String>,
    ): List<TwoWindingsTransformer> =
        network.twoWindingsTransformers.mapNotNull { twt ->
            val fromBusId = twt.terminal1.busBreakerView.connectableBus?.id
            val toBusId = twt.terminal2.busBreakerView.connectableBus?.id
            if (fromBusId == null || toBusId == null) {
                warnings += "2W transformer ${twt.id} has a terminal with no connectable bus; skipping"
                return@mapNotNull null
            }
            val tapPosition = twt.ratioTapChanger?.tapPosition ?: 0
            TwoWindingsTransformer(
                id = twt.id,
                name = twt.nameOrId,
                fromBusId = fromBusId,
                toBusId = toBusId,
                ratingMva = twt.ratedSOrNull(),
                currentFromA = twt.terminal1.i.orNull(),
                currentToA = twt.terminal2.i.orNull(),
                activePowerFromMw = twt.terminal1.p.orNull(),
                reactivePowerFromMvar = twt.terminal1.q.orNull(),
                connected = twt.terminal1.isConnected && twt.terminal2.isConnected,
                resistanceOhm = twt.r,
                reactanceOhm = twt.x,
                ratioTapPosition = tapPosition,
                nominalVoltageHvKv = maxOf(twt.ratedU1, twt.ratedU2),
                nominalVoltageLvKv = minOf(twt.ratedU1, twt.ratedU2),
                nominalVoltageFromKv = twt.ratedU1,
                nominalVoltageToKv = twt.ratedU2,
            )
        }

    private fun mapThreeWindingsTransformers(
        network: Network,
        warnings: MutableList<String>,
    ): List<ThreeWindingsTransformer> =
        network.threeWindingsTransformers.mapNotNull { twt3 ->
            val bus1Id = twt3.leg1.terminal.busBreakerView.connectableBus?.id
            val bus2Id = twt3.leg2.terminal.busBreakerView.connectableBus?.id
            val bus3Id = twt3.leg3.terminal.busBreakerView.connectableBus?.id
            if (bus1Id == null || bus2Id == null || bus3Id == null) {
                warnings += "3W transformer ${twt3.id} has a terminal with no connectable bus; skipping"
                return@mapNotNull null
            }
            ThreeWindingsTransformer(
                id = twt3.id,
                name = twt3.nameOrId,
                bus1Id = bus1Id,
                bus2Id = bus2Id,
                bus3Id = bus3Id,
                ratingMva1 =
                    twt3.leg1.currentLimits.orElse(null)?.permanentLimit?.let { i ->
                        ampsToPowerMva(i, twt3.leg1.ratedU)
                    },
                ratingMva2 =
                    twt3.leg2.currentLimits.orElse(null)?.permanentLimit?.let { i ->
                        ampsToPowerMva(i, twt3.leg2.ratedU)
                    },
                ratingMva3 =
                    twt3.leg3.currentLimits.orElse(null)?.permanentLimit?.let { i ->
                        ampsToPowerMva(i, twt3.leg3.ratedU)
                    },
                current1A = twt3.leg1.terminal.i.orNull(),
                current2A = twt3.leg2.terminal.i.orNull(),
                current3A = twt3.leg3.terminal.i.orNull(),
                nominalVoltage1Kv = twt3.leg1.ratedU,
                nominalVoltage2Kv = twt3.leg2.ratedU,
                nominalVoltage3Kv = twt3.leg3.ratedU,
                resistanceOhm1 = twt3.leg1.r,
                reactanceOhm1 = twt3.leg1.x,
                resistanceOhm2 = twt3.leg2.r,
                reactanceOhm2 = twt3.leg2.x,
                resistanceOhm3 = twt3.leg3.r,
                reactanceOhm3 = twt3.leg3.x,
            )
        }

    private fun mapGenerators(
        network: Network,
        warnings: MutableList<String>,
    ): List<Generator> =
        network.generators.mapNotNull { gen ->
            val busId = gen.terminal.busBreakerView.connectableBus?.id
            if (busId == null) {
                warnings += "Generator ${gen.id} has no connectable bus; skipping"
                return@mapNotNull null
            }
            val nominalV = gen.terminal.voltageLevel.nominalV
            val targetVoltagePu =
                if (nominalV > 0.0) {
                    gen.targetV / nominalV
                } else {
                    warnings += "Generator ${gen.id} has non-positive nominalV ($nominalV kV); defaulting targetVoltagePu to 1.0"
                    1.0
                }
            val meta = metadataProvider.getMetadata(gen.id)
            Generator(
                id = gen.id,
                name = gen.nameOrId,
                busId = busId,
                minActivePowerMw = gen.minP,
                maxActivePowerMw = gen.maxP,
                targetActivePowerMw = gen.targetP,
                targetReactivePowerMvar = gen.targetQ,
                targetVoltagePu = targetVoltagePu,
                connected = gen.terminal.isConnected,
                fuelType = meta.fuelType,
                marginalCostPerMwh = meta.marginalCostPerMwh,
            )
        }

    private fun mapLoads(
        network: Network,
        warnings: MutableList<String>,
    ): List<Load> =
        network.loads.mapNotNull { load ->
            val busId = load.terminal.busBreakerView.connectableBus?.id
            if (busId == null) {
                warnings += "Load ${load.id} has no connectable bus; skipping"
                return@mapNotNull null
            }
            Load(
                id = load.id,
                name = load.nameOrId,
                busId = busId,
                activePowerMw = load.p0,
                reactivePowerMvar = load.q0,
                connected = load.terminal.isConnected,
            )
        }

    private fun mapShuntCompensators(
        network: Network,
        warnings: MutableList<String>,
    ): List<ShuntCompensator> =
        network.shuntCompensators.mapNotNull { sc ->
            val busId = sc.terminal.busBreakerView.connectableBus?.id
            if (busId == null) {
                warnings += "ShuntCompensator ${sc.id} has no connectable bus; skipping"
                return@mapNotNull null
            }
            val bPerSection =
                when {
                    sc.modelType == com.powsybl.iidm.network.ShuntCompensatorModelType.LINEAR ->
                        sc.getModel(ShuntCompensatorLinearModel::class.java).bPerSection
                    else -> {
                        warnings += "ShuntCompensator ${sc.id} has non-linear model; susceptance per section set to 0"
                        0.0
                    }
                }
            ShuntCompensator(
                id = sc.id,
                name = sc.nameOrId,
                busId = busId,
                susceptanceSiemensPerSection = bPerSection,
                maximumSectionCount = sc.maximumSectionCount,
                currentSectionCount = sc.sectionCount,
                connected = sc.terminal.isConnected,
            )
        }

    // -------------------------------------------------------------------------
    // applyMutation
    // -------------------------------------------------------------------------

    override fun applyMutation(
        network: Network,
        mutation: NetworkMutation,
    ): Result<Network> =
        runCatching {
            when (mutation) {
                is NetworkMutation.SetGeneratorOutput -> {
                    val gen =
                        network.getGenerator(mutation.generatorId)
                            ?: throw InvalidMutationException("Generator not found: ${mutation.generatorId}")
                    require(mutation.targetPMw >= gen.minP && mutation.targetPMw <= gen.maxP) {
                        "targetPMw ${mutation.targetPMw} outside [${gen.minP}, ${gen.maxP}] for ${mutation.generatorId}"
                    }
                    gen.targetP = mutation.targetPMw
                }

                is NetworkMutation.SetGeneratorVoltage -> {
                    val gen =
                        network.getGenerator(mutation.generatorId)
                            ?: throw InvalidMutationException("Generator not found: ${mutation.generatorId}")
                    val nominalV = gen.terminal.voltageLevel.nominalV
                    require(nominalV > 0.0) {
                        "Generator ${mutation.generatorId} is on a voltage level with non-positive nominal voltage"
                    }
                    gen.targetV = mutation.targetVoltagePu * nominalV
                }

                is NetworkMutation.TripLine -> {
                    val line =
                        network.getLine(mutation.lineId)
                            ?: throw InvalidMutationException("Line not found: ${mutation.lineId}")
                    line.terminal1.disconnect()
                    line.terminal2.disconnect()
                }

                is NetworkMutation.ConnectLine -> {
                    val line =
                        network.getLine(mutation.lineId)
                            ?: throw InvalidMutationException("Line not found: ${mutation.lineId}")
                    if (!line.terminal1.connect() || !line.terminal2.connect()) {
                        throw InvalidMutationException("Line ${mutation.lineId} terminal could not be reconnected")
                    }
                }

                is NetworkMutation.TripGenerator -> {
                    val gen =
                        network.getGenerator(mutation.generatorId)
                            ?: throw InvalidMutationException("Generator not found: ${mutation.generatorId}")
                    gen.terminal.disconnect()
                }

                is NetworkMutation.ConnectGenerator -> {
                    val gen =
                        network.getGenerator(mutation.generatorId)
                            ?: throw InvalidMutationException("Generator not found: ${mutation.generatorId}")
                    if (!gen.terminal.connect()) {
                        throw InvalidMutationException("Generator ${mutation.generatorId} terminal could not be reconnected")
                    }
                }

                is NetworkMutation.SetTapPosition -> {
                    val twt =
                        network.getTwoWindingsTransformer(mutation.transformerId)
                            ?: throw InvalidMutationException("Transformer not found: ${mutation.transformerId}")
                    val rtc =
                        twt.ratioTapChanger
                            ?: throw InvalidMutationException("Transformer ${mutation.transformerId} has no ratio tap changer")
                    require(mutation.tapPosition in rtc.lowTapPosition..rtc.highTapPosition) {
                        "tapPosition ${mutation.tapPosition} out of range [${rtc.lowTapPosition}, ${rtc.highTapPosition}]"
                    }
                    rtc.tapPosition = mutation.tapPosition
                }

                is NetworkMutation.SetLoadPower -> {
                    val load =
                        network.getLoad(mutation.loadId)
                            ?: throw InvalidMutationException("Load not found: ${mutation.loadId}")
                    load.p0 = mutation.activePowerMw
                    mutation.reactivePowerMvar?.let { load.q0 = it }
                }

                is NetworkMutation.ConnectLoad -> {
                    val load =
                        network.getLoad(mutation.loadId)
                            ?: throw InvalidMutationException("Load not found: ${mutation.loadId}")
                    if (!load.terminal.connect()) {
                        throw InvalidMutationException("Load ${mutation.loadId} terminal could not be reconnected")
                    }
                }

                is NetworkMutation.DisconnectLoad -> {
                    val load =
                        network.getLoad(mutation.loadId)
                            ?: throw InvalidMutationException("Load not found: ${mutation.loadId}")
                    load.terminal.disconnect()
                }

                is NetworkMutation.SetShuntSections -> {
                    val sc =
                        network.getShuntCompensator(mutation.shuntCompensatorId)
                            ?: throw InvalidMutationException("ShuntCompensator not found: ${mutation.shuntCompensatorId}")
                    require(mutation.sectionCount in 0..sc.maximumSectionCount) {
                        "sectionCount ${mutation.sectionCount} out of range [0, ${sc.maximumSectionCount}]"
                    }
                    sc.sectionCount = mutation.sectionCount
                }
            }
            network
        }.mapFailure { e ->
            when (e) {
                is InvalidMutationException -> e
                is IllegalArgumentException -> InvalidMutationException(e.message ?: "Invalid mutation", e)
                else -> InvalidMutationException("Unexpected error applying mutation: ${e.message}", e)
            }
        }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun Double.orNull(): Double? = if (isNaN() || isInfinite()) null else this

    /** Approximate conversion: I(A) × V(kV) × √3 / 1000 = S(MVA). */
    private fun ampsToPowerMva(
        currentA: Double,
        voltageKv: Double,
    ): Double = currentA * voltageKv * SQRT3 / 1000.0

    private fun IidmTwoWindingsTransformer.ratedSOrNull(): Double? = runCatching { ratedS }.getOrNull()?.orNull()

    companion object {
        private val SQRT3 = sqrt(3.0)
    }
}

/** Maps a Result's failure through a transformation. */
private fun <T> Result<T>.mapFailure(transform: (Throwable) -> Throwable): Result<T> =
    fold(onSuccess = { Result.success(it) }, onFailure = { Result.failure(transform(it)) })
