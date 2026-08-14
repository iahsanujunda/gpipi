import { expect, test } from '@playwright/test'

const workoutId = '61000000-0000-0000-0000-000000000001'
const sessionId = '64000000-0000-0000-0000-000000000201'
const writeId = '80000000-0000-0000-0000-000000000001'
const rdlId = '65000000-0000-0000-0000-000000000001'
const holdId = '65000000-0000-0000-0000-000000000002'

function detail() {
  return {
    program: { id: '60000000-0000-0000-0000-000000000001', name: 'M1', note: null, startsOn: null, active: true },
    currentWeekNumber: 3,
    weekId: '62000000-0000-0000-0000-000000000201',
    weekNumber: 2,
    skipped: false,
    workoutId,
    workoutName: 'Full Body 1',
    workoutNote: null,
    session: {
      id: sessionId,
      performedOn: '2026-08-12',
      status: 'COMPLETED',
      note: null,
      updatedAt: '2026-08-12T00:47:00Z',
      completedAt: '2026-08-12T00:46:00Z',
    },
    groups: [{
      position: 1,
      label: 'A',
      kind: 'STRAIGHT_SET',
      exercises: [{
        prescriptionId: '63000000-0000-0000-0000-000000000001',
        performedExerciseId: rdlId,
        position: 1,
        exerciseName: 'Barbell RDL',
        demoUrl: null,
        executionType: 'REPS',
        targetSets: '3',
        targetRest: '60 sec',
        targetReps: '8',
        targetLoad: '7.5 kg',
        targetRir: '2',
        targetTempo: null,
        targetNote: null,
        executionNote: null,
        sets: [],
      }, {
        prescriptionId: '63000000-0000-0000-0000-000000000002',
        performedExerciseId: holdId,
        position: 2,
        exerciseName: 'Hollow hold',
        demoUrl: null,
        executionType: 'DURATION',
        targetSets: '2',
        targetRest: '45 sec',
        targetReps: '45 sec',
        targetLoad: null,
        targetRir: null,
        targetTempo: null,
        targetNote: null,
        executionNote: null,
        sets: [],
      }],
    }],
  }
}

function write(state, overrides = {}) {
  const choosingTab = state === 'NEEDS_TAB'
  const choosingWeek = state === 'NEEDS_WEEK'
  return {
    id: writeId,
    sessionId,
    sourceWeekNumber: 2,
    sourceWorkoutName: 'Full Body 1',
    spreadsheetTitle: 'JUNDA – M1',
    availableWeekNumbers: choosingTab ? [] : [1, 2, 3, 4, 5, 6],
    targetWeekNumber: choosingTab || choosingWeek ? null : 5,
    targetTabTitle: choosingTab ? null : 'Full Body WO 1',
    selectedTabKey: choosingTab ? null : 'tab-101',
    status: state,
    detail: null,
    availableTabs: [{ key: 'tab-101', title: 'Full Body WO 1' }, { key: 'tab-202', title: 'Macro Check In' }],
    candidateTabs: choosingTab || choosingWeek ? [] : [{
      key: 'tab-101',
      title: 'Full Body WO 1',
      rows: [
        { address: 'B14', text: 'Romanian Deadlift' },
        { address: 'B15', text: 'Hollow body hold' },
        { address: 'B18', text: 'Full plank' },
      ],
    }],
    matches: choosingTab || choosingWeek ? [] : [{
      sourceMovementKey: rdlId,
      sourceName: 'Barbell RDL',
      sourcePosition: 1,
      sheetMovementAddress: 'B14',
      sheetMovementText: 'Romanian Deadlift',
      matchSource: state === 'RESOLVED' ? 'IMPORT' : 'MODEL',
      confirmed: state !== 'REVIEW' || state === 'RESOLVED',
    }, {
      sourceMovementKey: holdId,
      sourceName: 'Hollow hold',
      sourcePosition: 2,
      sheetMovementAddress: state === 'REVIEW' ? null : 'B15',
      sheetMovementText: state === 'REVIEW' ? null : 'Hollow body hold',
      matchSource: state === 'RESOLVED' ? 'IMPORT' : state === 'REVIEW' ? null : 'MANUAL',
      confirmed: state !== 'REVIEW' || state === 'RESOLVED',
    }],
    preview: ['PREPARED', 'SUCCEEDED'].includes(state) ? [{
      sourceMovementKey: rdlId,
      sourceName: 'Barbell RDL',
      sheetMovementAddress: 'B14',
      cells: [
        { setNumber: 1, field: 'REPS', address: 'K14', current: '10', proposed: '8', action: 'WRITE' },
        { setNumber: 1, field: 'LOAD', address: 'L14', current: '5', proposed: '7.5', action: 'WRITE' },
        { setNumber: 2, field: 'REPS', address: 'N14', current: '10', proposed: '8', action: 'WRITE' },
        { setNumber: 3, field: 'REPS', address: 'Q14', current: '10', proposed: null, action: 'CLEAR' },
      ],
    }, {
      sourceMovementKey: holdId,
      sourceName: 'Hollow hold',
      sheetMovementAddress: 'B15',
      cells: [
        { setNumber: 1, field: 'REPS', address: 'K15', current: '40 sec', proposed: '45 sec', action: 'WRITE' },
        { setNumber: 2, field: 'REPS', address: 'N15', current: '40 sec', proposed: '42 sec', action: 'WRITE' },
      ],
    }] : [],
    cellCount: ['PREPARED', 'SUCCEEDED'].includes(state) ? 6 : 0,
    finishedAt: state === 'SUCCEEDED' ? '2026-08-12T00:47:00Z' : null,
    ...overrides,
  }
}

