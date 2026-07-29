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

async function expectBottomSheetGeometry(page, sheet) {
  await expect(sheet).toBeVisible()
  await expect(sheet).toHaveAttribute('data-motion', 'slide-from-bottom')
  await expect.poll(
    () => sheet.evaluate((element) => {
      const transform = getComputedStyle(element).transform
      return transform === 'none' ? 0 : new DOMMatrixReadOnly(transform).m42
    }),
  ).toBeCloseTo(0, 0)

  const box = await sheet.boundingBox()
  expect(box).not.toBeNull()
  expect(box.y).toBeGreaterThanOrEqual(19)
  expect(box.height).toBeLessThanOrEqual(page.viewportSize().height - 19)
  expect(Math.abs((box.y + box.height) - page.viewportSize().height)).toBeLessThanOrEqual(2)
}

async function expectDialogInsideMainView(page, dialog, actionName) {
  await expect(dialog).toBeVisible()
  await expect(dialog).not.toHaveAttribute('data-motion')
  await expect(dialog.getByRole('button', { name: actionName })).toBeVisible()

  const header = page.locator('header')
  const actionBar = page.getByTestId('navigation-mask')
  const [box, headerBox, actionBarBox] = await Promise.all([
    dialog.boundingBox(),
    header.boundingBox(),
    actionBar.boundingBox(),
  ])
  expect(box).not.toBeNull()
  expect(headerBox).not.toBeNull()
  expect(actionBarBox).not.toBeNull()
  expect(box.y).toBeGreaterThanOrEqual(headerBox.y + headerBox.height + 23)
  expect(box.y + box.height).toBeLessThanOrEqual(actionBarBox.y - 23)
}

