import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * Сборка кладётся прямо в ресурсы Kotlin-сервера (classpath:/web/admin) —
 * серверная сборка и Docker остаются без Node. Каталог коммитится.
 */
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../../server/src/main/resources/web/admin',
    emptyOutDir: true,
    sourcemap: false,
    target: 'es2020',
    chunkSizeWarningLimit: 700
  },
  server: {
    port: 5173,
    // Дев-режим: API проксируется на staging-сервер (в проде SPA и API same-origin).
    proxy: {
      '/v1': {
        target: 'https://omnix.144.31.14.236.sslip.io',
        changeOrigin: true,
        secure: true
      }
    }
  }
})
