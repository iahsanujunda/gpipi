import { expect, test } from '@playwright/test'

async function expectNoHorizontalOverflow(page) {
  await expect.poll(() => page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))).toEqual({
    clientWidth: page.viewportSize().width,
    scrollWidth: page.viewportSize().width,
  })
}

function cadenceOverview(workoutNames) {
  return {
    program: {
      id: '60000000-0000-0000-0000-000000000099',
      name: 'Flexible cadence',
      note: null,
      startsOn: '2026-08-03',
      active: true,
    },
    currentWeekNumber: 1,
    selectedWeekNumber: 1,
    availableWeekNumbers: [1, 2],
    workouts: workoutNames.map((workoutName, index) => ({
      weekId: `62000000-0000-0000-0000-${String(index + 1).padStart(12, '0')}`,
      workoutId: `61000000-0000-0000-0000-${String(index + 1).padStart(12, '0')}`,
      workoutName,
      status: 'NOT_STARTED',
      sessionId: null,
      performedOn: null,
      setCount: 0,
      updatedAt: null,
    })),
  }
}

for (const workoutNames of [
  ['Only workout'],
  ['Day A', 'Day B', 'Day C'],
]) {
  test(`week overview renders a ${workoutNames.length}-workout cadence without horizontal overflow`, async ({ page }) => {
    await page.route(/\/api\/training\?week=1$/, (route) => route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify(cadenceOverview(workoutNames)),
    }))

    await page.goto('/training/weeks/1')

    await expect(page.getByRole('article')).toHaveCount(workoutNames.length)
    for (const workoutName of workoutNames) {
      await expect(page.getByRole('heading', { name: workoutName })).toBeVisible()
    }
    await expect(page.getByText(`0 of ${workoutNames.length} resolved`)).toBeVisible()
    await expectNoHorizontalOverflow(page)
  })
}

