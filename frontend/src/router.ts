import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from './stores/auth'
import WorkspaceView from './views/WorkspaceView.vue'
import OverviewView from './views/OverviewView.vue'
import MasterDataView from './views/MasterDataView.vue'
import ImportView from './views/ImportView.vue'
import RuleFactsView from './views/RuleFactsView.vue'
import TeachingPlanView from './views/TeachingPlanView.vue'
import ScheduleVersionsView from './views/ScheduleVersionsView.vue'
import LoginView from './views/LoginView.vue'
import PublishedView from './views/PublishedView.vue'
import AuditView from './views/AuditView.vue'
import ProblemsView from './views/ProblemsView.vue'
import NotificationsView from './views/NotificationsView.vue'
import RuleTemplatesView from './views/RuleTemplatesView.vue'
import RetrospectiveView from './views/RetrospectiveView.vue'
import TermReuseView from './views/TermReuseView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView, meta: { public: true } },
    { path: '/', redirect: '/overview' },
    { path: '/overview', component: OverviewView, meta: { requiresAuth: true, reviewer: true } },
    { path: '/workspace', component: WorkspaceView, meta: { requiresAuth: true, planner: true } },
    { path: '/master-data', component: MasterDataView, meta: { requiresAuth: true, planner: true } },
    { path: '/teaching-plan', component: TeachingPlanView, meta: { requiresAuth: true, planner: true } },
    { path: '/rule-facts', component: RuleFactsView, meta: { requiresAuth: true, planner: true } },
    { path: '/import', component: ImportView, meta: { requiresAuth: true, planner: true } },
    { path: '/versions', component: ScheduleVersionsView, meta: { requiresAuth: true, reviewer: true } },
    { path: '/published', component: PublishedView, meta: { requiresAuth: true } },
    { path: '/audit', component: AuditView, meta: { requiresAuth: true, audit: true } },
    { path: '/problems', component: ProblemsView, meta: { requiresAuth: true, audit: true } },
    { path: '/notifications', component: NotificationsView, meta: { requiresAuth: true } },
    { path: '/rule-templates', component: RuleTemplatesView, meta: { requiresAuth: true, planner: true } },
    { path: '/retrospective', component: RetrospectiveView, meta: { requiresAuth: true, planner: true } },
    { path: '/term-reuse', component: TermReuseView, meta: { requiresAuth: true, planner: true } },
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
  return true
})

export default router
