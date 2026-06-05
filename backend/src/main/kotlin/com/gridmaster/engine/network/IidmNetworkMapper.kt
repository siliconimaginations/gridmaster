package com.gridmaster.engine.network

import com.gridmaster.engine.model.FuelType
import com.gridmaster.engine.model.GridNetwork
import com.gridmaster.engine.model.NetworkMutation
import com.gridmaster.engine.model.Region
import com.powsybl.iidm.network.Network

/**
 * Fuel type and marginal cost metadata for a generator.
 * IIDM does not carry this information; it is injected from a sidecar source.
 */
data class GeneratorMetadata(
    val fuelType: FuelType = FuelType.OTHER,
    val marginalCostPerMwh: Double = 0.0,
)

/**
 * Provides fuel type and cost metadata for generators by ID.
 * The default implementation returns [GeneratorMetadata] defaults (OTHER / £0).
 * Override with a sidecar JSON provider for tutorial and test networks.
 */
interface GeneratorMetadataProvider {
    fun getMetadata(generatorId: String): GeneratorMetadata
}

/** Default — all generators get FuelType.OTHER and zero marginal cost. */
class DefaultGeneratorMetadataProvider : GeneratorMetadataProvider {
    override fun getMetadata(generatorId: String) = GeneratorMetadata()
}

/** Metadata provider backed by an in-memory map; useful for tests and XIIDM sidecar files. */
class MapGeneratorMetadataProvider(
    private val metadata: Map<String, GeneratorMetadata>,
) : GeneratorMetadataProvider {
    override fun getMetadata(generatorId: String) = metadata[generatorId] ?: GeneratorMetadata()
}

/**
 * Converts between PowSyBl's mutable IIDM [Network] and GridMaster's immutable [GridNetwork].
 * Also applies [NetworkMutation]s to the live IIDM network.
 */
interface IidmNetworkMapper {
    /**
     * Snapshot the current state of [network] as an immutable [GridNetwork].
     * Voltage and current values are null if power flow has not yet been solved.
     * @param regions Optional region annotations to embed in the snapshot.
     */
    fun toGridNetwork(
        network: Network,
        regions: List<Region> = emptyList(),
    ): GridNetwork

    /**
     * Apply [mutation] to [network] in place.
     * Returns [Result.success] with the mutated network on success,
     * or [Result.failure] with an [InvalidMutationException] if the mutation is invalid.
     * The network is NOT re-solved; the caller is responsible for running power flow afterwards.
     */
    fun applyMutation(
        network: Network,
        mutation: NetworkMutation,
    ): Result<Network>
}

/** Thrown when a [NetworkMutation] cannot be applied (e.g. element not found, out-of-range value). */
class InvalidMutationException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