async function expectNoHorizontalOverflow(page) {
  await expect.poll(() => page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))).toEqual({
    clientWidth: page.viewportSize().width,
    scrollWidth: page.viewportSize().width,
  })
}

test('a completed workout without a linked Sheet uses the app-owned Sheet selector', async ({ page }) => {
  let current = write('NEEDS_TAB')
  await page.route(`**/api/training/weeks/2/workouts/${workoutId}`, (route) => route.fulfill({
    contentType: 'application/json', body: JSON.stringify(detail()),
  }))
  await page.route(`**/api/training/sessions/${sessionId}/write-destination`, (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ sessionId, linkedSheetTitle: null, googleConnected: true }),
  }))
  await page.route('**/api/training/google/sheets*', (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({
      sheets: [{ selectionToken: 'opaque-sheet-token', name: 'JUNDA – M1', modifiedAt: '2026-08-11T00:00:00Z' }],
      nextPageToken: null,
    }),
  }))
  await page.route(`**/api/training/sessions/${sessionId}/writes`, async (route) => {
    expect(await route.request().postDataJSON()).toEqual({ selectionToken: 'opaque-sheet-token' })
    await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify(current) })
  })
  await page.route(`**/api/training/writes/${writeId}`, (route) => route.fulfill({
    contentType: 'application/json', body: JSON.stringify(current),
  }))
  await page.route(`**/api/training/writes/${writeId}/tab`, async (route) => {
    expect(await route.request().postDataJSON()).toEqual({ tabKey: 'tab-101' })
    current = write('NEEDS_WEEK')
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(current) })
  })

  await page.goto(`/training/weeks/2/workouts/${workoutId}/write`)

  await expect(page.getByRole('heading', { name: 'Choose a Sheet' })).toBeVisible()
  await page.getByRole('button', { name: 'Choose JUNDA – M1' }).click()
  await expect(page.getByRole('heading', { name: 'Choose Sheet tab' })).toBeVisible()
  await page.getByRole('button', { name: 'Full Body WO 1' }).click()
  await page.getByRole('button', { name: 'Choose week in Full Body WO 1' }).click()
  await expect(page.getByRole('heading', { name: 'Choose Sheet week' })).toBeVisible()
})

