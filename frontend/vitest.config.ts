import { defineConfig, mergeConfig } from 'vitest/config'
import viteConfig from './vite.config'

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: 'jsdom',
      globals: true,
      include: ['src/**/*.spec.ts'],
      setupFiles: ['./test/setup.ts'],
      clearMocks: true,
      restoreMocks: true,
    },
  }),
)
