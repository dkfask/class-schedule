import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createRouter, createMemoryHistory } from 'vue-router'
import { clearCsrfToken } from '../api/http'
import { useAuthStore } from '../stores/auth'
import { createPinia, setActivePinia } from 'pinia'
import LoginView from './LoginView.vue'

afterEach(() => { clearCsrfToken(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('LoginView and auth store', () => {
  it('logs in with credentials and stores the authenticated user', async () => {
    setActivePinia(createPinia())
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => ({ headerName: 'X-XSRF-TOKEN', token: 'csrf-token' }) })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ id: 1, username: 'planner', displayName: '排课员', enabled: true, roles: ['PLANNER'] }) })
    vi.stubGlobal('fetch', fetchMock)
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/workspace', component: { template: '<div />' } }] })
    await router.push('/login')
    await router.isReady()
    const wrapper = mount(LoginView, { global: { plugins: [createPinia(), router] } })
    const vm = wrapper.vm as any
    vm.username = 'planner'
    vm.password = 'secret'
    await vm.submit()
    await flushPromises()
    const auth = useAuthStore()
    expect(auth.user?.username).toBe('planner')
    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/auth/csrf', expect.objectContaining({ credentials: 'include' }))
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/auth/login', expect.objectContaining({ credentials: 'include', headers: expect.any(Headers) }))
    expect((fetchMock.mock.calls[1][1]?.headers as Headers).get('X-XSRF-TOKEN')).toBe('csrf-token')
    expect(router.currentRoute.value.path).toBe('/workspace')
    wrapper.unmount()
  })

  it('shows login failures without authenticating', async () => {
    setActivePinia(createPinia())
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => ({ headerName: 'X-XSRF-TOKEN', token: 'csrf-token' }) })
      .mockResolvedValueOnce({ ok: false, status: 401, json: async () => ({ message: '用户名或密码错误' }) }))
    const wrapper = mount(LoginView, { global: { plugins: [createPinia()] } })
    const vm = wrapper.vm as any
    vm.username = 'bad'
    vm.password = 'bad'
    await vm.submit()
    await flushPromises()
    expect(vm.error).toBe('用户名或密码错误')
    expect(useAuthStore().user).toBeNull()
    wrapper.unmount()
  })
})
