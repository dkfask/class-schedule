import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, test } from '@playwright/test';
import { login } from './helpers';

// 对应测试用例测试用例_markmap.md 模块 5（导入与数据集转换）；
// 上传通过 Playwright setInputFiles 走浏览器原生文件通道（IAB 不支持、CDP 等价能力的标准实现）。
const FIXTURES = join(fileURLToPath(new URL('./fixtures', import.meta.url)));
const TEMPLATE_WITH_TEACHER = join(FIXTURES, 'master-data-template-with-teacher.xlsx');
const MINIMAL_MASTER_DATA = join(FIXTURES, 'minimal-master-data.xlsx');
const NOT_A_TEMPLATE = join(FIXTURES, 'not-a-template.xlsx');

async function upload(page: import('@playwright/test').Page, file: string): Promise<void> {
  await page.locator('input[type=file]').setInputFiles(file);
  await expect(page.locator('body')).toContainText('VALIDATED', { timeout: 20_000 });
  await page.getByRole('button', { name: '确认导入' }).click();
  await expect(page.locator('body')).toContainText('导入成功', { timeout: 20_000 });
}

test.describe('导入模块（TC-05）', () => {
  test.beforeEach(async ({ page }) => {
    await login(page);
    await page.goto('/import');
    await expect(page.getByRole('button', { name: '选择 Excel 文件' })).toBeVisible();
  });

  test('TC-05-01 下载导入模板', async ({ page }) => {
    const downloadPromise = page.waitForEvent('download');
    await page.getByRole('button', { name: '下载导入模板' }).click();
    const download = await downloadPromise;
    const path = await download.path();
    expect(path).toBeTruthy();
    // MASTER_DATA 模板是 xlsx（zip 容器），以 PK 魔数校验非空且格式正确
    const head = readFileSync(path as string).subarray(0, 2);
    expect(head.toString('latin1')).toBe('PK');
  });

  test('TC-05-02 上传模板→预览 VALIDATED→确认导入', async ({ page }) => {
    await upload(page, TEMPLATE_WITH_TEACHER);
  });

  test('TC-05-03 仅必需 Sheet 导入不删除已有教室', async ({ page }) => {
    await upload(page, MINIMAL_MASTER_DATA);
    const resp = await page.request.get('/api/master-data/rooms?active=false&page=0&size=50');
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    const codes = body.items.map((item: { code: string }) => item.code);
    expect(codes).toEqual(expect.arrayContaining(['A101', 'A102', 'RAPI', 'RHTTP']));
  });

  test('TC-05-05 非模板文件返回可诊断问题', async ({ page }) => {
    await page.locator('input[type=file]').setInputFiles(NOT_A_TEMPLATE);
    await expect(page.locator('body')).toContainText('INVALID_WORKBOOK', { timeout: 20_000 });
    await expect(page.getByRole('button', { name: '选择 Excel 文件' })).toBeEnabled();
  });

  test('TC-05-07 重复导入按编码幂等', async ({ page }) => {
    for (let round = 0; round < 2; round += 1) {
      await upload(page, TEMPLATE_WITH_TEACHER);
    }
    const resp = await page.request.get('/api/master-data/teachers?active=false&page=0&size=50');
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    const rows = body.items.filter((item: { code: string }) => item.code === 'T-IMP-001');
    expect(rows).toHaveLength(1);
  });
});
