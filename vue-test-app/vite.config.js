import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const apiTarget = process.env.VITE_API_TARGET || 'http://localhost:8080'
const aiTarget = process.env.VITE_AI_TARGET || 'http://localhost:8083'

export default defineConfig({
  plugins: [vue()],

  server: {
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      '/ws/collaborate': {
        target: 'http://localhost:8084',
        changeOrigin: true,
        ws: true,
      },
      '/ws': {
        target: apiTarget,
        changeOrigin: true,
        ws: true,
      },
      '/api/ai': {
        target: aiTarget,
        changeOrigin: true,
      },
      '/api/skills': {
        target: aiTarget,
        changeOrigin: true,
      },
      '/api': {
        target: apiTarget,
        changeOrigin: false,
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => {
            proxyReq.removeHeader('Origin')
            proxyReq.setHeader('Origin', 'http://localhost:5173')
            proxyReq.setHeader('Connection', 'keep-alive')
          })
          proxy.on('error', (err) => {
            console.log('[proxy error]', err.message)
          })
        }
      }
    }
  }
})
