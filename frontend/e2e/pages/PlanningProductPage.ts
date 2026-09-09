import { expect, type Page } from '@playwright/test'
import { login } from '../helpers'

export class PlanningProductPage {
  constructor(readonly page: Page) {}

  async signIn() {
    await login(this.page)
  }

  async visit(path: string, heading: string) {
    await this.page.goto(path)
    await expect(this.page.getByRole('heading', { name: heading, exact: true })).toBeVisible()
  }

  async assertP1Routes() {
    const routes = [
      ['/overview', '学期总览', null],
      ['/notifications', '通知与待办', 'notifications-page'],
      ['/audit', '操作与审计', 'audit-page'],
      ['/problems', '问题与反馈中心', 'problems-page'],
      ['/rule-templates', '规则模板与交付方案', 'rule-templates-page'],
      ['/retrospective', '学期复盘', 'retrospective-page'],
      ['/term-reuse', '学期复用', 'term-reuse-page'],
    ] as const

    for (const [path, heading, testId] of routes) {
      await this.visit(path, heading)
      if (testId) await expect(this.page.getByTestId(testId)).toBeVisible()
      await expect(this.page.locator('body')).not.toContainText(/加载失败|无法读取|请求失败/)
    }
  }

  async createAndResolveProblem(title: string) {
    await this.visit('/problems', '问题与反馈中心')
    await this.page.getByLabel('标题').fill(title)
    await this.page.getByLabel('问题描述').fill('闭环测试验证问题创建、关联当前学期和关闭结论。')
    await this.page.getByLabel('证据或验证依据').fill('E2E runtime verification')
    await Promise.all([
      this.page.waitForResponse(response => response.url().includes('/api/problems') && response.request().method() === 'POST' && response.status() === 201),
      this.page.getByRole('button', { name: '创建问题', exact: true }).click(),
    ])

    const card = this.page.locator('.problem-card').filter({ hasText: title })
    await expect(card).toBeVisible()
    await card.locator('.resolution-field input').fill('已验证，关闭 E2E 闭环问题。')
    await card.locator('select').selectOption('RESOLVED')
    await Promise.all([
      this.page.waitForResponse(response => /\/api\/problems\/\d+$/.test(response.url()) && response.request().method() === 'PATCH' && response.status() === 200),
      card.getByRole('button', { name: '保存', exact: true }).click(),
    ])
    await expect(card.locator('select')).toHaveValue('RESOLVED')
  }

  async previewTermReuse(targetCode: string) {
    await this.visit('/term-reuse', '学期复用')
    const sourceCode = await this.page.getByLabel('来源学期').inputValue()
    await this.page.getByLabel('目标学期编码').fill(targetCode)
    await this.page.getByLabel('目标学期名称').fill('闭环测试目标学期')
    await this.page.getByLabel('开始日期').fill('2027-01-01')
    await this.page.getByLabel('结束日期').fill('2027-06-30')
    await Promise.all([
      this.page.waitForResponse(response => response.url().endsWith('/api/terms/copy/preview') && response.request().method() === 'POST' && response.status() === 200),
      this.page.getByRole('button', { name: '生成复制预览', exact: true }).click(),
    ])
    await expect(this.page.locator('.preview-content')).toBeVisible()
    await expect(this.page.locator('.preview-panel')).toContainText(`${sourceCode} → ${targetCode}`)
    await expect(this.page.locator('.preview-panel')).toContainText('将复制')
    await expect(this.page.locator('.preview-panel')).toContainText('不会复制')
  }

  async saveAndRestoreRetrospective(note: string) {
    await this.visit('/retrospective', '学期复盘')
    await expect(this.page.getByText('产品与流程', { exact: true })).toBeVisible()
    const feedback = this.page.getByLabel('学校反馈')
    const previous = await feedback.inputValue()
    await feedback.fill(note)
    await expect(feedback).toHaveValue(note)
    await Promise.all([
      this.page.waitForResponse(response => response.url().endsWith('/api/retrospectives') && response.request().method() === 'PATCH' && response.status() === 200),
      this.page.getByRole('button', { name: '保存复盘', exact: true }).click(),
    ])
    await expect(this.page.getByText('复盘记录已保存')).toBeVisible()
    await this.page.reload()
    await expect(this.page.getByRole('heading', { name: '学期复盘', exact: true })).toBeVisible()
    await expect(feedback).toHaveValue(note)

    await feedback.fill(previous)
    await Promise.all([
      this.page.waitForResponse(response => response.url().endsWith('/api/retrospectives') && response.request().method() === 'PATCH' && response.status() === 200),
      this.page.getByRole('button', { name: '保存复盘', exact: true }).click(),
    ])
  }
}
