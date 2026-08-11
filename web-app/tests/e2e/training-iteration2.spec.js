import { expect, test } from '@playwright/test'

const programId = '60000000-0000-0000-0000-000000000001'
const workoutId = '61000000-0000-0000-0000-000000000001'
const importId = '70000000-0000-0000-0000-000000000001'
const importWeekId = '71000000-0000-0000-0000-000000000001'
const exerciseId = '72000000-0000-0000-0000-000000000001'

function mappedImport() {
  return {
    id: importId,
    programId,
    programName: 'M1',
    spreadsheetTitle: 'JUNDA – M1',
    selectedWeekNumber: 5,
    state: 'NEEDS_MAPPING',
    errorDetail: null,
    tabs: [{
      importWeekId,
      googleSheetId: 101,
      tabTitle: 'Full Body 1',
      decision: 'WORKOUT',
      targetWorkoutId: workoutId,
      newWorkoutName: null,
      startRow: 72,
      endRow: 91,
      executionBoundaryColumn: 11,
      extractionModel: null,
      groups: [],
    }, {
      importWeekId: null,
      googleSheetId: 202,
      tabTitle: 'Warming Up',
      decision: 'EXCLUDE',
      targetWorkoutId: null,
      newWorkoutName: null,
      groups: [],
    }],
  }
}

