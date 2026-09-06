import { expect, type Page } from '@playwright/test';

const USERNAME = process.env.E2E_USERNAME ?? 'planner';
const PASSWORD = process.env.E2E_PASSWORD ?? 'change-this-in-local-env';

/** 通过登录页进入工作台；凭据可用 E2E_USERNAME / E2E_PASSWORD 覆盖 */
export async function login(page: Page): Promise<void> {
  await page.goto('/login');
  await page.getByRole('textbox', { name: '用户名' }).fill(USERNAME);
  await page.getByRole('textbox', { name: '密码' }).fill(PASSWORD);
  await page.getByRole('button', { name: '登录' }).click();
  await expect(page).toHaveURL(/\/workspace/, { timeout: 15_000 });
}
