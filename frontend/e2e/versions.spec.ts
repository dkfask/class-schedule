import { expect, test } from '@playwright/test';
import { login } from './helpers';

// 依赖本地验证库存在已发布/候选版本（v25 已发布、v26+ 候选）；库为空时用例自动跳过
test.describe('版本与发布管理（TC-07）', () => {
  test.beforeEach(async ({ page }) => {
    await login(page);
    await page.goto('/versions');
    await expect(page.getByRole('button', { name: '刷新' })).toBeVisible();
    await page.waitForTimeout(1_500);
  });

  test('TC-07-01 版本列表加载与状态显示', async ({ page }) => {
    const hasVersions = await page.locator('.version-row').count();
    test.skip(hasVersions === 0, '本地库无版本数据');
    await expect(page.locator('.version-row').first()).toContainText(/CANDIDATE|PUBLISHED|DRAFT|ARCHIVED/);
  });

  test('TC-07-02 版本差异与只看变化切换', async ({ page }) => {
    test.skip((await page.locator('.version-row').count()) === 0, '本地库无版本数据');
    const checkbox = page.locator('.diff-tools input[type=checkbox]');
    const before = await checkbox.isChecked();
    await checkbox.click();
    await expect(checkbox).not.toBeChecked({ checked: before });
    await checkbox.click();
    await expect(checkbox).toBeChecked({ checked: before });
  });

  test('TC-07-03 fork：复制已发布版本为新草稿', async ({ page }) => {
    const publishedRow = page.locator('.version-row', { hasText: 'PUBLISHED' }).first();
    test.skip((await publishedRow.count()) === 0, '本地库无已发布版本');
    const parentLabel = await publishedRow.locator('strong').first().innerText(); // 形如 "版本 v83 · r2"
    await publishedRow.click();
    await page.getByRole('button', { name: '复制为新草稿' }).click();
    await page.locator('.el-message-box input').fill(`e2e-fork-${Date.now()}`);
    await page.getByRole('button', { name: '创建草稿' }).click();
    await expect(page.locator('.el-message-box')).toBeHidden({ timeout: 15_000 });
    // 列表按创建时间倒序分页（每页 50 条），新草稿应出现在第一行；不能用 DRAFT 计数断言，
    // 因为新增一条会把最旧的版本挤出第一页。
    const parentId = parentLabel.match(/版本 v(\d+)/)?.[1];
    await expect(page.locator('.version-row').first()).toContainText(`父版本 v${parentId}`, { timeout: 15_000 });
    await expect(page.locator('.version-row').first()).toContainText('DRAFT');
  });

  test('TC-07-04 草稿版本锁定与解锁', async ({ page }) => {
    const draftRow = page.locator('.version-row', { hasText: 'DRAFT' }).first();
    test.skip((await draftRow.count()) === 0, '本地库无草稿版本（可先运行 fork 用例）');
    await draftRow.click();
    await page.getByRole('button', { name: '锁定编辑' }).click();
    await expect(page.getByRole('button', { name: '解锁' })).toBeEnabled({ timeout: 15_000 });
    await page.getByRole('button', { name: '解锁' }).click();
    await expect(page.getByRole('button', { name: '锁定编辑' })).toBeEnabled({ timeout: 15_000 });
  });

  test('TC-07-06 命令历史区块空态可见', async ({ page }) => {
    test.skip((await page.locator('.version-row').count()) === 0, '本地库无版本数据');
    await expect(page.locator('body')).toContainText('命令历史');
  });
});
