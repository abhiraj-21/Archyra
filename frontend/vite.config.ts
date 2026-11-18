/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig, loadEnv, type ProxyOptions } from 'vite';

// Every backend path the browser talks to goes through this proxy, so the SPA and the API share one origin in
// development. That keeps the SameSite=Strict refresh cookie working and lets the SPA read the XSRF-TOKEN cookie.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');
  const backend = env.VITE_BACKEND_ORIGIN || 'http://localhost:8080';

  // changeOrigin stays false: the backend builds the OAuth2 redirect_uri from the Host header, which must remain
  // localhost:5173. xfwd adds X-Forwarded-For/Proto/Port/Host, which Tomcat's RemoteIpValve trusts from loopback.
  const proxy: ProxyOptions = { target: backend, changeOrigin: false, xfwd: true, secure: false };

  return {
    plugins: [react(), tailwindcss()],
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
    },
    server: {
      port: 5173,
      strictPort: true,
      proxy: {
        '/api': proxy,
        '/oauth2': proxy,
        '/login/oauth2': proxy,
      },
    },
    preview: {
      port: 5173,
      strictPort: true,
      proxy: {
        '/api': proxy,
        '/oauth2': proxy,
        '/login/oauth2': proxy,
      },
    },
    build: {
      sourcemap: false,
      chunkSizeWarningLimit: 900,
    },
    test: {
      environment: 'jsdom',
      include: ['src/**/*.test.{ts,tsx}'],
      restoreMocks: true,
    },
  };
});
