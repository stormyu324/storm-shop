import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'

// 新版后台挂在 /console/ 下，打包结果放进 Spring Boot 的静态资源目录，和后端一起发布
export default defineConfig({
  base: '/console/',
  plugins: [vue()],
  build: {
    outDir: fileURLToPath(new URL('../src/main/resources/static/console', import.meta.url)),
    emptyOutDir: true,
    chunkSizeWarningLimit: 1500
  },
  server: {
    port: 5173,
    // 开发时（npm run dev）把接口和后端页面转发到本机的 Spring Boot
    proxy: {
      '/api': 'http://localhost:8080',
      '/admin': 'http://localhost:8080',
      '/uploads': 'http://localhost:8080',
      '/img': 'http://localhost:8080'
    }
  }
})
