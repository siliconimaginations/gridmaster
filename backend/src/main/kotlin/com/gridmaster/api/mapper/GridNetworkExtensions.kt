package com.gridmaster.api.mapper

import com.gridmaster.engine.dispatch.DispatchableGenerator
import com.gridmaster.engine.model.GridNetwork

/**
 * Maps all generators in a [GridNetwork] snapshot to [DispatchableGenerator]s
 * for use with the dispatch and unit commitment services.
 */
fun GridNetwork.toDispatchableGenerators(): List<DispatchableGenerator> =
    generators.map { gen ->
        DispatchableGenerator(
            id = gen.id,
            name = gen.name,
            committed = gen.connected,
            minActivePowerMw = gen.minActivePowerMw,
            maxActivePowerMw = gen.maxActivePowerMw,
            currentActivePowerMw = gen.targetActivePowerMw,
            marginalCostPerMwh = gen.marginalCostPerMwh,
        )
    }
