import { test, expect } from '@playwright/test'

/**
 * CM — Command / Network Mutation
 *
 * CM-01: DecommitGenerator → committed=false in next store update
 * CM-02: CommitGenerator → committed=true after decommit baseline
 * CM-03: SetGeneratorOutput → activePowerMw reflects new setpoint in next store update
 *
 * Generator IDs in the ieee14 preset are assigned at runtime by PowSyBl;
 * they are discovered once in `beforeAll` via a throwaway REST session so
 * the tests are resilient to PowSyBl version changes.
 *
 * CM-02 controls its own precondition: it decommits a committed generator
 * within the test, then commits it back. Each test uses its own bootstrapped
 * app session, so all generators start committed.
 *
 * @see docs/engineering/15-e2e-ci.md §CM-01–03
 */

let committedGenId: string
let committedGenMaxMw: number

test.beforeAll(async ({ request }) => {
  // Issue a token for the discovery session
  const tokenRes = await request.post('/api/auth/token', { data: {} })
  const { token } = await tokenRes.json() as { token: string }

  // Create a throwaway session to discover the network structure
  const sessionRes = await request.post('/api/sessions', {
    data: { displayName: 'CM discovery', mode: 'FREE_PLAY', networkPreset: 'ieee14' },
    headers: { Authorization: `Bearer ${token}` },
  })
  const { id } = await sessionRes.json() as { id: string }

  const networkRes = await request.get(`/api/sessions/${id}/network`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  // GET /network returns GridNetwork domain model with `connected` (not `committed`)
  const network = await networkRes.json() as { generators: Array<{ id: string; connected: boolean; maxActivePowerMw: number }> }
  const gen = network.generators.find((g) => g.connected)
  committedGenId = gen?.id ?? ''
  committedGenMaxMw = gen?.maxActivePowerMw ?? 100

  // Clean up discovery session
  await request.delete(`/api/sessions/${id}`, { headers: { Authorization: `Bearer ${token}` } })

  expect(committedGenId).toBeTruthy()
})

test('CM-01 toggle generator off → committed=false in next store update', async ({ page }) => {
  await page.goto('/')
  await page.waitForSelector('[data-testid="bootstrap-overlay"]', {
    state: 'hidden',
    timeout: 15_000,
  })

  // Send DecommitGenerator command through the app's own store
  await page.evaluate((id) => {
    ;(window as { __e2e: { getStore: () => { sendCommand: (cmd: unknown) => void } } }).__e2e
      .getStore()
      .sendCommand({ commandType: 'DecommitGenerator', payload: { generatorId: id } })
  }, committedGenId)

  // Wait for the store to reflect committed=false from the server update
  await page.waitForFunction(
    (id) => {
      const { network } = (window as {
        __e2e: { getStore: () => { network: { generators: Array<{ id: string; committed: boolean }> } | null } }
      }).__e2e.getStore()
      return network?.generators.find((g) => g.id === id && !g.committed) !== undefined
    },
    committedGenId,
    { timeout: 20_000 },
  )
})

test('CM-02 toggle generator on → committed=true after decommit baseline', async ({ page }) => {
  await page.goto('/')
  await page.waitForSelector('[data-testid="bootstrap-overlay"]', {
    state: 'hidden',
    timeout: 15_000,
  })

  // Step 1: Decommit to establish a clean baseline
  await page.evaluate((id) => {
    ;(window as { __e2e: { getStore: () => { sendCommand: (cmd: unknown) => void } } }).__e2e
      .getStore()
      .sendCommand({ commandType: 'DecommitGenerator', payload: { generatorId: id } })
  }, committedGenId)

  await page.waitForFunction(
    (id) => {
      const { network } = (window as {
        __e2e: { getStore: () => { network: { generators: Array<{ id: string; committed: boolean }> } | null } }
      }).__e2e.getStore()
      return network?.generators.find((g) => g.id === id && !g.committed) !== undefined
    },
    committedGenId,
    { timeout: 20_000 },
  )

  // Step 2: Commit it back and assert
  await page.evaluate((id) => {
    ;(window as { __e2e: { getStore: () => { sendCommand: (cmd: unknown) => void } } }).__e2e
      .getStore()
      .sendCommand({ commandType: 'CommitGenerator', payload: { generatorId: id } })
  }, committedGenId)

  await page.waitForFunction(
    (id) => {
      const { network } = (window as {
        __e2e: { getStore: () => { network: { generators: Array<{ id: string; committed: boolean }> } | null } }
      }).__e2e.getStore()
      return network?.generators.find((g) => g.id === id && g.committed) !== undefined
    },
    committedGenId,
    { timeout: 20_000 },
  )
})

/**
 * CM-03: Send SetGeneratorOutput; verify that the next GameStateUpdate
 * reflects the new activePowerMw setpoint.
 *
 * Target is set to 50% of the generator's max rating, chosen to stay well
 * within min/max bounds across all ieee14 generators while producing a
 * clearly observable change from the initial dispatch value.
 */
test('CM-03 SetGeneratorOutput → activePowerMw reflects new setpoint', async ({ page }) => {
  await page.goto('/')
  await page.waitForSelector('[data-testid="bootstrap-overlay"]', {
    state: 'hidden',
    timeout: 15_000,
  })

  // Wait for at least one tick so network is populated
  await page.waitForFunction(
    () => (window as { __e2e: { getStore: () => { tickNumber: number } } }).__e2e.getStore().tickNumber > 0,
    { timeout: 15_000 },
  )

  // Target 50% of max — safe across all ieee14 generator ratings
  const targetMw = Math.round(committedGenMaxMw * 0.5)

  await page.evaluate(
    ([id, mw]) => {
      ;(window as { __e2e: { getStore: () => { sendCommand: (cmd: unknown) => void } } }).__e2e
        .getStore()
        .sendCommand({ commandType: 'SetGeneratorOutput', payload: { generatorId: id, targetMw: mw } })
    },
    [committedGenId, targetMw] as [string, number],
  )

  // Wait for the server to apply the mutation and broadcast the new setpoint
  await page.waitForFunction(
    ([id, mw]) => {
      const { network } = (window as {
        __e2e: {
          getStore: () => {
            network: { generators: Array<{ id: string; activePowerMw: number }> } | null
          }
        }
      }).__e2e.getStore()
      const gen = network?.generators.find((g) => g.id === id)
      // Allow ±1 MW tolerance for floating-point rounding in the power flow solve
      return gen !== undefined && Math.abs(gen.activePowerMw - (mw as number)) < 1.0
    },
    [committedGenId, targetMw] as [string, number],
    { timeout: 20_000 },
  )

  // Final assertion: value is stable in the store after the wait
  const actualMw = await page.evaluate(
    (id) => {
      const { network } = (window as {
        __e2e: { getStore: () => { network: { generators: Array<{ id: string; activePowerMw: number }> } | null } }
      }).__e2e.getStore()
      return network?.generators.find((g) => g.id === id)?.activePowerMw ?? -1
    },
    committedGenId,
  )

  expect(Math.abs(actualMw - targetMw)).toBeLessThan(1.0)
})
