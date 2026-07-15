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
     * Produces one [Contingency] per connected line, connected two-winding
     * transformer, three-winding transformer, and connected generator
     * (issue #407 — lines/two-winding transformers/generators are now all
     * filtered by connection state; previously only generators were, so a
     * manually-tripped/disconnected line or transformer produced a nonsensical
     * "loss of X" scenario for an element already out of service).
     * Three-winding transformers have no `connected` field in the domain model
     * yet, so they remain unfiltered — a smaller follow-up if it matters.
     */
    fun buildN1(network: GridNetwork): List<Contingency> {
        val contingencies = mutableListOf<Contingency>()

        network.lines.filter { it.connected }.forEach { line ->
            contingencies +=
                Contingency(
                    id = "N1-LINE-${line.id}",
                    description = "Loss of line ${line.name.ifBlank { line.id }}",
                    elements = listOf(ContingencyElement.LineOutage(line.id)),
                )
        }

        network.twoWindingsTransformers.filter { it.connected }.forEach { twt ->
            contingencies +=
                Contingency(
                    id = "N1-TWT-${twt.id}",
                    description = "Loss of transformer ${twt.name.ifBlank { twt.id }}",
                    elements = listOf(ContingencyElement.TwoWindingsTransformerOutage(twt.id)),
                )
        }

        // ThreeWindingsTransformer has no `connected` field in the domain model today
        // (issue #407 follow-up) — left unfiltered for now, matching prior behavior.
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
