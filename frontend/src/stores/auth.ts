import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { clearCsrfToken, http, jsonRequest } from '../api/http'

export interface AuthUser {
  id: number
  username: string
  displayName: string
  enabled: boolean
  roles: string[]
}

export const useAuthStore = defineStore('auth', () => {
  const user = ref<AuthUser | null>(null)
  const loading = ref(false)
  const initialized = ref(false)
  const isAuthenticated = computed(() => user.value !== null)
  const isPlanner = computed(() => user.value?.roles.includes('PLANNER') ?? false)
  const isViewer = computed(() => user.value?.roles.includes('VIEWER') ?? false)

  async function loadMe() {
    loading.value = true
    try {
      user.value = await http<AuthUser>('/api/auth/me')
    } catch {
      user.value = null
    } finally {
      initialized.value = true
      loading.value = false
    }
  }

  async function login(username: string, password: string) {
    loading.value = true
    try {
      user.value = await http<AuthUser>('/api/auth/login', jsonRequest('POST', { username, password }))
      initialized.value = true
    } finally {
      loading.value = false
    }
  }

  async function logout() {
    try {
      await http<void>('/api/auth/logout', { method: 'POST' })
    } finally {
      clearCsrfToken()
      user.value = null
      initialized.value = true
    }
  }

  return { user, loading, initialized, isAuthenticated, isPlanner, isViewer, loadMe, login, logout }
})
