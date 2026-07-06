import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { GridNetworkDto, SelectedElementInfo, ViolationDto } from '../../api/types'

vi.mock('../../state/useGameStore')
import { useGameStore } from '../../state/useGameStore'

// Default resolves null ("no analysis yet") so unrelated LINE tests stay quiet.
vi.mock('../../api/restClient', () => ({
  getContingencyForBranch: vi.fn(() => Promise.resolve(null)),
}))
import { getContingencyForBranch } from '../../api/restClient'

import { InspectorPanel } from '../InspectorPanel'

// ── Fixtures ──────────────────────────────────────────────────────────────────

const GEN_ID = 'gen-1'
const BRANCH_ID = 'br-1'
const SUB_ID = 'sub-1'
const LOAD_ID = 'load-1'

const NETWORK: GridNetworkDto = {
  buses: [
    { id: 'b1', name: 'Bus 1', voltageKv: 132, voltagePu: 0.98, angleRad: 0, substationId: SUB_ID },
    { id: 'b2', name: 'Bus 2', voltageKv: 132, voltagePu: 1.02, angleRad: 0, substationId: 'sub-2' },
  ],
  generators: [
    { id: GEN_ID, busId: 'b1', name: 'CCGT-1', activePowerMw: 400, maxActivePowerMw: 600, committed: true, fuelType: 'Gas' },
  ],
  branches: [
    { id: BRANCH_ID, fromBusId: 'b1', toBusId: 'b2', activePowerMw: 150, reactivePowerMvar: 10, loadingPercent: 75, connected: true },
  ],
  loads: [
    { id: LOAD_ID, busId: 'b2', name: 'City North', activePowerMw: 200, reactivePowerMvar: 30 },
  ],
}

