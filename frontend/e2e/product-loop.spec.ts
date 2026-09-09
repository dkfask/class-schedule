import { test } from '@playwright/test'
import { PlanningProductPage } from './pages/PlanningProductPage'

test.describe('产品 P1 闭环（通知、追踪、复用与复盘）', () => {
  test('P1 页面路由与当前学期数据加载', async ({ page }) => {
    const app = new PlanningProductPage(page)
    await app.signIn()
    await app.assertP1Routes()
  })

  test('P1 问题记录从创建到解决', async ({ page }) => {
    const app = new PlanningProductPage(page)
    await app.signIn()
    await app.createAndResolveProblem(`闭环测试问题 ${Date.now()}`)
  })

  test('P1 学期复用先预览复制范围', async ({ page }) => {
    const app = new PlanningProductPage(page)
    await app.signIn()
    await app.previewTermReuse(`E2E-REUSE-${Date.now()}`)
  })

  test('P1 复盘记录写入后刷新读回', async ({ page }) => {
    const app = new PlanningProductPage(page)
    await app.signIn()
    await app.saveAndRestoreRetrospective(`闭环测试临时记录 ${Date.now()}`)
  })
})
