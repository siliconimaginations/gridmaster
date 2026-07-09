import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { DispatchPanel } from '../DispatchPanel'
import type { GridNetworkDto, GeneratorDto } from '../../api/types'

// ── Store mock ────────────────────────────────────────────────────────────────

const mockSendCommandOptimistic = vi.fn()
const mockSendCommand = vi.fn()

function makeGenerator(overrides: Partial<GeneratorDto> = {}): GeneratorDto {
  return {
    id: 'gen-1',
    busId: 'bus-1',
    name: 'Test Gas',
    fuelType: 'GAS',
    activePowerMw: 200,
    setpointMw: 200,
    maxActivePowerMw: 500,
    committed: true,
    marginalCostPerMwh: 48.6,
    dispatchable: true,
    ...overrides,
  }
}

function makeNetwork(
  generators: GeneratorDto[],
  systemMarginalCostPerMwh: number | null = null,
): GridNetworkDto {
  return {
    generators,
    loads: [{ id: 'load-1', busId: 'bus-1', name: 'City Load', activePowerMw: 300, reactivePowerMvar: 0 }],
    buses: [],
    branches: [],
    systemMarginalCostPerMwh,
  } as unknown as GridNetworkDto
}

// Vitest module mock — returned value is configured per-test via `mockReturnValue`
vi.mock('../../state/useGameStore', () => {
  const mockStore: Record<string, unknown> = {}

  function useGameStore(selector: (s: typeof mockStore) => unknown) {
    return selector(mockStore)
  }

  useGameStore.__mockState = mockStore
  useGameStore.__reset = (state: Record<string, unknown>) => {
    Object.assign(mockStore, state)
  }

  return { useGameStore }
})

// Pull in the mocked module so tests can reconfigure state
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const { useGameStore } = await import('../../state/useGameStore') as any

