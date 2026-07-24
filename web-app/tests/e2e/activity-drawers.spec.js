import { expect, test } from '@playwright/test'

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
  await category.click()

  const sheet = page.locator('[data-presentation="bottom-sheet-options"]')
  await expectBottomSheetGeometry(page, sheet)
  await expect(sheet).toHaveAttribute('data-motion', 'slide-from-bottom')
  await expect(sheet).toHaveAttribute('data-enter-duration-ms', '380')
  await expect(sheet).toHaveAttribute('data-exit-duration-ms', '220')
  await expect(sheet).toHaveCSS('transition-duration', '0.38s')
  await expect(sheet).toHaveCSS(
    'transition-timing-function',
    'cubic-bezier(0.22, 1, 0.36, 1)',
  )

  await page.mouse.click(12, 12)
  await expect(sheet).toBeAttached()
  await expect(sheet).toHaveCSS('transition-duration', '0.22s')
  await expect(sheet).toBeHidden()
  await expect(category).toBeFocused()
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
})