function reviewImport(decision = null) {
  const data = mappedImport()
  data.state = 'REVIEW'
  data.tabs[0].extractionModel = 'provider/training-model'
  data.tabs[0].groups = [{
    label: 'STRAIGHT SET',
    labelAddress: 'A74',
    kind: 'STRAIGHT_SET',
    prescriptions: [{
      movement: 'DB romanian deadlift',
      movementAddress: 'A75',
      executionTypeProposal: 'REPS',
      demoUrl: 'https://trainer.example/rdl',
      sets: '3',
      rest: '45-60sec',
      reps: '8 each',
      load: '6-7 kg each',
      rir: null,
      tempo: null,
      note: null,
      sourceCells: {
        movement: 'A75',
        demo_url: 'B75',
        sets: 'C75',
        rest: 'D75',
        reps: 'E75',
        load: 'F75',
        rir: null,
        tempo: null,
        note: null,
      },
      decision,
      exerciseId,
      newExerciseName: null,
      executionType: decision === 'MATCH' ? 'REPS_PER_SIDE' : null,
      rememberAsAlias: true,
    }],
  }]
  return data
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

test('disconnected import page has one clear Google connection action', async ({ page }) => {
  await page.route('**/api/training/google/status', (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ configured: true, connected: false, connectedAt: null, missingConfiguration: [] }),
  }))

  await page.goto('/training/program/import')

  await expect(page.getByRole('heading', { name: 'Import from Google Sheet' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Connect Google' })).toBeVisible()
  await expect(page.getByText('Connect your Google account')).toHaveCount(0)
  await expect(page.getByText(/The app receives access only/)).toHaveCount(0)
  await expectNoHorizontalOverflow(page)
})

test('one explicit week crosses Picker, mapping, extraction, review, and Apply', async ({ page }) => {
  let currentImport = mappedImport()
  currentImport.selectedWeekNumber = null
  currentImport.tabs = []

  await page.addInitScript(({ selectedId }) => {
    class DocsView {
      setMimeTypes() { return this }
      setSelectFolderEnabled() { return this }
    }
    class PickerBuilder {
      addView() { return this }
      setOAuthToken() { return this }
      setDeveloperKey() { return this }
      setAppId() { return this }
      setOrigin(origin) { window.__pickerOrigin = origin; return this }
      setCallback(callback) { this.callback = callback; return this }
      build() {
        return { setVisible: () => this.callback({ action: 'picked', docs: [{ id: selectedId }] }) }
      }
    }
    window.google = {
      picker: {
        Action: { PICKED: 'picked', CANCEL: 'cancel' },
        DocsView,
        PickerBuilder,
        ViewId: { SPREADSHEETS: 'spreadsheets' },
      },
    }
  }, { selectedId: 'sheet-selected-by-picker' })

  await page.route('**/api/training/google/status', (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ configured: true, connected: true, connectedAt: '2026-08-10T00:00:00Z', missingConfiguration: [] }),
  }))
  await page.route('**/api/training/google/picker-token', (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ accessToken: 'short-lived-picker-token', expiresIn: 3600, apiKey: 'picker-key', appId: '123456789' }),
  }))
  await page.route('**/api/training/exercises', (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify([{ id: exerciseId, name: 'Romanian deadlift', demoUrl: null, aliases: [] }]),
  }))
  await page.route(`**/api/training/programs/${programId}/imports`, async (route) => {
    expect((await route.request().postDataJSON()).spreadsheetId).toBe('sheet-selected-by-picker')
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify({ importId, spreadsheetTitle: 'JUNDA – M1', availableWeekNumbers: [3, 4, 5, 6] }),
    })
  })
  await page.route(`**/api/training/imports/${importId}`, (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify(currentImport),
  }))
  await page.route(`**/api/training/imports/${importId}/week`, async (route) => {
    expect(await route.request().postDataJSON()).toEqual({ weekNumber: 5 })
    currentImport.selectedWeekNumber = 5
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        importId,
        selectedWeekNumber: 5,
        workouts: [{ id: workoutId, name: 'Full Body 1' }],
        tabs: [{
          googleSheetId: 101,
          tabTitle: 'Full Body 1',
          present: true,
          startRow: 72,
          endRow: 91,
          executionBoundaryColumn: 11,
          executionHeaderAddress: 'K72',
          executionHeaderValue: 'Eksekusi Week 5',
          boundaryAmbiguous: false,
        }, {
          googleSheetId: 202,
          tabTitle: 'Warming Up',
          present: false,
          startRow: null,
          endRow: null,
          executionBoundaryColumn: null,
          executionHeaderAddress: null,
          executionHeaderValue: null,
          boundaryAmbiguous: false,
        }],
      }),
    })
  })
  await page.route(`**/api/training/imports/${importId}/mapping`, async (route) => {
    const body = await route.request().postDataJSON()
    expect(body.tabs[0]).toMatchObject({ decision: 'WORKOUT', startRow: 72, endRow: 91 })
    expect(body.tabs[1]).toMatchObject({ decision: 'EXCLUDE' })
    currentImport = mappedImport()
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(currentImport) })
  })
  await page.route(`**/api/training/imports/${importId}/extract`, (route) => {
    currentImport = reviewImport()
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify(currentImport) })
  })
  await page.route(`**/api/training/imports/${importId}/review`, async (route) => {
    expect((await route.request().postDataJSON()).workouts[0].groups[0].prescriptions[0]).toMatchObject({
      decision: 'MATCH',
      exerciseId,
      executionType: 'REPS_PER_SIDE',
    })
    currentImport = reviewImport('MATCH')
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(currentImport) })
  })
  await page.route(`**/api/training/imports/${importId}/apply`, (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ weekNumber: 5 }),
  }))
  await page.route(/\/api\/training\?week=5$/, (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({
      program: { id: programId, name: 'M1', note: null, startsOn: null, active: true },
      currentWeekNumber: 5,
      selectedWeekNumber: 5,
      availableWeekNumbers: [2, 3, 4, 5],
      workouts: [],
    }),
  }))

  await page.goto('/training/program/import')
  await page.getByRole('button', { name: 'Choose Google Sheet' }).click()
  expect(await page.evaluate(() => window.__pickerOrigin)).toBe(await page.evaluate(() => window.location.origin))
  await page.getByRole('button', { name: 'Week 5' }).click()
  await expect(page.getByText('Only Week 5 will cross into the app')).toBeVisible()
  await expect(page.getByText('Warming Up')).toBeVisible()
  await page.getByRole('button', { name: 'Confirm Week 5 scope' }).click()
  await page.getByRole('button', { name: 'Extract Week 5' }).click()

  await expect(page.getByText('DB romanian deadlift')).toBeVisible()
  await expect(page.getByText(/No session or performed set will be created/)).toBeVisible()
  await page.getByLabel('Exercise decision').click()
  await page.getByRole('option', { name: 'Match existing exercise' }).click()
  await page.getByLabel('Existing exercise').click()
  await page.getByRole('option', { name: 'Romanian deadlift' }).click()
  await page.getByLabel('Execution type — confirm').click()
  await page.getByRole('option', { name: 'Reps per side' }).click()
  await page.getByRole('button', { name: 'Save reviewed week' }).click()
  await page.getByRole('button', { name: 'Apply Week 5' }).click()

  await expect(page).toHaveURL(/\/training\/weeks\/5$/)
  await expectNoHorizontalOverflow(page)
})
