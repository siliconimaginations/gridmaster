package com.gridmaster.engine.contingency

import com.gridmaster.engine.model.GridNetwork
import com.powsybl.contingency.BranchContingency
import com.powsybl.contingency.GeneratorContingency
import com.powsybl.contingency.Contingency as PowSyBlContingency

/**
 * Builds contingency lists from domain model and converts between domain
 * [Contingency] and PowSyBl's [PowSyBlContingency].
 */
object ContingencyBuilder {
    /**
     * Build the N-1 contingency list from [network].
     * Produces one [Contingency] per line, two-winding transformer, and connected generator.
     * Three-winding transformers are included as single-element outages.
     */
    fun buildN1(network: GridNetwork): List<Contingency> {
        val contingencies = mutableListOf<Contingency>()

        network.lines.forEach { line ->
            contingencies +=
                Contingency(
                    id = "N1-LINE-${line.id}",
                    description = "Loss of line ${line.name.ifBlank { line.id }}",
                    elements = listOf(ContingencyElement.LineOutage(line.id)),
                )
        }

        network.twoWindingsTransformers.forEach { twt ->
            contingencies +=
                Contingency(
                    id = "N1-TWT-${twt.id}",
                    description = "Loss of transformer ${twt.name.ifBlank { twt.id }}",
                    elements = listOf(ContingencyElement.TwoWindingsTransformerOutage(twt.id)),
                )
        }

        network.threeWindingsTransformers.forEach { twt3 ->
            contingencies +=
                Contingency(
                    id = "N1-TWT3-${twt3.id}",
                    description = "Loss of 3W transformer ${twt3.name.ifBlank { twt3.id }}",
                    elements = listOf(ContingencyElement.ThreeWindingsTransformerOutage(twt3.id)),
                )
        }

        network.generators.filter { it.connected }.forEach { gen ->
            contingencies +=
                Contingency(
                    id = "N1-GEN-${gen.id}",
                    description = "Loss of generator ${gen.name.ifBlank { gen.id }}",
                    elements = listOf(ContingencyElement.GeneratorOutage(gen.id)),
                )
        }

        return contingencies
    }

    /**
     * Convert a domain [Contingency] to a PowSyBl [PowSyBlContingency].
     * PowSyBl's [BranchContingency] covers both lines and transformers.
     */
    fun toPowSyBl(contingency: Contingency): PowSyBlContingency {
        val elements =
            contingency.elements.map { element ->
                when (element) {
                    is ContingencyElement.LineOutage ->
                        BranchContingency(element.lineId)
                    is ContingencyElement.TwoWindingsTransformerOutage ->
                        BranchContingency(element.transformerId)
                    is ContingencyElement.ThreeWindingsTransformerOutage ->
                        BranchContingency(element.transformerId)
                    is ContingencyElement.GeneratorOutage ->
                        GeneratorContingency(element.generatorId)
                }
            }
        return PowSyBlContingency(contingency.id, elements)
    }

    /** Convert a list of domain contingencies to PowSyBl contingencies. */
    fun toPowSyBlList(contingencies: List<Contingency>): List<PowSyBlContingency> = contingencies.map { toPowSyBl(it) }
}
