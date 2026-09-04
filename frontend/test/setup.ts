const storageValues = new Map<string, string>()

const localStorageShim: Storage = {
  get length() {
    return storageValues.size
  },
  clear() {
    storageValues.clear()
  },
  getItem(key: string) {
    return storageValues.get(String(key)) ?? null
  },
  key(index: number) {
    return Array.from(storageValues.keys())[index] ?? null
  },
  removeItem(key: string) {
    storageValues.delete(String(key))
  },
  setItem(key: string, value: string) {
    storageValues.set(String(key), String(value))
  },
}

if (typeof window !== 'undefined') {
  Object.defineProperty(window, 'localStorage', { configurable: true, value: localStorageShim })
}
Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: localStorageShim })
