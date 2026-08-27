import { ref } from 'vue'
import { http } from '../api/http'

export interface TermOption {
  code: string
  name: string
  status: string
}

const terms = ref<TermOption[]>([])
const selectedTermCode = ref('2026-FALL')
const loading = ref(false)
let loaded = false

function restoreSelection() {
  if (typeof window === 'undefined') return
  const stored = window.localStorage.getItem('class-schedule.term')
  if (stored) selectedTermCode.value = stored
}

async function loadTerms(force = false) {
  if (loaded && !force) return terms.value
  loading.value = true
  try {
    const data = await http<TermOption[] | { terms?: TermOption[] }>('/api/terms')
    terms.value = Array.isArray(data) ? data : data.terms ?? []
    if (!terms.value.some(item => item.code === selectedTermCode.value)) {
      selectedTermCode.value = terms.value[0]?.code ?? selectedTermCode.value
    }
    loaded = true
  } finally {
    loading.value = false
  }
  return terms.value
}

function selectTerm(code: string) {
  selectedTermCode.value = code
  if (typeof window !== 'undefined') window.localStorage.setItem('class-schedule.term', code)
}

restoreSelection()

export function useTermStore() {
  return { terms, selectedTermCode, loading, loadTerms, selectTerm }
}
