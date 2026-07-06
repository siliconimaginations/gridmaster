package com.gridmaster.engine.network

import com.gridmaster.engine.model.FuelType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Assigns fuel-type and marginal-cost metadata to the ieee14 preset generators.
 *
 * IIDM files carry no fuel or cost information, so without this bean every
 * generator defaults to [FuelType.OTHER] and zero marginal cost, and the
 * renderer's fuel glyphs never appear (issue 335).
 *
 * Marginal costs (issue #336) are derived from the real MATPOWER `case14`
 * generator cost curves (https://matpower.org/docs/ref/matpower5.0/case14.html,
 * `mpc.gencost`), which are quadratic: cost(P) = c2*P^2 + c1*P + c0, so
 * marginal cost MC(P) = 2*c2*P + c1. Evaluated at each generator's rated
 * `Pmax` from `mpc.gen`, in £/MWh order matching `mpc.gencost` row order
 * (bus 1, 2, 3, 6, 8):
 *   B1-G (Pmax 332.4, c2=0.0430292599, c1=20) -> 48.6
 *   B2-G (Pmax 140,   c2=0.25,          c1=20) -> 90.0
 *   B3-G/B6-G/B8-G (Pmax 100, c2=0.01,  c1=40) -> 42.0
 *
 * Note: case14's generic "generator 3/4/5" cost curves are the same
 * regardless of which fuel type this game's config later labelled them as
 * (WIND/HYDRO/SOLAR) — case14 doesn't model renewables specifically, so
 * these three all land at the same £42/MWh rather than the near-zero fuel
 * cost real renewables have. Flagged for Rick to revisit if the flat £42
 * for all three renewables looks wrong for merit-order/gameplay purposes.
 */
@Configuration
class NetworkMetadataConfig {
    /** Fuel-type + marginal-cost map for the ieee14 preset generator IDs. */
    @Bean
    fun generatorMetadataProvider(): GeneratorMetadataProvider =
        MapGeneratorMetadataProvider(
            mapOf(
                "B1-G" to GeneratorMetadata(fuelType = FuelType.GAS, marginalCostPerMwh = 48.6),
                "B2-G" to GeneratorMetadata(fuelType = FuelType.COAL, marginalCostPerMwh = 90.0),
                "B3-G" to GeneratorMetadata(fuelType = FuelType.WIND, marginalCostPerMwh = 42.0),
                "B6-G" to GeneratorMetadata(fuelType = FuelType.HYDRO, marginalCostPerMwh = 42.0),
                "B8-G" to GeneratorMetadata(fuelType = FuelType.SOLAR, marginalCostPerMwh = 42.0),
            ),
        )
}
