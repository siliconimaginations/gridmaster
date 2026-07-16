package com.gridmaster.engine.model

import java.time.Instant

/**
 * A dormant site pre-built into an expansion-capable preset's IIDM network.
 * Not part of the domain [GridNetwork] model exposed to the client directly —
 * surfaced only through [ExpansionOption] once a rule matches it to an
 * observed violation.
 *
 * Per `docs/engineering/17-grid-expansion.md`'s "Expansion sites: pre-built,
 * dormant topology" section: rather than synthesizing new IIDM objects at
 * build time, each expansion-capable preset (initially `freeplay50`) is
 * seeded with real IIDM topology -- substations, voltage levels, buses,
 * lines, and (for [ExpansionSiteKind.GENERATOR] sites) a generator -- that
 * exists in the network from session start but whose terminals are
 * disconnected until a [BuildProject] completes. This reframes "build a new
 * generator" as "connect a pre-existing, currently disconnected generator,"
 * reusing [NetworkMutation.ConnectGenerator]/[NetworkMutation.ConnectLine]
 * rather than inventing new mutation semantics.
 */
data class ExpansionSite(
    val id: String,
    val kind: ExpansionSiteKind,
    /** Bus/substation this site would connect into once built. */
    val anchorBusId: String,
    /**
     * The existing network element this site is a remedy *for*, used by the
     * (not-yet-implemented, see design doc's Rule-driven proposal
     * derivation) rule engine to match a site to a violation. A
     * [ExpansionSiteKind.DOUBLE_LINE] site's target is the line it
     * duplicates; a [ExpansionSiteKind.GENERATOR]/[ExpansionSiteKind.SHUNT_COMPENSATOR]
     * site's target is the bus whose voltage it supports; a
     * [ExpansionSiteKind.SUBSTATION]/[ExpansionSiteKind.NEW_LINE] site's
     * target is the congested corridor/area it relieves.
     */
    val remediesElementId: String,
    /**
     * Set only when this site requires a separate connecting line to reach
     * the energized network (e.g. a [ExpansionSiteKind.GENERATOR] or
     * [ExpansionSiteKind.SUBSTATION] site sitting on its own new bus).
     * References another dormant [ExpansionSite] of kind
     * [ExpansionSiteKind.NEW_LINE] that must be built as part of the same
     * [BuildProject].
     */
    val connectingLineSiteId: String? = null,
    /** Approximate map coordinates for the preview render (frontend concern). */
    val locationHint: LocationHint,
)

/**
 * The kind of remedy an [ExpansionSite] provides.
 *
 * [DOUBLE_LINE] sites are different from the others: there is no new bus --
 * the "site" is an existing line corridor that has a second, currently-
 * disconnected [com.powsybl.iidm.network.Line] pre-modeled in parallel with
 * the same terminals (same corridor, doubling thermal capacity once
 * connected). This is a new, independent IIDM `Line` object with parameters
 * (impedance, rating) identical to the first -- not a shared/aliased
 * definition.
 *
 * [NEW_LINE] sites exist to connect a [GENERATOR] or [SUBSTATION] site's new
 * bus back into the energized network when that site isn't directly
 * adjacent to an existing bus -- see [ExpansionSite.connectingLineSiteId].
 *
 * [SHUNT_COMPENSATOR] sites address sustained voltage violations directly --
 * a dormant shunt/capacitor bank at a bus, connected the same
 * disconnected-until-built way as a generator.
 */
enum class ExpansionSiteKind { GENERATOR, SUBSTATION, NEW_LINE, DOUBLE_LINE, SHUNT_COMPENSATOR }

/** Approximate map coordinates for an [ExpansionSite]'s preview render (frontend concern). */
data class LocationHint(val x: Double, val y: Double)

/**
 * A committed, in-progress (or completed) build, persisted per session.
 * Once created, always runs to completion -- no cancel (`17-grid-expansion.md`
 * Resolved Design Point #6: construction latency exists specifically to
 * make the build-time cost real).
 *
 * [siteIds] is plural because a [ExpansionSiteKind.GENERATOR]/
 * [ExpansionSiteKind.SUBSTATION] build with a non-null
 * [ExpansionSite.connectingLineSiteId] commits both sites as one project --
 * they complete together (the generator is useless without its connecting
 * line).
 */
data class BuildProject(
    val id: String,
    val sessionId: String,
    val siteIds: List<String>,
    val costGbp: Double,
    val buildDurationGameMinutes: Long,
    val startedAtGameTimeMinutes: Long,
    val status: BuildStatus,
)

enum class BuildStatus { BUILDING, COMPLETE }

/**
 * Percent complete, purely derived from game-clock progress -- not
 * persisted. Clamped to [0, 100] so a project queried before its
 * [BuildProject.startedAtGameTimeMinutes] (shouldn't happen, but defensive)
 * or after its nominal completion time (the common case -- a project stays
 * queryable at 100% between the tick its duration elapses and the tick the
 * tick engine actually processes completion) reads sensibly either way.
 */
fun BuildProject.percentComplete(currentGameTimeMinutes: Long): Int =
    (
        ((currentGameTimeMinutes - startedAtGameTimeMinutes).toDouble() / buildDurationGameMinutes) * 100
    ).toInt().coerceIn(0, 100)

/**
 * A player-facing remedy offered by an `EventCard` in response to an
 * observed, sustained network violation. Not yet produced anywhere in the
 * codebase -- the rule engine that derives these from [ExpansionSite]s and a
 * live violation (`docs/engineering/17-grid-expansion.md`'s Rule-driven
 * proposal derivation) is a separate, later implementation slice.
 */
data class ExpansionOption(
    /** >1 only when a [ExpansionSite.connectingLineSiteId] is bundled in. */
    val siteIds: List<String>,
    /** e.g. "Build Gas Peaker South", "Add second circuit: L4". */
    val label: String,
    val costGbp: Double,
    val buildDurationGameMinutes: Long,
    /** Null for [ExpansionSiteKind.DOUBLE_LINE]/[ExpansionSiteKind.SHUNT_COMPENSATOR] (not an MW addition). */
    val capacityAddedMw: Double?,
    val previewImageUrl: String,
    val offeredAt: Instant = Instant.now(),
)
