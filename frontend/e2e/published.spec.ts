import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';
import { login } from './helpers';

// 依赖本地验证库存在已发布版本（v25）；库为空时用例自动跳过
test.describe('已发布课表与导出（TC-08）', () => {
  test.beforeEach(async ({ page }) => {
    await login(page);
    await page.goto('/published');
    await page.waitForTimeout(1_500);
  });

  test('TC-08-01 已发布页加载与只读标识', async ({ page }) => {
    const hasVersions = await page.locator('.version-row, button:has-text("版本 v")').count();
    test.skip(hasVersions === 0, '本地库无已发布版本');
    await expect(page.locator('body')).toContainText('只读');
    await expect(page.getByRole('button', { name: '下载 Excel' })).toBeVisible();
    await expect(page.getByRole('button', { name: '下载 PDF' })).toBeVisible();
  });

  test('TC-08-02 Excel 导出真实下载', async ({ page, context }) => {
    test.skip((await page.locator('button:has-text("下载 Excel")').count()) === 0, '本地库无已发布版本');
    const downloadPromise = context.waitForEvent('download', { timeout: 20_000 });
    await page.getByRole('button', { name: '下载 Excel' }).click();
    const download = await downloadPromise;
    const path = await download.path();
    expect(path).toBeTruthy();
    expect(readFileSync(path as string).subarray(0, 2).toString('latin1')).toBe('PK');
  });

  test('TC-08-03 PDF 导出真实下载且中文可渲染', async ({ page, context }) => {
    test.skip((await page.locator('button:has-text("下载 PDF")').count()) === 0, '本地库无已发布版本');
    const downloadPromise = context.waitForEvent('download', { timeout: 20_000 });
    await page.getByRole('button', { name: '下载 PDF' }).click();
    const download = await downloadPromise;
    const path = await download.path();
    expect(path).toBeTruthy();
    const bytes = readFileSync(path as string);
    // PDF 魔数；体积大于 5KB 说明已嵌入 CJK 子集字体（BUG-3 回归锚点）
    expect(bytes.subarray(0, 4).toString('latin1')).toBe('%PDF');
    expect(bytes.length).toBeGreaterThan(5_000);
  });

  test('TC-08-06 校验报告导出可下载', async ({ page, context }) => {
    test.skip((await page.locator('.version-row, button:has-text("版本 v")').count()) === 0, '本地库无已发布版本');
    // 当前 UI 未提供校验报告入口，走同版本 API 断言导出链路（观察项 O-1 残留）
    const resp = await context.request.get('/api/schedule-versions/25/validation/export.xlsx');
    if (resp.status() === 404) test.skip(true, '版本 25 不存在');
    expect(resp.status()).toBe(200);
  });
});
