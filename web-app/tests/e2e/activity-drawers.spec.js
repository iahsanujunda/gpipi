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
  await page.goto('/activity')
  await expect(page.getByRole('heading', { name: 'Activity', exact: true })).toBeVisible()
  await expect(page.getByText('Life Supermarket')).toBeVisible()
})

test('mobile select drawer animates, stays below the viewport top, and dismisses through the revealed backdrop', async ({ page }) => {
  const category = page.getByRole('combobox', { name: 'Category' })
  const samples = await category.evaluate(async (trigger) => {
    trigger.click()
    await new Promise(requestAnimationFrame)
    await new Promise(requestAnimationFrame)

    const sheet = document.querySelector('[data-presentation="bottom-sheet-options"]')
    const sample = () => {
      const transform = getComputedStyle(sheet).transform
      return {
        height: sheet.getBoundingClientRect().height,
        y: transform === 'none' ? 0 : new DOMMatrixReadOnly(transform).m42,
      }
    }

    const start = sample()
    await new Promise((resolve) => setTimeout(resolve, 180))
    const middle = sample()
    await new Promise((resolve) => setTimeout(resolve, 420))
    const end = sample()
    return { start, middle, end }
  })

  expect(samples.start.y).toBeGreaterThan(samples.start.height * 0.15)
  expect(samples.middle.y).toBeGreaterThan(samples.middle.height * 0.08)
  expect(samples.middle.y).toBeLessThan(samples.start.y)
  expect(samples.end.y).toBeCloseTo(0, 0)

  const sheet = page.locator('[data-presentation="bottom-sheet-options"]')
  await expectBottomSheetGeometry(page, sheet)
  await expect(sheet).toHaveAttribute('data-motion', 'slide-from-bottom')
  await expect(sheet).toHaveAttribute('data-enter-duration-ms', '520')
  await expect(sheet).toHaveAttribute('data-exit-duration-ms', '320')
  await expect(sheet).toHaveCSS('transition-duration', '0.52s')
  await expect(sheet).toHaveCSS(
    'transition-timing-function',
    'cubic-bezier(0.2, 0.75, 0.2, 1)',
  )

  await page.mouse.click(12, 12)
  await expect(sheet).toBeAttached()
  await expect(sheet).toHaveCSS('transition-duration', '0.32s')
  await expect(sheet).toBeHidden()
  await expect(category).toBeFocused()
})

test('ordinary action buttons provide tactile feedback while pressed', async ({ page }) => {
  await page.getByRole('combobox', { name: 'Category' }).click()
  await page.getByRole('option', { name: 'Monthly Groceries' }).click()

  const pressed = await holdAndSampleTactileMotion(
    page,
    page.getByRole('button', { name: 'Clear filters' }),
  )

  expect(pressed.scale).toBeCloseTo(0.98, 2)
  expect(pressed.y).toBeCloseTo(1, 1)
})

test('icon buttons provide tactile feedback while pressed', async ({ page }) => {
  await page.getByRole('combobox', { name: 'From' }).click()
  const dateSheet = page.locator('[data-presentation="bottom-sheet-date-picker"]')
  await expectBottomSheetGeometry(page, dateSheet)

  const pressed = await holdAndSampleTactileMotion(
    page,
    dateSheet.getByRole('button', { name: 'Previous month' }),
  )

  expect(pressed.scale).toBeCloseTo(0.98, 2)
  expect(pressed.y).toBeCloseTo(1, 1)
})

test('list action buttons provide tactile feedback while pressed', async ({ page }) => {
  await page.getByRole('combobox', { name: 'Category' }).click()
  const optionSheet = page.locator('[data-presentation="bottom-sheet-options"]')
  await expectBottomSheetGeometry(page, optionSheet)

  const pressed = await holdAndSampleTactileMotion(
    page,
    optionSheet.getByRole('option', { name: 'Monthly Groceries' }),
  )

  expect(pressed.scale).toBeCloseTo(0.98, 2)
  expect(pressed.y).toBeCloseTo(1, 1)
})

test('mobile select and date drawers complete their interactions and restore focus', async ({ page }) => {
  const category = page.getByRole('combobox', { name: 'Category' })
  await category.click()
  const optionSheet = page.locator('[data-presentation="bottom-sheet-options"]')
  await expectBottomSheetGeometry(page, optionSheet)
  await optionSheet.getByRole('option', { name: 'Monthly Groceries' }).click()
  await expect(optionSheet).toBeHidden()
  await expect(category).toHaveValue('Monthly Groceries')
  await expect(category).toBeFocused()
  await expect(page.getByText('Ramen Station')).toBeHidden()

  const from = page.getByRole('combobox', { name: 'From' })
  await from.click()
  const dateSheet = page.locator('[data-presentation="bottom-sheet-date-picker"]')
  await expectBottomSheetGeometry(page, dateSheet)
  await expect(dateSheet.getByRole('grid')).toBeVisible()

  const firstDay = dateSheet.getByRole('grid').getByRole('button').first()
  await firstDay.click()
  await expect(dateSheet).toBeHidden()
  await expect(from).not.toHaveValue('')
  await expect(from).toBeFocused()
})

test('reduced motion removes the drawer transition', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Activity', exact: true })).toBeVisible()

  await page.getByRole('combobox', { name: 'Sort' }).click()
  const sheet = page.locator('[data-presentation="bottom-sheet-options"]')
  await expect(sheet).toBeVisible()
  await expect(sheet).toHaveAttribute('data-motion', 'reduced')
  await expect(sheet).toHaveAttribute('data-enter-duration-ms', '0')
  await expect(sheet).toHaveAttribute('data-exit-duration-ms', '0')
  await expect(sheet).toHaveCSS('transition-duration', '0s')

  const pressed = await holdAndSampleTactileMotion(
    page,
    sheet.getByRole('option', { name: 'Oldest first' }),
  )
  expect(pressed.scale).toBeCloseTo(1, 2)
  expect(pressed.y).toBeCloseTo(0, 1)
  expect(pressed.rippleDisplay).toBe('none')
})
