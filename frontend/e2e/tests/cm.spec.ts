import { test, expect } from '@playwright/test'

/**
 * CM — Command / Network Mutation
 *
 * CM-01: DecommitGenerator → committed=false in next store update
 * CM-02: CommitGenerator → committed=true after decommit baseline
 *
 * Generator IDs in the ieee14 preset are assigned at runtime by PowSyBl;
 * they are discovered once in `beforeAll` via a throwaway REST session so
 * the tests are resilient to PowSyBl version changes.
 *
 * CM-02 controls its own precondition: it decommits a committed generator
 * within the test, then commits it back. Each test uses its own bootstrapped
 * app session, so all generators start committed.
 *
 * @see docs/engineering/15-e2e-ci.md §CM-01–02
 */

let committedGenId: string

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
  const network = await networkRes.json() as { generators: Array<{ id: string; committed: boolean }> }
  committedGenId = network.generators.find((g) => g.committed)?.id ?? ''

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
    { timeout: 10_000 },
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
    { timeout: 10_000 },
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
    { timeout: 10_000 },
  )
})
