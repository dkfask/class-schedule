import { expect, type Page } from '@playwright/test'

const LOGIN = process.env.E2E_USERNAME ?? process.env.E2E_EMAIL ?? 'planner'
const PASSWORD = process.env.E2E_PASSWORD ?? 'change-this-in-local-env'

export interface LoginOptions {
  expectedPath?: RegExp
}

/** 通过登录页进入受保护页面；凭据可用 E2E_USERNAME/E2E_EMAIL 与 E2E_PASSWORD 覆盖。 */
export async function login(page: Page, options: LoginOptions = {}): Promise<void> {
  await page.goto('/login')
  await page.getByRole('textbox', { name: '邮箱或用户名' }).fill(LOGIN)
  await page.getByRole('textbox', { name: '密码' }).fill(PASSWORD)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(options.expectedPath ?? /\/overview/, { timeout: 15_000 })
}