test('navigation opens the derived current week and preserves week history return paths', async ({ page }) => {
  await page.goto('/wallets')
  await page.getByRole('button', { name: 'Open navigation' }).click()
  await page.getByRole('link', { name: 'Training' }).click()

  await expect(page).toHaveURL(/\/training\/weeks\/3$/)
  await expect(page.getByRole('heading', { name: 'M1', exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Edit M1 program' })).toBeVisible()
  await expect(page.getByText('Program settings')).toHaveCount(0)
  await expect(page.getByRole('heading', { name: 'Week 3', exact: true })).toBeVisible()
  await expect(page.getByText('0 of 2 resolved')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Add workout' })).toBeVisible()
  await expectNoHorizontalOverflow(page)

  await page.getByRole('button', { name: 'Open navigation' }).click()
  await expect(page.getByRole('button', { name: 'Add Program' })).toBeVisible()
  await page.getByRole('button', { name: 'Close navigation' }).click()

  await page.getByRole('button', { name: 'Previous authored week' }).click()
  await expect(page).toHaveURL(/\/training\/weeks\/2$/)
  await expect(page.getByText('2 of 2 resolved')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Add workout' })).toHaveCount(0)

  const pastWorkout = page.getByRole('article').filter({ hasText: 'Full Body 1' })
  await pastWorkout.getByRole('link', { name: 'Review' }).click()
  await expect(page.getByRole('heading', { name: 'Workout history' })).toBeVisible()
  await expect(page.getByText(/Historical targets are preserved/)).toBeVisible()

  await page.getByRole('link', { name: 'Week 2' }).click()
  await expect(page).toHaveURL(/\/training\/weeks\/2$/)
  await page.getByRole('button', { name: 'Current · Week 3' }).click()
  await expect(page).toHaveURL(/\/training\/weeks\/3$/)
})

test('blank execution, stable slot repair, finish, and completed edits work in the browser', async ({ page }) => {
  await page.goto('/training/weeks/3')
  const workoutCard = page.getByRole('article').filter({ hasText: 'Full Body 2' })
  await workoutCard.getByRole('link', { name: 'Open' }).click()

  const editor = page.getByRole('form', { name: 'Set editor for Rear-foot elevated split squat' })
  await expect(editor.getByLabel('Reps / side')).toHaveValue('')
  await expect(editor.getByLabel('Load kg')).toHaveValue('')
  await expect(editor.getByText('Empty until logged')).toBeVisible()

  await editor.getByLabel('Reps / side').fill('11')
  await editor.getByLabel('Load kg').fill('8')
  await editor.getByLabel('RIR').fill('2')
  await editor.getByRole('button', { name: 'Log Set 1' }).click()
  await expect(page.getByRole('status')).toContainText('Set 1 saved')

  await editor.getByLabel('Reps / side').fill('10')
  await editor.getByLabel('Load kg').fill('8')
  await editor.getByRole('button', { name: 'Log Set 2' }).click()
  await expect(page.getByRole('button', { name: 'Edit set 2 for Rear-foot elevated split squat' })).toBeVisible()

  await page.getByRole('button', { name: 'Delete set 1 for Rear-foot elevated split squat' }).click()
  await expect(page.getByRole('button', { name: 'Edit set 1 for Rear-foot elevated split squat' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Edit set 2 for Rear-foot elevated split squat' })).toBeVisible()
  await expect(editor.getByRole('button', { name: 'Correct Set 1' })).toBeVisible()
  await expect(editor.getByRole('button', { name: 'Log new Set 3' })).toBeVisible()

  await editor.getByLabel('Reps / side').fill('12')
  await editor.getByRole('button', { name: 'Log Set 1' }).click()
  await expect(page.getByRole('button', { name: 'Edit set 1 for Rear-foot elevated split squat' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Edit set 2 for Rear-foot elevated split squat' })).toBeVisible()

  await page.getByRole('button', { name: 'Finish workout' }).click()
  await expect(page.getByRole('heading', { name: 'Workout history' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Resume workout' })).toBeVisible()

  await page.getByRole('button', { name: 'Edit set 1 for Rear-foot elevated split squat' }).click()
  await editor.getByLabel('Reps / side').fill('9')
  await editor.getByRole('button', { name: 'Save Set 1' }).click()
  await expect(page.getByRole('heading', { name: 'Workout history' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Resume workout' })).toBeVisible()
  await expectNoHorizontalOverflow(page)
})

test('exercise prescriptions stay readable with video and cue fallbacks on a phone', async ({ page }) => {
  await page.route('https://i.ytimg.com/**', (route) => route.fulfill({
    contentType: 'image/svg+xml',
    body: '<svg xmlns="http://www.w3.org/2000/svg" width="480" height="270"><rect width="480" height="270" fill="#b7d7d7"/></svg>',
  }))

  await page.goto('/training/weeks/3/workouts/61000000-0000-0000-0000-000000000001')

  const thumbnail = page.getByRole('img', { name: 'Video thumbnail for Goblet squat' })
  await expect(thumbnail).toBeVisible()
  await expect(thumbnail).toHaveAttribute('src', 'https://i.ytimg.com/vi/jO2Jl9eZpXk/hqdefault.jpg')

  const prescription = page.getByRole('region', { name: 'Prescription for Goblet squat' })
  await expect(prescription.getByText('Sets').locator('..').getByText('3', { exact: true })).toBeVisible()
  await expect(prescription.getByText('Reps').locator('..').getByText('10–12')).toBeVisible()
  await expect(prescription.getByText('Load').locator('..').getByText('20–25 kg')).toBeVisible()
  await expect(prescription.getByText('Rest').locator('..').getByText('60 sec')).toBeVisible()

  await prescription.getByText('Cues').click()
  const cues = prescription.getByText(/Set-up:/)
  await expect(cues).toBeVisible()
  await expect(cues).toHaveCSS('white-space', 'pre-wrap')
  expect(await cues.textContent()).toContain('\n\nDuring the rep:\n- Control the descent.')

  const fallback = page.getByRole('link', { name: 'Open demo video for Suitcase carry' })
  await expect(fallback.getByText('Demo video')).toBeVisible()
  await expect(page.getByText(/Preview unavailable/i)).toHaveCount(0)
  await expectNoHorizontalOverflow(page)
})

test('manual flow creates program details, then adds a workout from empty current Week 1', async ({ page }) => {
  const programId = '60000000-0000-0000-0000-000000000099'
  const workoutId = '61000000-0000-0000-0000-000000000099'
  let activeProgram = null
  let workouts = []

  await page.route(/\/api\/training(?:\?week=\d+)?$/, (route) => {
    if (!activeProgram) return route.fulfill({ status: 204 })
    return route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        program: activeProgram,
        currentWeekNumber: 1,
        selectedWeekNumber: 1,
        availableWeekNumbers: [1],
        workouts,
      }),
    })
  })
  await page.route('**/api/training/programs', async (route) => {
    if (route.request().method() === 'GET') {
      return route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify(activeProgram ? [activeProgram] : []),
      })
    }
    expect(await route.request().postDataJSON()).toEqual({
      name: 'M2',
      startsOn: null,
      note: 'Pregnancy strength block',
    })
    activeProgram = { id: programId, name: 'M2', startsOn: null, note: 'Pregnancy strength block', active: true }
    return route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ id: programId }) })
  })
  await page.route(`**/api/training/programs/${programId}`, async (route) => {
    expect(route.request().method()).toBe('PUT')
    const input = await route.request().postDataJSON()
    activeProgram = { ...activeProgram, ...input }
    return route.fulfill({ status: 204 })
  })
  await page.route('**/api/training/exercises', (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify([]),
  }))
  await page.route(`**/api/training/programs/${programId}/weeks/1/workouts`, async (route) => {
    expect(await route.request().postDataJSON()).toMatchObject({
      name: 'Full Body 1',
      groups: [{
        label: 'A',
        kind: 'STRAIGHT_SET',
        prescriptions: [{
          exerciseName: 'Front squat',
          createExercise: true,
          executionType: 'REPS',
          sets: '3',
          reps: '8–10',
        }],
      }],
    })
    workouts = [{
      weekId: '62000000-0000-0000-0000-000000000099',
      workoutId,
      workoutName: 'Full Body 1',
      status: 'NOT_STARTED',
      sessionId: null,
      performedOn: null,
      setCount: 0,
      updatedAt: null,
    }]
    return route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ id: workoutId }) })
  })

  await page.goto('/training')
  await expect(page.getByRole('heading', { name: 'No Active Program' })).toBeVisible()
  await expect(page.getByText(/author the prescribed/i)).toHaveCount(0)
  await page.getByRole('button', { name: 'Open navigation' }).click()
  await page.getByRole('button', { name: 'Add Program' }).click()

  await expect(page.getByRole('heading', { name: 'Create Program' })).toBeVisible()
  await expect(page.getByText('Import from Google Sheet')).toHaveCount(0)
  await page.getByLabel('Program name').fill('M2')
  await page.getByLabel('Program note (optional)').fill('Pregnancy strength block')
  await page.getByRole('button', { name: 'Create Program' }).click()

  await expect(page).toHaveURL(/\/training\/weeks\/1$/)
  await expect(page.getByText('No workouts yet')).toBeVisible()
  await page.getByRole('link', { name: 'Edit M2 program' }).click()
  await expect(page.getByRole('heading', { name: 'Edit Program' })).toBeVisible()
  await expect(page.getByLabel('Program name')).toHaveValue('M2')
  await expect(page.getByLabel('Program note (optional)')).toHaveValue('Pregnancy strength block')
  await page.getByLabel('Program name').fill('M2 revised')
  await page.getByRole('button', { name: 'Save Program' }).click()

  await expect(page).toHaveURL(/\/training\/weeks\/1$/)
  await expect(page.getByRole('heading', { name: 'M2 revised' })).toBeVisible()
  await page.getByRole('button', { name: 'Add workout' }).click()
  await page.getByRole('link', { name: 'Create manually' }).click()

  await page.getByLabel('Workout name').fill('Full Body 1')
  await page.getByLabel('Exercise — select or create').click()
  await page.getByRole('option', { name: 'Create a new exercise…' }).click()
  await page.getByLabel('New exercise name').fill('Front squat')
  await page.getByLabel('Execution type — confirm').click()
  await page.getByRole('option', { name: 'Reps', exact: true }).click()
  await page.getByLabel('Sets').fill('3')
  await page.getByLabel('Reps / time').fill('8–10')
  await page.getByRole('button', { name: 'Save Workout' }).first().click()

  await expect(page).toHaveURL(/\/training\/weeks\/1$/)
  await expect(page.getByRole('heading', { name: 'Full Body 1' })).toBeVisible()
  await expectNoHorizontalOverflow(page)
})
