import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';
import fs from 'fs';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

export default defineConfig(({ mode }) => {
  // UPDATED: Load env file from the project's root directory (__dirname),
  // regardless of where the 'npm' command is run from.
  const env = loadEnv(mode, __dirname, '');

  // Create a define block to manually expose VITE_ variables to the client
  const envWithVitePrefix = Object.entries(env)
    .filter(([key]) => key.startsWith('VITE_'))
    .reduce<{ [key: string]: string }>(
      (acc, [key, val]) => {
        acc[`import.meta.env.${key}`] = JSON.stringify(val);
        return acc;
      },
      {},
    );

  return {
    base: '/',
    plugins: [
      react({
        include: '**/*.{jsx,tsx}',
        fastRefresh: false,
      }),
    ],
    define: envWithVitePrefix,
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
          target: env.VITE_ADMIN_API_PROXY_TARGET,
          changeOrigin: true,
          secure: false,
        },
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