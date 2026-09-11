import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from './stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('./views/LoginView.vue'), meta: { public: true } },
    { path: '/', redirect: '/overview' },
    { path: '/overview', component: () => import('./views/OverviewView.vue'), meta: { requiresAuth: true, reviewer: true } },
    { path: '/workspace', component: () => import('./views/WorkspaceView.vue'), meta: { requiresAuth: true, planner: true } },
    { path: '/master-data', component: () => import('./views/MasterDataView.vue'), meta: { requiresAuth: true, planner: true } },
    { path: '/teaching-plan', component: () => import('./views/TeachingPlanView.vue'), meta: { requiresAuth: true, planner: true } },
    { path: '/rule-facts', component: () => import('./views/RuleFactsView.vue'), meta: { requiresAuth: true, planner: true } },
    { path: '/import', component: () => import('./views/ImportView.vue'), meta: { requiresAuth: true, planner: true } },
    { path: '/versions', component: () => import('./views/ScheduleVersionsView.vue'), meta: { requiresAuth: true, reviewer: true } },
    { path: '/published', component: () => import('./views/PublishedView.vue'), meta: { requiresAuth: true } },
    { path: '/audit', component: () => import('./views/AuditView.vue'), meta: { requiresAuth: true, audit: true } },
    { path: '/problems', component: () => import('./views/ProblemsView.vue'), meta: { requiresAuth: true, audit: true } },
    { path: '/notifications', component: () => import('./views/NotificationsView.vue'), meta: { requiresAuth: true } },
    { path: '/rule-templates', component: () => import('./views/RuleTemplatesView.vue'), meta: { requiresAuth: true, planner: true } },
    { path: '/retrospective', component: () => import('./views/RetrospectiveView.vue'), meta: { requiresAuth: true, planner: true } },
    { path: '/term-reuse', component: () => import('./views/TermReuseView.vue'), meta: { requiresAuth: true, planner: true } },
    { path: '/settings/ai', component: () => import('./views/AiSettingsView.vue'), meta: { requiresAuth: true, admin: true } },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.initialized) await auth.loadMe()
  if (to.meta.public) {
    if (to.path === '/login' && auth.isAuthenticated) return auth.canReview ? '/overview' : '/published'
    return true
  }
  if (to.meta.requiresAuth && !auth.isAuthenticated) return { path: '/login', query: { redirect: to.fullPath } }
  if (to.meta.planner && !auth.isPlanner) return '/published'
  if (to.meta.reviewer && !auth.canReview) return '/published'
  if (to.meta.audit && !auth.canReadAudit) return '/published'
  if (to.meta.admin && !auth.canManageAi) return '/published'
  return true
})

export default router
