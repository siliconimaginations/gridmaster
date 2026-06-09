import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { GridNetworkDto, SelectedElementInfo, ViolationDto } from '../../api/types'

vi.mock('../../state/useGameStore')
import { useGameStore } from '../../state/useGameStore'

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
    const state = { selectedElement: selected, network, violations, selectElement }
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
    mockStore({ elementType: 'BUS', elementId: SUB_ID })
    render(<InspectorPanel />)
    expect(screen.getByTestId('inspector-panel')).toHaveTextContent('0.980 pu')
    expect(screen.getByTestId('inspector-panel')).toHaveTextContent('132 kV')
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
