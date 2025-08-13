import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';
import fs from 'fs';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

export default defineConfig(({ mode }) => {
  // Add a log to be 100% sure which mode is being used
  console.log(`Vite is running in mode: "${mode}"`);

  // --- START OF HARDCODED CONFIGURATION ---

  let adminProxyTarget = '';
  let userProxyTarget = '';
  const clientBaseUrl = '/api'; // For client-side code, we always use the relative path

  // Set the proxy targets based on the mode
  if (mode === 'compose') {
    adminProxyTarget = 'https://localhost:8081';
    userProxyTarget = 'https://localhost:8082';
  } else if (mode === 'k8s') {
    adminProxyTarget = 'https://localhost:30443';
    userProxyTarget = 'https://localhost:30443';
  }

  // --- END OF HARDCODED CONFIGURATION ---

  return {
    base: '/',
    plugins: [
      react({
        include: '**/*.{jsx,tsx}',
        fastRefresh: false,
      }),
    ],
    // Manually define the variables for the client-side code
    define: {
      'import.meta.env.VITE_ADMIN_API_BASE_URL': JSON.stringify(`${clientBaseUrl}/admin`),
      'import.meta.env.VITE_USER_API_BASE_URL': JSON.stringify(`${clientBaseUrl}/user`),
    },
    server: {
      port: 3000,
      host: true,
      https: {
        key: fs.readFileSync('./localhost-key.pem'),
        cert: fs.readFileSync('./localhost.pem'),
      },
      http2: true,
      proxy: {
        '/api/admin': {
          target: adminProxyTarget,
          changeOrigin: true,
          secure: false,
        },
        '/api/user': {
          target: userProxyTarget,
          changeOrigin: true,
          secure: false,
        },
      },
      hmr: false
    },
    resolve: {
      alias: {
        '@': path.resolve(__dirname, './src'),
        'components': path.resolve(__dirname, './src/components'),
        'utils': path.resolve(__dirname, './src/lib/utils'),
        'ui': path.resolve(__dirname, './src/components/ui'),
        'lib': path.resolve(__dirname, './src/lib'),
        'hooks': path.resolve(__dirname, './src/hooks'),
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
    },
  };
});