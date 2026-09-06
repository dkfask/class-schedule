import { expect, test } from '@playwright/test';

// 注册流程：公开注册默认 VIEWER 只读角色（排课员由管理员分配）
test.describe('账号注册（TC-AUTH）', () => {
  test('注册新账号并可以登录，侧边栏不显示排课员功能', async ({ page }) => {
    const username = `e2e-user-${Date.now()}`;
    await page.goto('/login');
    await page.getByRole('link', { name: '没有账号？注册' }).click();
    await expect(page.getByRole('heading', { name: '注册新账号' })).toBeVisible();
    await page.getByRole('textbox', { name: '用户名' }).fill(username);
    await page.getByRole('textbox', { name: '显示名称' }).fill('E2E 只读用户');
    const secret = `pw-${Math.random().toString(36).slice(2, 10)}`;
    await page.getByRole('textbox', { name: '密码' }).fill(secret);
    await page.getByRole('button', { name: '注册' }).click();
    await expect(page.locator('body')).toContainText('注册成功，请使用新账号登录');
    // 用新账号登录（VIEWER 用户默认导航至已发布课表页）
    await page.getByRole('textbox', { name: '密码' }).fill(secret);
    await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/\/published/, { timeout: 15_000 });
    await expect(page.locator('body')).toContainText('只读');
    // VIEWER 侧边栏没有排课员专属入口
    await expect(page.getByRole('link', { name: /基础数据/ })).toHaveCount(0);
    await expect(page.getByRole('link', { name: /数据导入/ })).toHaveCount(0);
  });

  test('重复用户名返回可诊断错误', async ({ page }) => {
    const username = `e2e-dup-${Date.now()}`;
    for (let round = 0; round < 2; round += 1) {
      await page.goto('/login');
      await page.getByRole('link', { name: '没有账号？注册' }).click();
      await page.getByRole('textbox', { name: '用户名' }).fill(username);
      await page.getByRole('textbox', { name: '密码' }).fill(`pw-${Math.random().toString(36).slice(2, 10)}`);
      await page.getByRole('button', { name: '注册' }).click();
      if (round === 0) {
        await expect(page.locator('body')).toContainText('注册成功，请使用新账号登录', { timeout: 15_000 });
      } else {
        await expect(page.locator('body')).toContainText('用户名已被占用', { timeout: 15_000 });
      }
    }
  });
});
