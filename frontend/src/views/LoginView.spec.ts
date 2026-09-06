import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createRouter, createMemoryHistory, routerKey } from 'vue-router'
import { clearCsrfToken } from '../api/http'
import { useAuthStore } from '../stores/auth'
import { createPinia, setActivePinia } from 'pinia'
import LoginView from './LoginView.vue'

afterEach(() => { clearCsrfToken(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

function createLoginRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', component: { template: '<div />' } },
      { path: '/workspace', component: { template: '<div />' } },
      { path: '/published', component: { template: '<div />' } },
    ],
  })
}

describe('LoginView and auth store', () => {
  it('logs in with credentials and stores the authenticated user', async () => {
    setActivePinia(createPinia())
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => ({ headerName: 'X-XSRF-TOKEN', token: 'csrf-token' }) })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ id: 1, username: 'planner', displayName: '排课员', enabled: true, roles: ['PLANNER'] }) })
    vi.stubGlobal('fetch', fetchMock)
    const router = createLoginRouter()
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
    const wrapper = mount(LoginView, { global: { plugins: [createPinia()], provide: { [routerKey]: { push: vi.fn() } } } })
    const vm = wrapper.vm as any
    vm.username = 'bad'
    vm.password = 'bad'
    await vm.submit()
    await flushPromises()
    expect(vm.error).toBe('用户名或密码错误')
    expect(useAuthStore().user).toBeNull()
    wrapper.unmount()
  })

  it('registers a new account and returns to the login mode', async () => {
    setActivePinia(createPinia())
    const registerSecret = `pw-${Math.random().toString(36).slice(2, 10)}`
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => ({ headerName: 'X-XSRF-TOKEN', token: 'csrf-token' }) })
      .mockResolvedValueOnce({ ok: true, status: 201, json: async () => ({ id: 9, username: 'new-user', displayName: '新用户', enabled: true, roles: ['VIEWER'] }) })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(LoginView, { global: { plugins: [createPinia()], provide: { [routerKey]: { push: vi.fn() } } } })
    const vm = wrapper.vm as any
    vm.mode = 'register'
    vm.username = 'new-user'
    vm.password = registerSecret
    vm.displayName = '新用户'
    await vm.submitRegister()
    await flushPromises()
    expect(vm.mode).toBe('login')
    expect(vm.notice).toContain('注册成功')
    expect(vm.error).toBe('')
    const registerCall = fetchMock.mock.calls.find(([url]) => String(url).includes('/api/auth/register'))
    expect(registerCall).toBeTruthy()
    expect(JSON.parse((registerCall![1] as RequestInit).body as string).password).toBe(registerSecret)
    wrapper.unmount()
  })

  it('surfaces registration rejections from the backend', async () => {
    setActivePinia(createPinia())
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => ({ headerName: 'X-XSRF-TOKEN', token: 'csrf-token' }) })
      .mockResolvedValueOnce({ ok: false, status: 409, json: async () => ({ code: 'USERNAME_EXISTS', message: '用户名已被占用' }) }))
    const wrapper = mount(LoginView, { global: { plugins: [createPinia()], provide: { [routerKey]: { push: vi.fn() } } } })
    const vm = wrapper.vm as any
    vm.mode = 'register'
    vm.username = 'taken'
    vm.password = 'x'.repeat(12)
    await vm.submitRegister()
    await flushPromises()
    expect(vm.mode).toBe('register')
    expect(vm.error).toBe('用户名已被占用')
    wrapper.unmount()
  })
})
