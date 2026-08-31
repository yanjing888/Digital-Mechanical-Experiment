import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '0.0.0.0',
    port: 3780,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8586',
        changeOrigin: true
      }
    }
  },
  preview: {
    host: '0.0.0.0',
    port: 3780,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8586',
        changeOrigin: true
      }
    }
  }
})
