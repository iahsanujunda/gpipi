import { expect, test } from '@playwright/test'
import { holdAndSampleTactileMotion } from './motion'

async function expectBottomSheetGeometry(page, sheet) {
  await expect(sheet).toBeVisible()
  await expect.poll(
    () => sheet.evaluate((element) => {
      const transform = getComputedStyle(element).transform
      if (transform === 'none') return 0
      return new DOMMatrixReadOnly(transform).m42
    }),
  ).toBeCloseTo(0, 0)
  const box = await sheet.boundingBox()
  expect(box).not.toBeNull()
  expect(box.y).toBeGreaterThanOrEqual(23)
  expect(box.height).toBeLessThanOrEqual(page.viewportSize().height - 23)
  expect(Math.abs((box.y + box.height) - page.viewportSize().height)).toBeLessThanOrEqual(2)
}

test.beforeEach(async ({ page }) => {
  await page.goto('/budgets')
  await expect(page.getByRole('heading', { name: 'Budgeting', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Eating Out' })).toBeVisible()
})

test('toggle buttons provide tactile feedback while pressed', async ({ page }) => {
  await page.getByRole('button', { name: 'Edit Eating Out' }).click()
  const sheet = page.getByRole('dialog', { name: 'Edit Eating Out' })
  await expectBottomSheetGeometry(page, sheet)

  const pressed = await holdAndSampleTactileMotion(
    page,
    sheet.getByRole('button', { name: 'Monthly' }),
  )

  expect(pressed.scale).toBeCloseTo(0.98, 2)
  expect(pressed.y).toBeCloseTo(1, 1)
})

test('shows exact utilization states on mobile and in the wider budget table', async ({ page }) => {
  const eatingOut = page.getByRole('progressbar', { name: 'Eating Out utilization' })
  await expect(eatingOut).toHaveAttribute('aria-valuenow', '80')
  await expect(eatingOut).toHaveAttribute(
    'aria-valuetext',
    '80% used; ¥12,000 spent of ¥15,000',
  )
  await expect(page.getByText('¥3,000 left').first()).toBeVisible()

  const transport = page.getByRole('progressbar', { name: 'Transport utilization' })
  await transport.scrollIntoViewIfNeeded()
  await expect(transport).toHaveAttribute('aria-valuenow', '100')
  await expect(transport).toHaveAttribute(
    'aria-valuetext',
    '110% used; ¥22,000 spent of ¥20,000',
  )
  await expect(page.getByText('¥2,000 over').first()).toBeVisible()

  const homeRepairs = page
    .locator('[data-budget-id="10000000-0000-0000-0000-000000000005"]')
    .first()
  await expect(homeRepairs.getByText('No cap set')).toBeVisible()
  await expect(homeRepairs.getByRole('progressbar')).toHaveCount(0)

  await page.setViewportSize({ width: 900, height: 800 })
  await page.reload()

  const mediumTable = page.getByRole('table', { name: 'Active budget lines' })
  await expect(mediumTable).toBeVisible()
  const mediumTableBox = await mediumTable.boundingBox()
  const lastEditBox = await mediumTable.getByRole('button', { name: 'Edit' }).last().boundingBox()
  expect(mediumTableBox).not.toBeNull()
  expect(lastEditBox).not.toBeNull()
  expect(lastEditBox.x + lastEditBox.width)
    .toBeLessThanOrEqual(mediumTableBox.x + mediumTableBox.width + 1)

  await page.setViewportSize({ width: 1280, height: 800 })
  await page.reload()

  const table = page.getByRole('table', { name: 'Active budget lines' })
  await expect(table).toBeVisible()
  await expect(table.getByRole('columnheader', { name: 'Spent / cap' })).toBeVisible()
  await expect(table.getByRole('columnheader', { name: 'Difference' })).toBeVisible()
  await expect(table.getByRole('row', { name: /Transport/ })).toContainText('¥2,000 over')
  await expect(table.getByRole('row', { name: /Home repairs/ })).toContainText('No cap set')
  await expect(table.getByRole('row', { name: /Home repairs/ })).not.toContainText('over')
  await expect(page.getByText('5 lines · 1 over cap')).toBeVisible()
})

test('launcher animates its main icon and staggered page action, then removes the action on Activity', async ({ page }) => {
  const launcher = page.getByRole('button', { name: 'Open navigation' })
  const navigationMask = page.getByTestId('navigation-mask')
  await expect(launcher).toHaveAttribute('data-launcher-state', 'closed')
  await expect(launcher).toHaveAttribute('data-icon-duration-ms', '280')
  await expect(navigationMask).toHaveAttribute('data-mask-state', 'clear')

  const samples = await launcher.evaluate(async (button) => {
    const brand = button.querySelector('[data-launcher-icon="brand"]')
    const close = button.querySelector('[data-launcher-icon="close"]')
    const action = document.querySelector('[data-menu-entry^="page-action-"]')
    const sample = () => {
      const actionTransform = getComputedStyle(action).transform
      return {
        actionOpacity: Number(getComputedStyle(action).opacity),
        actionY: actionTransform === 'none'
          ? 0
          : new DOMMatrixReadOnly(actionTransform).m42,
        brandOpacity: Number(getComputedStyle(brand).opacity),
        closeOpacity: Number(getComputedStyle(close).opacity),
      }
    }

    button.click()
    await new Promise(requestAnimationFrame)
    const start = sample()
    await new Promise((resolve) => setTimeout(resolve, 160))
    const middle = sample()
    await new Promise((resolve) => setTimeout(resolve, 360))
    const end = sample()
    return { start, middle, end }
  })

  expect(samples.start.actionOpacity).toBeLessThan(0.25)
  expect(samples.start.actionY).toBeGreaterThan(8)
  expect(samples.middle.actionOpacity).toBeGreaterThan(0)
  expect(samples.middle.actionOpacity).toBeLessThan(0.8)
  expect(samples.middle.actionY).toBeGreaterThan(4)
  expect(samples.middle.actionY).toBeLessThan(samples.start.actionY)
  expect(samples.end.actionOpacity).toBeCloseTo(1, 1)
  expect(samples.end.actionY).toBeCloseTo(0, 0)
  expect(samples.middle.brandOpacity).toBeGreaterThan(0)
  expect(samples.middle.brandOpacity).toBeLessThan(1)
  expect(samples.middle.closeOpacity).toBeGreaterThan(0)
  expect(samples.middle.closeOpacity).toBeLessThan(1)
  expect(samples.end.brandOpacity).toBeCloseTo(0, 1)
  expect(samples.end.closeOpacity).toBeCloseTo(1, 1)

  const action = page.getByRole('button', { name: /add budget line/i })
  await expect(navigationMask).toHaveAttribute('data-mask-state', 'dimmed')
  await expect.poll(
    () => navigationMask.evaluate(
      (element) => getComputedStyle(element, '::before').opacity,
    ),
  ).toBe('1')
  await expect(page.getByText('Page actions')).toBeVisible()
  await expect(page.getByText('Navigation')).toBeVisible()
  await expect(action).toBeVisible()
  await expect(action).toHaveAttribute('data-enter-duration-ms', '320')
  await expect(action).toHaveCSS('transition-duration', '0.32s')
  await expect(action).toHaveCSS('transition-timing-function', 'cubic-bezier(0.2, 0.75, 0.2, 1)')

  await page.getByRole('link', { name: 'Activity' }).click()
  await expect(page.getByRole('heading', { name: 'Activity', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Open navigation' }).click()
  await expect(page.getByRole('link', { name: 'Budgets' })).toBeVisible()
  await expect(page.getByRole('button', { name: /add budget line/i })).toHaveCount(0)
})

test('creates a budget through the mobile drawer review flow', async ({ page }) => {
  await page.getByRole('button', { name: 'Open navigation' }).click()
  await page.getByRole('button', { name: /add budget line/i }).click()

  const sheet = page.getByRole('dialog', { name: 'New budget line' })
  await expectBottomSheetGeometry(page, sheet)
  await expect(sheet).toHaveAttribute('data-motion', 'slide-from-bottom')
  await expect(sheet).toHaveAttribute('data-enter-duration-ms', '520')
  await expect(sheet).toHaveAttribute('data-exit-duration-ms', '320')

  await page.getByRole('textbox', { name: 'Name' }).fill('Pet care E2E')
  await page.getByRole('textbox', { name: 'Description' }).fill('Vet visits, pet food, and medicine')
  await page.getByRole('textbox', { name: 'Budget cap' }).fill('12000')
  await page.getByRole('button', { name: 'Review budget line' }).click()

  await expect(page.getByText('Confirm these details before creating the budget line.')).toBeVisible()
  await expect(page.getByText('¥12,000 · MONTHLY')).toBeVisible()
  await page.getByRole('button', { name: 'Create budget line' }).click()

  const exitingSheet = page.locator('[aria-label="New budget line"]')
  await expect(exitingSheet).toBeAttached()
  await page.waitForTimeout(120)
  const exitY = await exitingSheet.evaluate((element) => {
    const transform = getComputedStyle(element).transform
    return transform === 'none' ? 0 : new DOMMatrixReadOnly(transform).m42
  })
  expect(exitY).toBeGreaterThan(8)
  await expect(exitingSheet).not.toBeAttached()
  await expect(page.getByRole('status')).toContainText('Pet care E2E created')
  await expect(page.getByRole('heading', { name: 'Pet care E2E' })).toBeVisible()
})

test('keeps unsaved edits after the discard confirmation', async ({ page }) => {
  await page.getByRole('button', { name: 'Edit Eating Out' }).click()
  const sheet = page.getByRole('dialog', { name: 'Edit Eating Out' })
  await expectBottomSheetGeometry(page, sheet)

  await page.getByRole('textbox', { name: 'Budget cap' }).fill('18000')
  await page.getByRole('button', { name: 'Close budget editor' }).click()
  await expect(page.getByRole('heading', { name: 'Discard changes?' })).toBeVisible()

  await page.getByRole('button', { name: 'Keep editing' }).click()
  await expect(page.getByRole('textbox', { name: 'Budget cap' })).toHaveValue('18000')
})

test('uses the budget table and a bounded centered dialog on wider screens', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 })
  await page.reload()

  const table = page.getByRole('table', { name: 'Active budget lines' })
  await expect(table).toBeVisible()
  await table.getByRole('button', { name: 'Edit' }).first().click()

  const dialog = page.getByRole('dialog', { name: 'Edit Eating Out' })
  await expect(dialog).toBeVisible()
  await expect(dialog).not.toHaveAttribute('data-motion')
  const box = await dialog.boundingBox()
  expect(box).not.toBeNull()
  expect(box.y).toBeGreaterThan(24)
  expect(box.y + box.height).toBeLessThan(776)

  await page.getByRole('button', { name: 'Deactivate budget line' }).click()
  await expect(page.getByRole('heading', { name: 'Deactivate Eating Out?' })).toBeVisible()
  await page.getByRole('button', { name: 'Deactivate budget line' }).click()
  await expect(page.getByRole('status')).toContainText('Eating Out deactivated')
  await expect(page.getByRole('heading', { name: 'Eating Out' })).toHaveCount(0)
})
