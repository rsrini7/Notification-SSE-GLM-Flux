import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'
import { fileURLToPath } from 'url'
import fs from 'fs'

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
export default defineConfig(({ mode }) => {
  // Load the appropriate .env file based on the mode
  const env = loadEnv(mode, process.cwd(), '');

  return {
    base: '/',
      plugins: [
        react({
          include: '**/*.{jsx,tsx}',
          fastRefresh: false,
        }),
        rootPathPlugin()
      ],
      server: {
        port: 3000,
        host: true,
        https: {
          key: fs.readFileSync('./localhost-key.pem'),
          cert: fs.readFileSync('./localhost.pem'),
        },
        http2: true,
        proxy: {
          // Proxy requests for the Admin API
          '/api/admin': {
            target: env.VITE_ADMIN_API_PROXY_TARGET,
            changeOrigin: true,
            secure: false,
          },
          // Proxy requests for the User API (REST and SSE)
          '/api/user': {
            target: env.VITE_USER_API_PROXY_TARGET,
            changeOrigin: true,
            secure: false,
          },
        },
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
  }
  
})