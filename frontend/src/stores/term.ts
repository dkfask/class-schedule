import { computed, ref } from 'vue'
import { http } from '../api/http'

export interface TermOption {
  code: string
  name: string
  status: string
}

const terms = ref<TermOption[]>([])
const selectedTermCode = ref('')
const loading = ref(false)
const ready = ref(false)
const error = ref('')
let loaded = false
let loadPromise: Promise<TermOption[]> | null = null

function storedSelection() {
  if (typeof window === 'undefined') return ''
  return window.localStorage.getItem('class-schedule.term') ?? ''
}

async function loadTerms(force = false) {
  if (loaded && !force) return terms.value
  if (loadPromise) return loadPromise
  loading.value = true
  error.value = ''
  loadPromise = (async () => {
    try {
      const data = await http<TermOption[] | { terms?: TermOption[] }>('/api/terms')
      terms.value = (Array.isArray(data) ? data : data.terms ?? [])
        .filter(item => item.code && item.status !== 'ARCHIVED')
      const stored = storedSelection()
      const selected = terms.value.find(item => item.code === stored)?.code ?? terms.value[0]?.code ?? ''
      selectedTermCode.value = selected
      if (typeof window !== 'undefined') {
        if (selected) window.localStorage.setItem('class-schedule.term', selected)
        else window.localStorage.removeItem('class-schedule.term')
      }
      loaded = true
      ready.value = true
      return terms.value
    } catch (reason) {
      terms.value = []
      selectedTermCode.value = ''
      error.value = reason instanceof Error ? reason.message : '学期列表加载失败'
      ready.value = true
      return terms.value
    } finally {
      loading.value = false
      loadPromise = null
    }
  })()
  return loadPromise
}

function selectTerm(code: string) {
  if (!terms.value.some(item => item.code === code)) return false
  selectedTermCode.value = code
  if (typeof window !== 'undefined') window.localStorage.setItem('class-schedule.term', code)
  return true
}

const hasValidTerm = computed(() => ready.value && terms.value.some(item => item.code === selectedTermCode.value))

function resetTermStore() {
  terms.value = []
  selectedTermCode.value = ''
  loading.value = false
  ready.value = false
  error.value = ''
  loaded = false
  loadPromise = null
}

export function useTermStore() {
  return { terms, selectedTermCode, loading, ready, error, hasValidTerm, loadTerms, selectTerm }
}

export { resetTermStore }
