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
    const row = page.locator('.version-row, button:has-text("版本 v")').first();
    test.skip((await row.count()) === 0, '本地库无已发布版本');
    await row.click();
    const button = page.getByRole('button', { name: '下载 Excel' });
    await expect(button).toBeEnabled();
    const downloadPromise = context.waitForEvent('download', { timeout: 20_000 });
    await button.click();
    const download = await downloadPromise;
    const path = await download.path();
    expect(path).toBeTruthy();
    expect(readFileSync(path as string).subarray(0, 2).toString('latin1')).toBe('PK');
  });

  test('TC-08-03 PDF 导出真实下载且中文可渲染', async ({ page, context }) => {
    const row = page.locator('.version-row, button:has-text("版本 v")').first();
    test.skip((await row.count()) === 0, '本地库无已发布版本');
    await row.click();
    const button = page.getByRole('button', { name: '下载 PDF' });
    await expect(button).toBeEnabled();
    const downloadPromise = context.waitForEvent('download', { timeout: 20_000 });
    await button.click();
    const download = await downloadPromise;
    const path = await download.path();
    expect(path).toBeTruthy();
    const bytes = readFileSync(path as string);
    // PDF 魔数；体积大于 5KB 说明已嵌入 CJK 子集字体（BUG-3 回归锚点）
    expect(bytes.subarray(0, 4).toString('latin1')).toBe('%PDF');
    expect(bytes.length).toBeGreaterThan(5_000);
  });

  test('TC-08-06 校验报告导出可下载（UI 入口）', async ({ page, context }) => {
    test.skip((await page.locator('.version-row, button:has-text("版本 v")').count()) === 0, '本地库无已发布版本');
    // 观察项 O-1 已修复：已发布页提供校验报告导出按钮，走真实 UI 下载链路
    const row = page.locator('.version-row, button:has-text("版本 v")').first();
    await row.click();
    const xlsxButton = page.getByTestId('validation-export-xlsx');
    await expect(xlsxButton).toBeEnabled();
    const downloadPromise = context.waitForEvent('download', { timeout: 20_000 });
    await xlsxButton.click();
    const download = await downloadPromise;
    const path = await download.path();
    expect(path).toBeTruthy();
    expect(readFileSync(path as string).subarray(0, 2).toString('latin1')).toBe('PK');
    await expect(page.getByTestId('validation-export-pdf')).toBeEnabled();
  });
});
