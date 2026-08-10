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
  await expect(page.getByRole('heading', { name: 'Training', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Week 3', exact: true })).toBeVisible()
  await expect(page.getByText('0 of 2 resolved')).toBeVisible()
  await expectNoHorizontalOverflow(page)

  await page.getByRole('button', { name: 'Previous authored week' }).click()
  await expect(page).toHaveURL(/\/training\/weeks\/2$/)
  await expect(page.getByText('2 of 2 resolved')).toBeVisible()

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

test('manual authoring copy-forwards a reviewed week without assuming a block length', async ({ page }) => {
  await page.goto('/training/program')

  await expect(page.getByRole('heading', { name: 'Training program' })).toBeVisible()
  await expect(page.getByText(/Human-reviewed authoring/)).toBeVisible()
  await page.getByLabel('Program name').fill('M2')
  await page.getByLabel('Exercise — select or create').click()
  await page.getByRole('option', { name: 'Create a new exercise…' }).click()
  await page.getByLabel('New exercise name').fill('Front squat')
  await page.getByLabel('Execution type — confirm').click()
  await page.getByRole('option', { name: 'Reps', exact: true }).click()
  await page.getByLabel('Sets').fill('3')
  await page.getByLabel('Reps / time').fill('8–10')

  await page.getByRole('button', { name: 'Duplicate this week' }).click()

  const weekInputs = page.getByLabel('Week')
  await expect(weekInputs).toHaveCount(2)
  await expect(weekInputs.nth(0)).toHaveValue('1')
  await expect(weekInputs.nth(1)).toHaveValue('2')
  await expect(page.getByLabel('New exercise name')).toHaveCount(2)
  await expect(page.getByLabel('New exercise name').nth(1)).toHaveValue('Front squat')
  await expect(page.getByLabel('Execution type — confirm').nth(1)).toHaveText(/Reps/)
  await expect(page.getByText(/expected week/i)).toHaveCount(0)
  await expectNoHorizontalOverflow(page)
})
