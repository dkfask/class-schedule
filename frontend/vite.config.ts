import { fileURLToPath } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const registration = loadEnv(mode, fileURLToPath(new URL('..', import.meta.url)), 'APP_AUTH_REGISTRATION_ENABLED')
  return {
    define: {
      'import.meta.env.VITE_AUTH_REGISTRATION_ENABLED': JSON.stringify(
        process.env.VITE_AUTH_REGISTRATION_ENABLED ?? registration.APP_AUTH_REGISTRATION_ENABLED ?? 'false'
      ),
    },
    plugins: [vue()],
    build: {
      rollupOptions: {
        output: {
          manualChunks: {
            'element-plus': ['element-plus'],
            vendor: ['vue', 'vue-router', 'pinia'],
          },
        },
      },
    },
    server: {
      port: 5173,
      proxy: {
        '/api': 'http://localhost:8080'
      }
    }
  }
})
