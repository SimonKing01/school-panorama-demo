import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  base: '/',
  plugins: [vue()],
  server: {
    port: 5181,
    // 本地开发时把 /ontology-api 重写到底座本体 API；容器环境由 nginx.conf 反代。
    proxy: {
      '/ontology-api': {
        target: 'http://127.0.0.1:18090',
        changeOrigin: true,
        rewrite: (path: string) => path.replace(/^\/ontology-api/, '/api/ontology'),
      },
    },
  },
  build: {
    outDir: 'dist',
  },
})
