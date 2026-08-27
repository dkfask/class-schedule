import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import PublishedView from './PublishedView.vue'

function response(body: unknown, ok = true) { return { ok, json: async () => body } }

function mountView() {
  return mount(PublishedView, {
    global: {
      stubs: {
        'el-button': { template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>', props: ['disabled', 'loading'] },
        'el-empty': { template: '<div><slot /></div>' },
      },
    },
  })
}

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('PublishedView', () => {
  it('renders legacy and modern scores from the raw score fallback', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/schedule-versions')) return response({ items: [
        { id: 24, status: 'PUBLISHED', score: '0hard/0soft', revision: 1 },
        { id: 25, status: 'PUBLISHED', score: '-1hard/-2medium/-3soft', revision: 2 },
      ] })
      return response({})
    }))
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('H0 / M0 / S0')
    expect(wrapper.text()).toContain('H-1 / M-2 / S-3')
    wrapper.unmount()
  })

  it('opens the selected version export and print URLs', async () => {
    const open = vi.fn()
    vi.stubGlobal('open', open)
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).includes('/api/schedule-versions')) return response({ items: [{ id: 24, status: 'PUBLISHED', score: '0hard/0soft' }] })
      return response({})
    }))
    const wrapper = mountView()
    await flushPromises()
    const vm = wrapper.vm as any
    vm.download('xlsx')
    vm.download('pdf')
    vm.print()
    expect(open).toHaveBeenNthCalledWith(1, '/api/schedule-versions/24/exports/xlsx?view=CLASS', '_blank')
    expect(open).toHaveBeenNthCalledWith(2, '/api/schedule-versions/24/exports/pdf?view=CLASS', '_blank')
    expect(open).toHaveBeenNthCalledWith(3, '/api/schedule-versions/24/print?view=CLASS', '_blank')
    wrapper.unmount()
  })
})