test('completed workout writes one chosen Sheet week through correction, exact preview, and verification', async ({ page }) => {
  let current = write('NEEDS_TAB')

  await page.route(`**/api/training/weeks/2/workouts/${workoutId}`, (route) => route.fulfill({
    contentType: 'application/json', body: JSON.stringify(detail()),
  }))
  await page.route(`**/api/training/sessions/${sessionId}/write-destination`, (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ sessionId, linkedSheetTitle: 'JUNDA – M1', googleConnected: true }),
  }))
  await page.route(`**/api/training/sessions/${sessionId}/writes`, (route) => route.fulfill({
    status: 201, contentType: 'application/json', body: JSON.stringify(current),
  }))
  await page.route(`**/api/training/writes/${writeId}`, (route) => route.fulfill({
    contentType: 'application/json', body: JSON.stringify(current),
  }))
  await page.route(`**/api/training/writes/${writeId}/tab`, async (route) => {
    expect(await route.request().postDataJSON()).toEqual({ tabKey: 'tab-101' })
    current = write('NEEDS_WEEK')
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(current) })
  })
  await page.route(`**/api/training/writes/${writeId}/week`, async (route) => {
    expect(await route.request().postDataJSON()).toEqual({ weekNumber: 5 })
    current = write('REVIEW')
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(current) })
  })
  await page.route(`**/api/training/writes/${writeId}/matches`, async (route) => {
    expect(await route.request().postDataJSON()).toEqual({
      tabKey: 'tab-101',
      movements: [
        { sourceMovementKey: rdlId, sheetMovementAddress: 'B14' },
        { sourceMovementKey: holdId, sheetMovementAddress: 'B15' },
      ],
    })
    current = write('REVIEW', {
      matches: write('PREPARED').matches,
    })
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(current) })
  })
  await page.route(`**/api/training/writes/${writeId}/preview`, (route) => {
    current = write('PREPARED')
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify(current) })
  })
  await page.route(`**/api/training/writes/${writeId}/confirm`, (route) => {
    current = write('SUCCEEDED')
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify(current) })
  })

  await page.goto(`/training/weeks/2/workouts/${workoutId}/write`)
  await expect(page.getByRole('heading', { name: 'Choose Sheet tab' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Macro Check In' })).toBeVisible()
  await page.getByRole('button', { name: 'Full Body WO 1' }).click()
  await page.getByRole('button', { name: 'Choose week in Full Body WO 1' }).click()
  await expect(page.getByRole('heading', { name: 'Choose Sheet week' })).toBeVisible()
  await page.getByRole('button', { name: 'Week 5' }).click()

  await expect(page.getByRole('heading', { name: 'Review matches' })).toBeVisible()
  await expect(page.getByLabel('Sheet row')).toHaveCount(2)
  await page.getByLabel('Sheet row').nth(1).click()
  await page.getByRole('option', { name: 'Hollow body hold · B15' }).click()
  await page.getByRole('button', { name: 'Preview execution' }).click()

  await expect(page.getByRole('heading', { name: 'Review execution' })).toBeVisible()
  await expect(page.getByText('Q14')).toBeVisible()
  await expect(page.getByText('clear')).toBeVisible()
  await expect(page.getByText('App Week 2')).toBeVisible()
  await expect(page.getByText('Sheet Week 5')).toBeVisible()
  await page.getByRole('button', { name: 'Write 6 cells' }).click()

  await expect(page.getByText('Written')).toBeVisible()
  await expect(page.getByText('JUNDA – M1 · Week 5')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Finish' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Write again' })).toHaveCount(0)
  await expectNoHorizontalOverflow(page)

  await page.getByRole('button', { name: 'Finish' }).click()
  await expect(page).toHaveURL(/\/training$/)
})

