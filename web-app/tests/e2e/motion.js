import { expect } from '@playwright/test'

export async function holdAndSampleTactileMotion(page, control) {
  await expect(control).toBeVisible()
  const box = await control.boundingBox()
  expect(box).not.toBeNull()
  await page.mouse.move(box.x + (box.width / 2), box.y + (box.height / 2))
  await page.mouse.down()
  try {
    await page.waitForTimeout(120)
    return await control.evaluate((element) => {
      const transform = getComputedStyle(element).transform
      const matrix = transform === 'none'
        ? new DOMMatrixReadOnly()
        : new DOMMatrixReadOnly(transform)
      const ripple = element.querySelector('.MuiTouchRipple-root')
      return {
        rippleDisplay: ripple ? getComputedStyle(ripple).display : null,
        scale: matrix.a,
        y: matrix.m42,
      }
    })
  } finally {
    await page.mouse.move(0, 0)
    await page.mouse.up()
  }
}
