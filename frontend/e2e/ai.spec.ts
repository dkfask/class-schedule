import { expect, test } from '@playwright/test';
import { login } from './helpers';

test.describe('AI 辅助（诊断 + 助手）', () => {
  test('TC-AI-01 候选方案智能诊断面板', async ({ page }) => {
    await login(page);
    const start = page.getByRole('button', { name: '开始自动排课' });
    await expect(start).toBeVisible();
    if (await start.isEnabled()) {
      test.setTimeout(180_000);
      await start.click();
      await expect(page.locator('body')).toContainText('候选结果', { timeout: 120_000 });
    } else {
      test.setTimeout(60_000);
      await page.waitForTimeout(2_000);
    }
    const aiButton = page.getByRole('button', { name: 'AI 诊断' });
    if (!(await aiButton.isVisible().catch(() => false))) {
      test.skip(true, '工作台无候选版本，诊断不可用');
    }
    await aiButton.click();
    await expect(page.locator('.ai-finding').first()).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('.ai-suggestions')).toBeVisible();
  });

  test('TC-AI-02 排课助手抽屉（未配置 Key 时显示提示）', async ({ page }) => {
    await login(page);
    await page.locator('.ai-fab').click();
    await expect(page.getByRole('dialog', { name: '排课助手' })).toBeVisible();
    // 未配置 APP_AI_* 时显示部署提示；已配置时显示输入框
    await expect(page.locator('.ai-chat-hint').or(page.locator('.ai-chat-input'))).toBeVisible({ timeout: 10_000 });
  });
});