test.beforeEach(async ({ page }) => {
  await page.goto('/wallets')
  await expect(page.getByRole('heading', { name: 'Wallets', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Everyday account' })).toBeVisible()
})

test('wallet list, movement sheet, and detail stay contained on mobile', async ({ page }) => {
  const cards = page.getByRole('article')
  const everydayCard = cards.filter({ hasText: 'Everyday account' })
  const billsCard = cards.filter({ hasText: 'Bills account' })
  const [everydayBox, billsBox, moveBox] = await Promise.all([
    everydayCard.boundingBox(),
    billsCard.boundingBox(),
    everydayCard.getByRole('button', { name: 'Move money' }).boundingBox(),
  ])

  expect(everydayBox).not.toBeNull()
  expect(billsBox).not.toBeNull()
  expect(moveBox).not.toBeNull()
  expect(billsBox.y).toBeGreaterThan(everydayBox.y + everydayBox.height)
  expect(Math.abs(everydayBox.x - billsBox.x)).toBeLessThanOrEqual(1)
  expect(Math.abs(everydayBox.width - billsBox.width)).toBeLessThanOrEqual(1)
  expect(moveBox.height).toBeGreaterThanOrEqual(44)
  await expectNoHorizontalOverflow(page)

  await everydayCard.getByRole('button', { name: 'Move money' }).click()
  const movement = page.getByRole('dialog', { name: 'Move money' })
  await expectBottomSheetGeometry(page, movement)
  await movement.getByRole('button', { name: 'Close money movement' }).click()
  await expect(movement).not.toBeAttached()

  await page.getByRole('link', { name: 'Open Everyday account' }).click()
  await expect(page.getByRole('heading', { name: 'Everyday account', exact: true })).toBeVisible()
  const edit = page.getByRole('button', { name: 'Edit Everyday account' })
  const editBox = await edit.boundingBox()
  expect(editBox).not.toBeNull()
  expect(editBox.width).toBeGreaterThanOrEqual(44)
  expect(editBox.height).toBeGreaterThanOrEqual(44)
  await expect(page.getByRole('table', { name: 'Wallet transactions' })).toBeVisible()
  await expectNoHorizontalOverflow(page)
})

test('top-up preview, swap, record, and wallet activity stay in one flow', async ({ page }) => {
  const everydayCard = page.getByRole('article').filter({ hasText: 'Everyday account' })
  const billsCard = page.getByRole('article').filter({ hasText: 'Bills account' })
  await expect(everydayCard).toContainText('¥28,400')
  await expect(billsCard).toContainText('−¥120,000')

  await everydayCard.getByRole('button', { name: 'Move money' }).click()
  const movement = page.getByRole('dialog', { name: 'Move money' })
  await expect(movement.getByRole('combobox', { name: 'From' })).toHaveValue('External account')
  await expect(movement.getByRole('combobox', { name: 'To' })).toHaveValue('Everyday account')

  await movement.getByRole('textbox', { name: 'Amount' }).fill('10000')
  await movement.getByRole('textbox', { name: 'Note' }).fill('July salary')
  await expect(movement.getByText('¥28,400 → ¥38,400')).toBeVisible()

  await movement.getByRole('button', { name: 'Swap From and To' }).click()
  await expect(movement.getByRole('combobox', { name: 'From' })).toHaveValue('Everyday account')
  await expect(movement.getByRole('combobox', { name: 'To' })).toHaveValue('External account')
  await expect(movement.getByRole('textbox', { name: 'Amount' })).toHaveValue('10000')
  await expect(movement.getByRole('textbox', { name: 'Note' })).toHaveValue('July salary')

  await movement.getByRole('button', { name: 'Swap From and To' }).click()
  await movement.getByRole('button', { name: 'Review money movement' }).click()
  await expect(movement.getByText('Confirm the movement before recording it.')).toBeVisible()
  await movement.getByRole('button', { name: 'Record money movement' }).click()

  await expect(page.getByRole('status')).toContainText('Money movement recorded')
  await expect(everydayCard).toContainText('¥38,400')

  await page.getByRole('link', { name: 'Open Everyday account' }).click()
  await expect(page.getByRole('heading', { name: 'Everyday account', exact: true })).toBeVisible()
  await expect(page.getByText('From External account').first()).toBeVisible()
  await expect(page.getByText('+¥10,000').first()).toBeVisible()
  await expect(page.getByText('July salary').first()).toBeVisible()
})

test('wallet list, movement dialog, detail, and editor adapt at the medium viewport', async ({ page }) => {
  await page.setViewportSize({ width: 900, height: 800 })
  await page.reload()

  const everydayCard = page.getByRole('article').filter({ hasText: 'Everyday account' })
  const billsCard = page.getByRole('article').filter({ hasText: 'Bills account' })
  const [everydayBox, billsBox] = await Promise.all([
    everydayCard.boundingBox(),
    billsCard.boundingBox(),
  ])
  expect(everydayBox).not.toBeNull()
  expect(billsBox).not.toBeNull()
  expect(Math.abs(everydayBox.y - billsBox.y)).toBeLessThanOrEqual(1)
  expect(billsBox.x).toBeGreaterThan(everydayBox.x + everydayBox.width)
  await expectNoHorizontalOverflow(page)

  await everydayCard.getByRole('button', { name: 'Move money' }).click()
  const movement = page.getByRole('dialog', { name: 'Move money' })
  await expectDialogInsideMainView(page, movement, 'Review money movement')
  await movement.getByRole('button', { name: 'Close money movement' }).click()
  await expect(movement).not.toBeAttached()

  await page.getByRole('link', { name: 'Open Everyday account' }).click()
  await expect(page.getByRole('heading', { name: 'Everyday account', exact: true })).toBeVisible()
  await expect(page.getByRole('table', { name: 'Wallet transactions' })).toBeVisible()
  await expectNoHorizontalOverflow(page)

  await page.getByRole('button', { name: 'Edit Everyday account' }).click()
  const editor = page.getByRole('dialog', { name: 'Edit Everyday account' })
  await expectDialogInsideMainView(page, editor, 'Review wallet')
})

test('creates a zero-balance wallet from the shared page action', async ({ page }) => {
  await page.getByRole('button', { name: 'Open navigation' }).click()
  await page.getByRole('button', { name: 'Add wallet or account' }).click()

  const editor = page.getByRole('dialog', { name: 'New wallet or account' })
  await editor.getByRole('textbox', { name: 'Name' }).fill('Travel wallet')
  await editor.getByRole('textbox', { name: 'Description' }).fill('Trips and holidays')
  await editor.getByRole('button', { name: 'Review wallet' }).click()
  await expect(editor.getByText('¥0 derived from recorded activity')).toBeVisible()
  await editor.getByRole('button', { name: 'Create wallet' }).click()

  await expect(page.getByRole('status')).toContainText('Travel wallet created')
  const travelCard = page.getByRole('article').filter({ hasText: 'Travel wallet' })
  await expect(travelCard).toContainText('¥0')
})
