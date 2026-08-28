import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from './stores/auth'
import WorkspaceView from './views/WorkspaceView.vue'
import MasterDataView from './views/MasterDataView.vue'
import ImportView from './views/ImportView.vue'
import RuleFactsView from './views/RuleFactsView.vue'
import TeachingPlanView from './views/TeachingPlanView.vue'
import ScheduleVersionsView from './views/ScheduleVersionsView.vue'
import LoginView from './views/LoginView.vue'
import PublishedView from './views/PublishedView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView, meta: { public: true } },
    { path: '/', redirect: '/workspace' },
    { path: '/workspace', component: WorkspaceView, meta: { requiresAuth: true, planner: true } },
    { path: '/master-data', component: MasterDataView, meta: { requiresAuth: true, planner: true } },
    { path: '/teaching-plan', component: TeachingPlanView, meta: { requiresAuth: true, planner: true } },
    { path: '/rule-facts', component: RuleFactsView, meta: { requiresAuth: true, planner: true } },
    { path: '/import', component: ImportView, meta: { requiresAuth: true, planner: true } },
    { path: '/versions', component: ScheduleVersionsView, meta: { requiresAuth: true, planner: true } },
    { path: '/published', component: PublishedView, meta: { requiresAuth: true } },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.initialized) await auth.loadMe()
  if (to.meta.public) {
    if (to.path === '/login' && auth.isAuthenticated) return '/workspace'
    return true
  }
  if (to.meta.requiresAuth && !auth.isAuthenticated) return { path: '/login', query: { redirect: to.fullPath } }
  if (to.meta.planner && !auth.isPlanner) return '/published'
  return true
})

export default router