function mockStore(
  selected: SelectedElementInfo | null,
  network: GridNetworkDto | null = NETWORK,
  violations: ViolationDto[] = [],
) {
  const selectElement = vi.fn()
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  vi.mocked(useGameStore).mockImplementation((selector?: (s: any) => any) => {
    const state = { selectedElement: selected, network, violations, selectElement, sessionId: 'sess-1' }
    return selector ? selector(state) : state
  })
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  ;(useGameStore as any).getState = () => ({ selectElement })
  return { selectElement }
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('InspectorPanel', () => {
  beforeEach(() => {
    mockStore(null)
  })

  it('renders nothing when no element is selected', () => {
    const { container } = render(<InspectorPanel />)
    expect(container).toBeEmptyDOMElement()
  })

  it('renders nothing when network is null even if element is selected', () => {
    mockStore({ elementType: 'GENERATOR', elementId: GEN_ID }, null)
    const { container } = render(<InspectorPanel />)
    expect(container).toBeEmptyDOMElement()
  })

  it('shows the panel when an element is selected', () => {
    mockStore({ elementType: 'GENERATOR', elementId: GEN_ID })
    render(<InspectorPanel />)
    expect(screen.getByTestId('inspector-panel')).toBeInTheDocument()
  })

  it('shows generator metrics', () => {
    mockStore({ elementType: 'GENERATOR', elementId: GEN_ID })
    render(<InspectorPanel />)
    expect(screen.getByTestId('inspector-panel')).toHaveTextContent('400.0 MW')
    expect(screen.getByTestId('inspector-panel')).toHaveTextContent('Gas')
    expect(screen.getByTestId('inspector-panel')).toHaveTextContent('Committed')
  })

  it('shows line metrics', () => {
    mockStore({ elementType: 'LINE', elementId: BRANCH_ID })
    render(<InspectorPanel />)
    expect(screen.getByTestId('inspector-panel')).toHaveTextContent('75.0%')
    expect(screen.getByTestId('inspector-panel')).toHaveTextContent('150.0 MW')
    expect(screen.getByTestId('inspector-panel')).toHaveTextContent('Connected')
  })

  it('shows bus metrics', () => {
    // The real click path (NodeLayer.onBusClick → PixiGridRenderer.onSelect)
    // passes the bus's own id, matching BusDto.id — not substationId (#364).
    mockStore({ elementType: 'BUS', elementId: 'b1' })
    render(<InspectorPanel />)
    expect(screen.getByTestId('inspector-panel')).toHaveTextContent('0.980 pu')
    expect(screen.getByTestId('inspector-panel')).toHaveTextContent('132 kV')
    // b1 has one connected branch (br-1, to b2) and shares SUB_ID with no
    // other bus in this fixture, so both grouping counts should read 1.
    // Negative lookahead (Gemini review, PR #368) so "Buses1" can't
    // false-match a future fixture where the count is e.g. 10.
    expect(screen.getByTestId('inspector-panel')).toHaveTextContent(/Buses1(?!\d)/)
    expect(screen.getByTestId('inspector-panel')).toHaveTextContent(/Lines1(?!\d)/)
  })

  it('renders nothing for a BUS element id that matches no bus (regression for #364)', () => {
    // Before the fix, BusCard matched on `substationId === id` instead of the
    // bus's own id — passing an id that isn't a real substationId (like a
    // bus's own id happens to be, in fixtures without shared substations)
    // silently rendered an empty body. Confirm an unknown id still renders
    // nothing (correct null-safety), while a real bus id (above) now works.
    mockStore({ elementType: 'BUS', elementId: 'no-such-bus' })
    render(<InspectorPanel />)
    expect(screen.getByTestId('inspector-panel')).not.toHaveTextContent('pu')
  })

  it('BusCard groups by substationId when two buses share one', () => {
    const network: GridNetworkDto = {
      ...NETWORK,
      buses: [
        ...NETWORK.buses,
        { id: 'b3', name: 'Bus 3', voltageKv: 132, voltagePu: 1.0, angleRad: 0, substationId: SUB_ID },
      ],
    }
    mockStore({ elementType: 'BUS', elementId: 'b1' }, network)
    render(<InspectorPanel />)
    // b1 and b3 now share SUB_ID — Buses count reflects both.
    expect(screen.getByTestId('inspector-panel')).toHaveTextContent(/Buses2(?!\d)/)
  })

  // ── #370: bus role must distinguish generator/load/substation ──────────────

  it('labels a generator-hosting bus as Generator and shows its output (#370)', () => {
    // b1 hosts GEN_ID (400/600 MW) in the shared NETWORK fixture — the header
    // previously always read "Substation" regardless of what the bus hosts.
    mockStore({ elementType: 'BUS', elementId: 'b1' })
    render(<InspectorPanel />)
    expect(screen.getByTestId('inspector-panel')).toHaveTextContent('⚡ Generator')
    expect(screen.getByTestId('inspector-panel')).toHaveTextContent('400.0 MW')
    expect(screen.getByTestId('inspector-panel')).toHaveTextContent('600.0 MW')
  })

  it('labels a load-hosting bus as City and shows its demand (#370)', () => {
    // b2 hosts LOAD_ID (200 MW) and no generators in the shared NETWORK fixture.
    mockStore({ elementType: 'BUS', elementId: 'b2' })
    render(<InspectorPanel />)
    expect(screen.getByTestId('inspector-panel')).toHaveTextContent('🏘 City')
    expect(screen.getByTestId('inspector-panel')).toHaveTextContent('200.0 MW')
  })

  it('labels a bus with neither generators nor loads as Substation (#370)', () => {
    const network: GridNetworkDto = {
      ...NETWORK,
      buses: [
        ...NETWORK.buses,
        { id: 'b4', name: 'Bus 4', voltageKv: 400, voltagePu: 1.0, angleRad: 0, substationId: 'sub-4' },
      ],
    }
    mockStore({ elementType: 'BUS', elementId: 'b4' }, network)
    render(<InspectorPanel />)
    expect(screen.getByTestId('inspector-panel')).toHaveTextContent('🔌 Substation')
  })

  it('shows load metrics', () => {
    mockStore({ elementType: 'LOAD', elementId: LOAD_ID })
    render(<InspectorPanel />)
    expect(screen.getByTestId('inspector-panel')).toHaveTextContent('200.0 MW')
    expect(screen.getByTestId('inspector-panel')).toHaveTextContent('Supplied ✓')
  })

  it('close button calls selectElement(null)', () => {
    const { selectElement } = mockStore({ elementType: 'GENERATOR', elementId: GEN_ID })
    render(<InspectorPanel />)
    fireEvent.click(screen.getByLabelText('Close inspector'))
    expect(selectElement).toHaveBeenCalledWith(null)
  })

  it('clicking the backdrop calls selectElement(null)', () => {
    const { selectElement } = mockStore({ elementType: 'GENERATOR', elementId: GEN_ID })
    render(<InspectorPanel />)
    fireEvent.click(screen.getByTestId('inspector-backdrop'))
    expect(selectElement).toHaveBeenCalledWith(null)
  })
})

// ── N-1 security section ─────────────────────────────────────────────────────

describe('N-1 security section', () => {
  const VIOLATION = {
    equipmentId: 'L5',
    equipmentType: 'LINE',
    violationType: 'THERMAL' as const,
    value: 1250,
    limit: 1000,
    loadingPercent: 125,
    severity: 'CRITICAL',
  }

  it('shows No analysis chip when no contingency result exists', async () => {
    vi.mocked(getContingencyForBranch).mockResolvedValue(null)
    mockStore({ elementType: 'LINE', elementId: BRANCH_ID })
    render(<InspectorPanel />)

    expect(await screen.findByText(/No analysis/)).toBeInTheDocument()
    expect(getContingencyForBranch).toHaveBeenCalledWith('sess-1', BRANCH_ID)
  })

  it('shows Secure ✓ chip for a SECURE result', async () => {
    vi.mocked(getContingencyForBranch).mockResolvedValue({
      contingencyId: 'N1-LINE-br-1',
      status: 'SECURE',
      violations: [],
      analysisCompletedAt: '2026-07-04T00:00:00Z',
    })
    mockStore({ elementType: 'LINE', elementId: BRANCH_ID })
    render(<InspectorPanel />)

    expect(await screen.findByText(/Secure ✓/)).toBeInTheDocument()
  })

  it('shows violation count chip for a VIOLATION result', async () => {
    vi.mocked(getContingencyForBranch).mockResolvedValue({
      contingencyId: 'N1-LINE-br-1',
      status: 'VIOLATION',
      violations: [VIOLATION, { ...VIOLATION, equipmentId: 'L6' }],
      analysisCompletedAt: '2026-07-04T00:00:00Z',
    })
    mockStore({ elementType: 'LINE', elementId: BRANCH_ID })
    render(<InspectorPanel />)

    expect(await screen.findByText(/2 violations/)).toBeInTheDocument()
  })

  it('expands on toggle click and lists violation rows', async () => {
    vi.mocked(getContingencyForBranch).mockResolvedValue({
      contingencyId: 'N1-LINE-br-1',
      status: 'VIOLATION',
      violations: [VIOLATION],
      analysisCompletedAt: '2026-07-04T00:00:00Z',
    })
    mockStore({ elementType: 'LINE', elementId: BRANCH_ID })
    render(<InspectorPanel />)

    fireEvent.click(await screen.findByTestId('n1-toggle'))
    expect(await screen.findByText('L5')).toBeInTheDocument()
    expect(await screen.findByText(/THERMAL 125\.0%/)).toBeInTheDocument()
  })
})
