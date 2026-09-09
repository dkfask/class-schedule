import { expect, test } from '@playwright/test'

test.describe('邮箱注册（TC-AUTH）', () => {
  test('邮箱验证码注册并可以登录，直接进入排课工作台', async ({ page }) => {
    test.skip(!process.env.E2E_EMAIL, '设置 E2E_EMAIL 后运行真实邮箱注册流程')
    const email = process.env.E2E_EMAIL as string
    const secret = process.env.E2E_PASSWORD ?? `pw-${Math.random().toString(36).slice(2, 10)}`
    await page.goto('/login')
    const registerLink = page.getByRole('link', { name: '没有账号？注册' })
    test.skip(await registerLink.count() === 0, '当前构建已关闭邮箱注册')
    await registerLink.click()
    await expect(page.getByRole('heading', { name: '注册新账号' })).toBeVisible()
    await page.getByRole('textbox', { name: '邮箱' }).fill(email)
    await page.getByRole('button', { name: '发送验证码' }).click()
    const code = process.env.E2E_VERIFICATION_CODE
    test.skip(!code, '设置 E2E_VERIFICATION_CODE 后运行真实验证码提交流程')
    await page.getByRole('textbox', { name: '邮箱验证码' }).fill(code as string)
    await page.getByRole('textbox', { name: '显示名称' }).fill('E2E 排课员')
    await page.getByRole('textbox', { name: '密码' }).fill(secret)
    await page.getByRole('textbox', { name: '确认密码' }).fill(secret)
    await page.getByRole('button', { name: '注册' }).click()
    await expect(page.locator('body')).toContainText('注册成功，请使用邮箱登录')
    await page.getByRole('textbox', { name: '邮箱或用户名' }).fill(email)
    await page.getByRole('textbox', { name: '密码' }).fill(secret)
    await page.getByRole('button', { name: '登录' }).click()
    await expect(page).toHaveURL(/\/overview/, { timeout: 15_000 })
    await expect(page.locator('body')).toContainText('排课员')
  })

  test('密码确认不一致时不提交注册请求', async ({ page }) => {
    await page.goto('/login')
    const registerLink = page.getByRole('link', { name: '没有账号？注册' })
    test.skip(await registerLink.count() === 0, '当前构建已关闭邮箱注册')
    await registerLink.click()
    await page.getByRole('textbox', { name: '邮箱' }).fill(`e2e-${Date.now()}@example.com`)
    await page.getByRole('textbox', { name: '密码' }).fill('password-123')
    await page.getByRole('textbox', { name: '确认密码' }).fill('password-456')
    await page.getByRole('textbox', { name: '邮箱验证码' }).fill('123456')
    await page.getByRole('button', { name: '注册' }).click()
    await expect(page.locator('body')).toContainText('两次输入的密码不一致')
  })
})
