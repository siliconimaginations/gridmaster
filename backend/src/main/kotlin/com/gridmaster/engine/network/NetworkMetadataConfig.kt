package com.gridmaster.engine.network

import com.gridmaster.engine.model.FuelType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Assigns fuel-type metadata to the ieee14 preset generators.
 *
 * IIDM files carry no fuel information, so without this bean every generator
 * defaults to [FuelType.OTHER] and the renderer's fuel glyphs never appear
 * (issue 335). Marginal costs are deliberately left at the default zero —
 * per-fuel pricing changes dispatch behaviour and is a separate
 * game-mechanics decision.
 */
@Configuration
class NetworkMetadataConfig {
    /** Fuel-type map for the ieee14 preset generator IDs. */
    @Bean
    fun generatorMetadataProvider(): GeneratorMetadataProvider =
        MapGeneratorMetadataProvider(
            mapOf(
                "B1-G" to GeneratorMetadata(fuelType = FuelType.GAS),
                "B2-G" to GeneratorMetadata(fuelType = FuelType.COAL),
                "B3-G" to GeneratorMetadata(fuelType = FuelType.WIND),
                "B6-G" to GeneratorMetadata(fuelType = FuelType.HYDRO),
                "B8-G" to GeneratorMetadata(fuelType = FuelType.SOLAR),
            ),
        )
}
