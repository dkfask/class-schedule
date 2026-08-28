import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { clearCsrfToken } from '../api/http'
import { resetTermStore, useTermStore } from './term'

function response(body: unknown, ok = true) {
  return { ok, json: async () => body }
}

describe('term store', () => {
  beforeEach(() => {
    resetTermStore()
    window.localStorage.clear()
    clearCsrfToken()
  })

  afterEach(() => {
    resetTermStore()
    window.localStorage.clear()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('rejects archived and invalid persisted selections', async () => {
    window.localStorage.setItem('class-schedule.term', 'MISSING')
    vi.stubGlobal('fetch', vi.fn(async () => response([
      { code: 'ARCHIVED-TERM', name: '历史学期', status: 'ARCHIVED' },
      { code: '2026-FALL', name: '2026 秋季学期', status: 'ACTIVE' },
    ])))

    const store = useTermStore()
    await store.loadTerms()

    expect(store.terms.value).toEqual([{ code: '2026-FALL', name: '2026 秋季学期', status: 'ACTIVE' }])
    expect(store.selectedTermCode.value).toBe('2026-FALL')
    expect(store.hasValidTerm.value).toBe(true)
    expect(window.localStorage.getItem('class-schedule.term')).toBe('2026-FALL')
    expect(store.selectTerm('MISSING')).toBe(false)
    expect(store.selectedTermCode.value).toBe('2026-FALL')
  })

  it('clears selection when the server returns no usable terms', async () => {
    window.localStorage.setItem('class-schedule.term', '2026-FALL')
    vi.stubGlobal('fetch', vi.fn(async () => response([
      { code: 'ARCHIVED-TERM', name: '历史学期', status: 'ARCHIVED' },
    ])))

    const store = useTermStore()
    await store.loadTerms()

    expect(store.terms.value).toEqual([])
    expect(store.selectedTermCode.value).toBe('')
    expect(store.hasValidTerm.value).toBe(false)
    expect(window.localStorage.getItem('class-schedule.term')).toBeNull()
  })

  it('exposes a stable error and clears stale values when loading fails', async () => {
    window.localStorage.setItem('class-schedule.term', '2026-FALL')
    vi.stubGlobal('fetch', vi.fn(async () => response({ message: '学期服务不可用' }, false)))

    const store = useTermStore()
    await store.loadTerms()

    expect(store.terms.value).toEqual([])
    expect(store.selectedTermCode.value).toBe('')
    expect(store.ready.value).toBe(true)
    expect(store.error.value).toBe('学期服务不可用')
    expect(store.hasValidTerm.value).toBe(false)
  })

  it('deduplicates concurrent term loads', async () => {
    let resolveRequest: ((value: ReturnType<typeof response>) => void) | undefined
    const fetchMock = vi.fn(() => new Promise<ReturnType<typeof response>>(resolve => {
      resolveRequest = resolve
    }))
    vi.stubGlobal('fetch', fetchMock)

    const store = useTermStore()
    const first = store.loadTerms()
    const second = store.loadTerms()
    expect(fetchMock).toHaveBeenCalledTimes(1)

    resolveRequest?.(response([{ code: '2026-FALL', name: '2026 秋季学期', status: 'ACTIVE' }]))
    await Promise.all([first, second])
    expect(store.selectedTermCode.value).toBe('2026-FALL')
  })
})
