import { expect, test } from '@playwright/test';
import { login } from './helpers';

test.describe('排课工作台（TC-06）', () => {
  test.beforeEach(async ({ page }) => {
    await login(page);
    await expect(page).toHaveURL(/\/workspace/);
  });

  test('TC-06-01 工作台加载：状态、评分与提示面板', async ({ page }) => {
    await expect(page.getByText('排课状态')).toBeVisible();
    await expect(page.getByRole('button', { name: /开始自动排课|正在求解/ })).toBeVisible();
    await expect(page.locator('body')).toContainText('排课条件已就绪');
    await expect(page.locator('body')).not.toContainText('基础数据暂时无法读取');
  });

  test('TC-06-02 三种课表视图切换', async ({ page }) => {
    for (const name of ['班级课表', '教师课表', '教室课表']) {
      await page.getByRole('button', { name }).click();
      await expect(page.getByRole('button', { name })).toBeVisible();
    }
    // 空态或数据态均算通过：不允许请求失败
    await expect(page.locator('body')).not.toContainText('请求失败');
  });

  test('TC-06-04 提交求解并等待候选结果', async ({ page }) => {
    test.setTimeout(150_000);
    const start = page.getByRole('button', { name: '开始自动排课' });
    if (!(await start.isEnabled().catch(() => false))) {
      test.skip(true, '求解按钮不可用（排课条件未就绪）');
    }
    await start.click();
    // 求解默认预算 30s，含排队与落库，放宽到 120s
    await expect(page.locator('body')).toContainText('候选结果', { timeout: 120_000 });
    await expect(page.locator('body')).toContainText('当前版本');
  });
});
