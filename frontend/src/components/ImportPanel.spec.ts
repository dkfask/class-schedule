import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ImportPanel from './ImportPanel.vue'
import { clearCsrfToken } from '../api/http'
import { resetTermStore } from '../stores/term'

function response(body: unknown, ok = true) {
  return { ok, json: async () => body, blob: async () => new Blob(['xlsx']) }
}

function mountPanel() {
  return mount(ImportPanel, {
    global: {
      stubs: {
        'el-button': {
          props: ['disabled', 'loading'],
          template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
        },
        'el-tag': { template: '<span><slot /></span>' },
      },
    },
  })
}

afterEach(() => {
  resetTermStore()
  window.localStorage.clear()
  clearCsrfToken()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('ImportPanel', () => {
  it('downloads the stable master-data template', async () => {
    const createObjectURL = vi.fn(() => 'blob:template')
    const revokeObjectURL = vi.fn()
    const urlApi = globalThis.URL as typeof URL & {
      createObjectURL?: (object: Blob | MediaSource) => string
      revokeObjectURL?: (url: string) => void
    }
    const originalCreateObjectURL = urlApi.createObjectURL
    const originalRevokeObjectURL = urlApi.revokeObjectURL
    Object.defineProperty(urlApi, 'createObjectURL', { configurable: true, writable: true, value: createObjectURL })
    Object.defineProperty(urlApi, 'revokeObjectURL', { configurable: true, writable: true, value: revokeObjectURL })

    let anchor: HTMLAnchorElement | null = null
    const originalCreateElement = document.createElement.bind(document)
    vi.spyOn(document, 'createElement').mockImplementation(((tagName: string, options?: ElementCreationOptions) => {
      const element = originalCreateElement(tagName, options)
      if (tagName.toLowerCase() === 'a') anchor = element as HTMLAnchorElement
      return element
    }) as typeof document.createElement)
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/terms') {
        return response([{ code: '2026-FALL', name: '2026 秋季学期', status: 'ACTIVE' }])
      }
      expect(String(input)).toBe('/api/imports/templates/master-data.xlsx')
      return response(null)
    }))

    try {
      const wrapper = mountPanel()
      await flushPromises()
      const vm = wrapper.vm as any
      await vm.downloadTemplate()

      expect(createObjectURL).toHaveBeenCalledOnce()
      expect(anchor?.download).toBe('master-data-v1.xlsx')
      expect(anchor?.href).toBe('blob:template')
      expect(revokeObjectURL).toHaveBeenCalledWith('blob:template')
    expect(vm.message).toBe('模板下载已开始')
    expect(wrapper.text()).toContain('必填：教师、班级、课程、教学需求')
    expect(wrapper.text()).toContain('可选 Sheet 可以省略或留空')
    wrapper.unmount()
    } finally {
      if (originalCreateObjectURL) Object.defineProperty(urlApi, 'createObjectURL', { configurable: true, writable: true, value: originalCreateObjectURL })
      else delete urlApi.createObjectURL
      if (originalRevokeObjectURL) Object.defineProperty(urlApi, 'revokeObjectURL', { configurable: true, writable: true, value: originalRevokeObjectURL })
      else delete urlApi.revokeObjectURL
    }
  })

  it('clears a previous preview and renders all validation issues', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/api/terms') return response([{ code: '2026-FALL', name: '2026 秋季学期', status: 'ACTIVE' }])
      if (String(input) === '/api/auth/csrf') return response({ headerName: 'X-XSRF-TOKEN', token: 'csrf-token' })
      expect(String(input)).toBe('/api/imports/preview')
      expect(init?.method).toBe('POST')
      return response({
        batchId: 7,
        status: 'INVALID',
        sheets: ['说明', '教师'],
        issues: [
          { sheet: '教师', row: 2, column: 'A', code: 'REQUIRED', message: '编码不能为空' },
          { sheet: '教师', row: 3, column: 'C', code: 'BOOLEAN', message: '布尔值无效' },
        ],
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mountPanel()
    await flushPromises()
    const vm = wrapper.vm as any
    await vm.previewFile(new File(['data'], 'master-data.xlsx'))

    expect(vm.preview.batchId).toBe(7)
    expect(vm.preview.issues).toHaveLength(2)
    expect(wrapper.find('[data-testid="import-issues"]').text()).toContain('BOOLEAN')
    expect(wrapper.text()).toContain('编码不能为空')
    expect(vm.message).toContain('发现 2 个数据问题')
    wrapper.unmount()
  })

  it('confirms a validated batch and clears the pending preview', async () => {
    const calls: Array<{ url: string; init?: RequestInit }> = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      calls.push({ url, init })
      if (url === '/api/terms') return response([{ code: '2026-FALL', name: '2026 秋季学期', status: 'ACTIVE' }])
      if (url === '/api/imports/preview') return response({ batchId: 8, status: 'VALIDATED', sheets: ['说明'], issues: [] })
      if (url === '/api/auth/csrf') return response({ headerName: 'X-XSRF-TOKEN', token: 'csrf-token' })
      if (url === '/api/imports/confirm') return response({ batchId: 8, status: 'IMPORTED', importedRows: 4, issueCount: 0 })
      throw new Error(`Unexpected request: ${url}`)
    }))

    const wrapper = mountPanel()
    await flushPromises()
    const vm = wrapper.vm as any
    await vm.previewFile(new File(['data'], 'master-data.xlsx'))
    await vm.confirmImport()

    expect(calls.some(call => call.url === '/api/imports/confirm' && call.init?.method === 'POST')).toBe(true)
    expect(JSON.parse(String(calls.find(call => call.url === '/api/imports/confirm')?.init?.body))).toEqual({ batchId: 8 })
    expect(vm.preview).toBeNull()
    expect(vm.confirmation.status).toBe('IMPORTED')
    expect(vm.message).toBe('导入成功，共写入 4 行')
    wrapper.unmount()
  })
})
