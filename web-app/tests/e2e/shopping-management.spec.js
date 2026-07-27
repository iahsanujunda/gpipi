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

test('edits, removes, undoes, and restores shopping items without offering web add', async ({ page }) => {
  await page.goto('/shopping')

  await expect(page.getByRole('heading', { name: 'Shopping list', exact: true })).toBeVisible()
  await expect(page.getByText('New items start in Slack')).toBeVisible()
  await expect(page.getByRole('button', { name: /add/i })).toHaveCount(0)
  await expect(page.getByRole('heading', { name: 'Milk' })).toBeVisible()

  await page.getByRole('button', { name: 'Edit Milk' }).click()
  const editor = page.getByRole('dialog', { name: 'Edit Milk' })
  await expectBottomSheetGeometry(page, editor)
  await editor.getByRole('textbox', { name: 'Quantity' }).fill('3 cartons')
  await editor.getByRole('button', { name: 'Save changes' }).click()
  await expect(page.getByRole('status')).toContainText('Milk updated')
  await expect(page.getByText('3 cartons · Full-fat').first()).toBeVisible()

  await page.getByRole('button', { name: 'Edit Milk' }).click()
  await page.getByRole('dialog', { name: 'Edit Milk' })
    .getByRole('button', { name: 'Remove from list' })
    .click()
  await expect(page.getByRole('status')).toContainText('Milk removed from the list')
  await expect(page.getByRole('heading', { name: 'Milk' })).toHaveCount(0)

  await page.getByRole('button', { name: 'Undo' }).click()
  await expect(page.getByRole('status')).toContainText('Milk restored to the list')
  await expect(page.getByRole('heading', { name: 'Milk' })).toBeVisible()

  await page.getByRole('button', { name: /^History/ }).click()
  await expect(page.getByRole('heading', { name: 'Dishwasher tablets' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Eggs' })).toBeVisible()

  await page.getByRole('button', { name: 'View' }).nth(1).click()
  const boughtDetails = page.getByRole('dialog', { name: 'Bought Eggs' })
  await expectBottomSheetGeometry(page, boughtDetails)
  await expect(boughtDetails.getByText('Bought item')).toBeVisible()
  await expect(boughtDetails.getByRole('button', { name: /restore/i })).toHaveCount(0)
  await boughtDetails.getByRole('button', { name: 'Close item details' }).click()

  await page.getByRole('button', { name: 'View' }).first().click()
  const removedDetails = page.getByRole('dialog', { name: 'Removed Dishwasher tablets' })
  await expectBottomSheetGeometry(page, removedDetails)
  await removedDetails.getByRole('button', { name: 'Restore to active list' }).click()

  await expect(page.getByRole('heading', { name: 'Dishwasher tablets' })).toBeVisible()
  await expect(page.getByRole('status')).toContainText('Dishwasher tablets restored to the list')
})
