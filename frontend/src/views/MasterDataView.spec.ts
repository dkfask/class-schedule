import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { routerKey } from 'vue-router'
import MasterDataView from './MasterDataView.vue'

function response(body: unknown, ok = true) {
  return { ok, json: async () => body }
}

function mountMasterData() {
  return mount(MasterDataView, {
    global: {
      provide: { [routerKey]: { push: vi.fn() } },
      directives: { loading: () => undefined },
      stubs: {
        'el-button': { template: '<button @click="$emit(\'click\')"><slot /></button>' },
        'el-table': { template: '<div><slot /></div>' },
        'el-table-column': { template: '<div />' },
        'el-pagination': { template: '<div />' },
        'el-empty': { template: '<div />' },
        'el-dialog': { template: '<div><slot /></div>' },
        'el-form': { template: '<form><slot /></form>' },
        'el-form-item': { template: '<label><slot /></label>' },
        'el-input': { template: '<input />' },
        'el-input-number': { template: '<input />' },
        'el-tag': { template: '<span><slot /></span>' },
      },
    },
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('MasterDataView pagination and errors', () => {
  it('requests page and size and stores total metadata', async () => {
    const calls: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      calls.push(String(input))
      return response({ items: [{ id: 1, code: 'T001', name: '张老师', active: true, attributes: {} }], page: 0, size: 20, total: 41 })
    }))
    const wrapper = mountMasterData()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(calls[0]).toContain('/api/master-data/teachers?active=false&page=0&size=20')
    expect(vm.total).toBe(41)
    expect(vm.items).toHaveLength(1)
    wrapper.unmount()
  })

  it('surfaces loading errors instead of showing a silent empty list', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response({ message: '服务不可用' }, false)))
    const wrapper = mountMasterData()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.errorMessage).toBe('服务不可用')
    expect(vm.items).toEqual([])
    wrapper.unmount()
  })
})
