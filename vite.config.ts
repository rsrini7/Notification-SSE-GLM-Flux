import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

// Custom plugin to handle root path
function rootPathPlugin() {
  return {
    name: 'root-path-plugin',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        if (req.url === '/') {
          req.url = '/index.html';
        }
        next();
      });
    }
  }
}

// https://vitejs.dev/config/
export default defineConfig({
  base: './',
  plugins: [
    react({
      include: '**/*.{jsx,tsx}',
      // Completely disable React refresh
      fastRefresh: false,
      skipReactRefresh: true,
      // Don't inject React refresh runtime
      injectReactRefresh: false
    }),
    rootPathPlugin()
  ],
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false,
      },
    },
    // Disable HMR completely
    hmr: false
  },
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
  },
  optimizeDeps: {
    include: ['react', 'react-dom']
  },
  esbuild: {
    jsx: 'automatic'
  }
})