test('an imported workout resolves its destination without Sheet, tab, week, or model selection', async ({ page }) => {
  let current = write('RESOLVED')
  await page.route(`**/api/training/weeks/2/workouts/${workoutId}`, (route) => route.fulfill({
    contentType: 'application/json', body: JSON.stringify(detail()),
  }))
  await page.route(`**/api/training/sessions/${sessionId}/write-destination`, (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ sessionId, linkedSheetTitle: 'JUNDA – M1', googleConnected: true }),
  }))
  await page.route(`**/api/training/sessions/${sessionId}/writes`, (route) => route.fulfill({
    status: 201, contentType: 'application/json', body: JSON.stringify(current),
  }))
  await page.route(`**/api/training/writes/${writeId}`, (route) => route.fulfill({
    contentType: 'application/json', body: JSON.stringify(current),
  }))
  await page.route(`**/api/training/writes/${writeId}/preview`, (route) => {
    current = write('PREPARED')
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify(current) })
  })

  await page.goto(`/training/weeks/2/workouts/${workoutId}/write`)

  await expect(page.getByText('Resolved from import')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Destination' })).toBeVisible()
  await expect(page.getByText('→ Romanian Deadlift · B14')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Choose a Sheet' })).toHaveCount(0)
  await expect(page.getByRole('heading', { name: 'Choose Sheet tab' })).toHaveCount(0)
  await expect(page.getByRole('heading', { name: 'Choose Sheet week' })).toHaveCount(0)
  await page.getByRole('button', { name: 'Preview execution' }).click()
  await expect(page.getByRole('heading', { name: 'Review execution' })).toBeVisible()
  await expectNoHorizontalOverflow(page)
})

test('Edit on a resolved destination enters tab-first selection', async ({ page }) => {
  let current = write('RESOLVED')
  await page.route(`**/api/training/weeks/2/workouts/${workoutId}`, (route) => route.fulfill({
    contentType: 'application/json', body: JSON.stringify(detail()),
  }))
  await page.route(`**/api/training/writes/${writeId}`, (route) => route.fulfill({
    contentType: 'application/json', body: JSON.stringify(current),
  }))
  await page.route(`**/api/training/writes/${writeId}/selection`, (route) => {
    current = write('NEEDS_TAB')
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify(current) })
  })

  await page.goto(`/training/weeks/2/workouts/${workoutId}/write?attempt=${writeId}`)
  await page.getByRole('button', { name: 'Edit' }).click()

  await expect(page.getByRole('heading', { name: 'Choose Sheet tab' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Macro Check In' })).toBeVisible()
})

test('structural drift stops the write and offers a fresh Sheet scan', async ({ page }) => {
  const drifted = write('DRIFT_ABORTED', {
    detail: 'Romanian Deadlift moved in the Sheet. Scan the Sheet again.',
  })
  await page.route(`**/api/training/weeks/2/workouts/${workoutId}`, (route) => route.fulfill({
    contentType: 'application/json', body: JSON.stringify(detail()),
  }))
  await page.route(`**/api/training/writes/${writeId}`, (route) => route.fulfill({
    contentType: 'application/json', body: JSON.stringify(drifted),
  }))

  await page.goto(`/training/weeks/2/workouts/${workoutId}/write?attempt=${writeId}`)

  await expect(page.getByRole('heading', { name: 'Sheet changed' })).toBeVisible()
  await expect(page.getByText(/Romanian Deadlift moved/)).toBeVisible()
  await expect(page.getByRole('button', { name: 'Scan Sheet again' })).toBeVisible()
  await expectNoHorizontalOverflow(page)
})

test('an uncertain send verifies by read-back without offering a blind second write', async ({ page }) => {
  let current = write('UNKNOWN', { detail: 'Check the Sheet before trying again.' })
  await page.route(`**/api/training/weeks/2/workouts/${workoutId}`, (route) => route.fulfill({
    contentType: 'application/json', body: JSON.stringify(detail()),
  }))
  await page.route(`**/api/training/writes/${writeId}`, (route) => route.fulfill({
    contentType: 'application/json', body: JSON.stringify(current),
  }))
  await page.route(`**/api/training/writes/${writeId}/verify`, (route) => {
    current = write('SUCCEEDED')
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify(current) })
  })

  await page.goto(`/training/weeks/2/workouts/${workoutId}/write?attempt=${writeId}`)

  await expect(page.getByRole('heading', { name: 'Check Sheet' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Verify' })).toBeVisible()
  await expect(page.getByRole('button', { name: /Write/ })).toHaveCount(0)
  await page.getByRole('button', { name: 'Verify' }).click()
  await expect(page.getByText('Written')).toBeVisible()
})