function setStoreState(network: GridNetworkDto | null) {
  useGameStore.__reset({
    network,
    sendCommandOptimistic: mockSendCommandOptimistic,
    sendCommand: mockSendCommand,
    setUcSchedule: vi.fn(),
  })
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function renderPanel(open = true) {
  const onClose = vi.fn()
  const result = render(<DispatchPanel open={open} onClose={onClose} />)
  return { ...result, onClose }
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('DispatchPanel', () => {
  beforeEach(() => {
    mockSendCommandOptimistic.mockReset()
    mockSendCommand.mockReset()
  })

  it('renders nothing when closed', () => {
    setStoreState(makeNetwork([makeGenerator()]))
    renderPanel(false)
    expect(screen.queryByTestId('dispatch-panel')).toBeNull()
  })

  it('renders nothing when network is null', () => {
    setStoreState(null)
    renderPanel(true)
    expect(screen.queryByTestId('dispatch-panel')).toBeNull()
  })

  it('renders panel when open with network data', () => {
    setStoreState(makeNetwork([makeGenerator()]))
    renderPanel(true)
    expect(screen.getByTestId('dispatch-panel')).toBeInTheDocument()
  })

  it('calls onClose when × button clicked', () => {
    setStoreState(makeNetwork([makeGenerator()]))
    const { onClose } = renderPanel(true)
    fireEvent.click(screen.getByRole('button', { name: /close dispatch panel/i }))
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('shows Real-time tab by default', () => {
    setStoreState(makeNetwork([makeGenerator()]))
    renderPanel(true)
    expect(screen.getByTestId('tab-realtime')).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByTestId('tab-dayahead')).toHaveAttribute('aria-selected', 'false')
  })

  it('switches to Day-ahead tab on click', () => {
    setStoreState(makeNetwork([makeGenerator()]))
    renderPanel(true)
    fireEvent.click(screen.getByTestId('tab-dayahead'))
    expect(screen.getByTestId('tab-dayahead')).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByTestId('uc-grid')).toBeInTheDocument()
  })

  describe('Real-time tab — merit order table', () => {
    it('renders generator row', () => {
      setStoreState(makeNetwork([makeGenerator({ id: 'gen-1', name: 'Didcot Gas' })]))
      renderPanel(true)
      expect(screen.getByTestId('dispatch-row-gen-1')).toBeInTheDocument()
      // "Didcot Gas" also appears in the cost-stack chart's bar label (#336),
      // so scope this assertion to the merit-order row rather than the whole
      // document.
      expect(screen.getByTestId('dispatch-row-gen-1')).toHaveTextContent('Didcot Gas')
    })

    it('sorts generators by marginal cost (renewable first)', () => {
      const gens = [
        makeGenerator({
          id: 'coal-1', name: 'Coal Plant', fuelType: 'COAL',
          activePowerMw: 100, maxActivePowerMw: 400, marginalCostPerMwh: 90.0,
        }),
        makeGenerator({
          id: 'wind-1', name: 'Wind Farm', fuelType: 'WIND',
          activePowerMw: 80, maxActivePowerMw: 200, marginalCostPerMwh: 42.0,
        }),
      ]
      setStoreState(makeNetwork(gens))
      renderPanel(true)

      // DOCUMENT_POSITION_FOLLOWING (4) = coalRow appears after windRow in the DOM
      const windRow = screen.getByTestId('dispatch-row-wind-1')
      const coalRow = screen.getByTestId('dispatch-row-coal-1')
      expect(windRow.compareDocumentPosition(coalRow) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    })

    it('expands slider row on row click', () => {
      setStoreState(makeNetwork([makeGenerator({ id: 'gen-1' })]))
      renderPanel(true)
      expect(screen.queryByTestId('slider-row-gen-1')).toBeNull()
      fireEvent.click(screen.getByTestId('dispatch-row-gen-1'))
      expect(screen.getByTestId('slider-row-gen-1')).toBeInTheDocument()
    })

    it('collapses slider row on second click', () => {
      setStoreState(makeNetwork([makeGenerator({ id: 'gen-1' })]))
      renderPanel(true)
      fireEvent.click(screen.getByTestId('dispatch-row-gen-1'))
      fireEvent.click(screen.getByTestId('dispatch-row-gen-1'))
      expect(screen.queryByTestId('slider-row-gen-1')).toBeNull()
    })

    it('decommitted generators have reduced opacity class', () => {
      setStoreState(makeNetwork([makeGenerator({ id: 'gen-off', committed: false })]))
      renderPanel(true)
      const row = screen.getByTestId('dispatch-row-gen-off')
      expect(row.className).toMatch(/genRowDecommitted/)
    })

    it('commit button shows ON for committed generator', () => {
      setStoreState(makeNetwork([makeGenerator({ id: 'gen-1', committed: true })]))
      renderPanel(true)
      expect(screen.getByTestId('commit-btn-gen-1')).toHaveTextContent('ON')
    })

    it('commit button shows OFF for decommitted generator', () => {
      setStoreState(makeNetwork([makeGenerator({ id: 'gen-1', committed: false })]))
      renderPanel(true)
      expect(screen.getByTestId('commit-btn-gen-1')).toHaveTextContent('OFF')
    })

    it('commit button click calls sendCommandOptimistic with DecommitGenerator', () => {
      setStoreState(makeNetwork([makeGenerator({ id: 'gen-1', committed: true })]))
      renderPanel(true)
      fireEvent.click(screen.getByTestId('commit-btn-gen-1'))
      expect(mockSendCommandOptimistic).toHaveBeenCalledWith(
        expect.objectContaining({ commandType: 'DecommitGenerator', payload: { generatorId: 'gen-1' } }),
        expect.any(Function),
      )
    })

    it('commit button click calls sendCommandOptimistic with CommitGenerator when OFF', () => {
      setStoreState(makeNetwork([makeGenerator({ id: 'gen-1', committed: false })]))
      renderPanel(true)
      fireEvent.click(screen.getByTestId('commit-btn-gen-1'))
      expect(mockSendCommandOptimistic).toHaveBeenCalledWith(
        expect.objectContaining({ commandType: 'CommitGenerator', payload: { generatorId: 'gen-1' } }),
        expect.any(Function),
      )
    })

    it('slider mouseUp sends SetGeneratorOutput with a targetMw payload (#365)', () => {
      // The backend's PlayerCommand.SetGeneratorOutput expects `targetMw`, not
      // `activePowerMw` — a prior mismatch here made every dispatch command
      // fail with "'targetMw' must be a number".
      setStoreState(makeNetwork([makeGenerator({ id: 'gen-1', committed: true, maxActivePowerMw: 500 })]))
      renderPanel(true)
      fireEvent.click(screen.getByTestId('dispatch-row-gen-1'))
      const slider = screen.getByLabelText(/Output for/)
      fireEvent.mouseUp(slider, { target: { value: '250' } })
      expect(mockSendCommandOptimistic).toHaveBeenCalledWith(
        {
          commandType: 'SetGeneratorOutput',
          payload: { generatorId: 'gen-1', targetMw: 250 },
        },
        expect.any(Function),
      )
    })

    it('does not expand slider for decommitted generator', () => {
      setStoreState(makeNetwork([makeGenerator({ id: 'gen-1', committed: false })]))
      renderPanel(true)
      fireEvent.click(screen.getByTestId('dispatch-row-gen-1'))
      // `expanded && gen.committed` guard means slider is never shown
      expect(screen.queryByTestId('slider-row-gen-1')).toBeNull()
    })

    // ── #382: dispatchable vs non-dispatchable (WIND/SOLAR) generators ────────

    it('slider uses setpointMw (not activePowerMw) as its editable value for dispatchable generators', () => {
      setStoreState(makeNetwork([
        makeGenerator({ id: 'gen-1', committed: true, dispatchable: true, activePowerMw: 180, setpointMw: 250 }),
      ]))
      renderPanel(true)
      fireEvent.click(screen.getByTestId('dispatch-row-gen-1'))
      const slider = screen.getByLabelText(/Output for/) as HTMLInputElement
      expect(slider.value).toBe('250')
      // Actual (solved) output is shown alongside, distinct from the setpoint.
      expect(screen.getByTestId('actual-output-gen-1')).toHaveTextContent('180 MW')
    })

    it('disables editing and shows read-only actual output for a non-dispatchable (WIND/SOLAR) generator', () => {
      setStoreState(makeNetwork([
        makeGenerator({
          id: 'wind-1', name: 'Wind Farm', fuelType: 'WIND',
          committed: true, dispatchable: false, activePowerMw: 65, setpointMw: 65,
        }),
      ]))
      renderPanel(true)
      fireEvent.click(screen.getByTestId('dispatch-row-wind-1'))

      // No editable slider for non-dispatchable generators.
      expect(screen.queryByLabelText(/Output for/)).toBeNull()
      // Read-only actual output is shown instead.
      const readonly = screen.getByTestId('readonly-output-wind-1')
      expect(readonly).toHaveTextContent('65 MW')
      expect(readonly).toHaveTextContent('not dispatchable')
    })
  })

  describe('Day-ahead tab — UC schedule', () => {
    it('renders 24 hour cells per committed generator', () => {
      setStoreState(makeNetwork([makeGenerator({ id: 'gen-1', committed: true })]))
      renderPanel(true)
      fireEvent.click(screen.getByTestId('tab-dayahead'))

      expect(screen.getByTestId('uc-row-gen-1')).toBeInTheDocument()
      // 24 cells per generator
      for (let h = 0; h < 24; h++) {
        expect(screen.getByTestId(`uc-cell-gen-1-${h}`)).toBeInTheDocument()
      }
    })

    it('does not render row for decommitted generators', () => {
      setStoreState(makeNetwork([
        makeGenerator({ id: 'gen-on', committed: true }),
        makeGenerator({ id: 'gen-off', committed: false }),
      ]))
      renderPanel(true)
      fireEvent.click(screen.getByTestId('tab-dayahead'))

      expect(screen.getByTestId('uc-row-gen-on')).toBeInTheDocument()
      expect(screen.queryByTestId('uc-row-gen-off')).toBeNull()
    })

    it('toggles cell ON/OFF on click', () => {
      setStoreState(makeNetwork([makeGenerator({ id: 'gen-1', committed: true })]))
      renderPanel(true)
      fireEvent.click(screen.getByTestId('tab-dayahead'))

      const cell = screen.getByTestId('uc-cell-gen-1-0')
      // Initially ON (committed generator → all hours pre-filled)
      expect(cell.className).toMatch(/ucCellOn/)
      fireEvent.click(cell)
      expect(cell.className).not.toMatch(/ucCellOn/)
      fireEvent.click(cell)
      expect(cell.className).toMatch(/ucCellOn/)
    })

    it('Auto-fill button sets all cells ON', () => {
      setStoreState(makeNetwork([makeGenerator({ id: 'gen-1', committed: true })]))
      renderPanel(true)
      fireEvent.click(screen.getByTestId('tab-dayahead'))

      // Turn off hour 5
      fireEvent.click(screen.getByTestId('uc-cell-gen-1-5'))
      expect(screen.getByTestId('uc-cell-gen-1-5').className).not.toMatch(/ucCellOn/)

      // Auto-fill restores it
      fireEvent.click(screen.getByTestId('btn-autofill'))
      expect(screen.getByTestId('uc-cell-gen-1-5').className).toMatch(/ucCellOn/)
    })

    it('Confirm schedule button calls sendCommand with RunUnitCommitment', () => {
      setStoreState(makeNetwork([makeGenerator({ id: 'gen-1', committed: true })]))
      renderPanel(true)
      fireEvent.click(screen.getByTestId('tab-dayahead'))
      fireEvent.click(screen.getByTestId('btn-uc-confirm'))

      expect(mockSendCommand).toHaveBeenCalledWith(
        expect.objectContaining({ commandType: 'RunUnitCommitment' }),
      )
    })
  })

  describe('Cost stack chart (#336)', () => {
    it('renders a bar per generator, sorted by marginal cost ascending', () => {
      const gens = [
        makeGenerator({ id: 'coal-1', name: 'Coal Plant', fuelType: 'COAL', marginalCostPerMwh: 90.0 }),
        makeGenerator({ id: 'wind-1', name: 'Wind Farm', fuelType: 'WIND', marginalCostPerMwh: 42.0 }),
        makeGenerator({ id: 'gas-1', name: 'Gas Plant', fuelType: 'GAS', marginalCostPerMwh: 48.6 }),
      ]
      setStoreState(makeNetwork(gens))
      renderPanel(true)

      const chart = screen.getByTestId('cost-stack-chart')
      expect(chart).toBeInTheDocument()
      // DOCUMENT_POSITION_FOLLOWING (4) — cheapest (wind) must render before
      // the more expensive gas and coal bars.
      const windBar = screen.getByTestId('cost-stack-bar-wind-1')
      const gasBar = screen.getByTestId('cost-stack-bar-gas-1')
      const coalBar = screen.getByTestId('cost-stack-bar-coal-1')
      expect(windBar.compareDocumentPosition(gasBar) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      expect(gasBar.compareDocumentPosition(coalBar) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    })

    it('shows the SMC marker and value when the server supplies one', () => {
      setStoreState(makeNetwork([makeGenerator({ id: 'gen-1', marginalCostPerMwh: 48.6 })], 45.2))
      renderPanel(true)

      expect(screen.getByTestId('cost-stack-toggle')).toHaveTextContent('£45.2/MWh')
      expect(screen.getByTestId('cost-stack-smc-line')).toBeInTheDocument()
    })

    it('omits the SMC marker when the network has no dispatch result yet', () => {
      setStoreState(makeNetwork([makeGenerator({ id: 'gen-1' })], null))
      renderPanel(true)

      expect(screen.queryByTestId('cost-stack-smc-line')).toBeNull()
    })

    it('collapses on toggle click', () => {
      setStoreState(makeNetwork([makeGenerator({ id: 'gen-1' })]))
      renderPanel(true)

      expect(screen.getByTestId('cost-stack-chart')).toBeInTheDocument()
      fireEvent.click(screen.getByTestId('cost-stack-toggle'))
      expect(screen.queryByTestId('cost-stack-chart')).toBeNull()
    })
  })
})

