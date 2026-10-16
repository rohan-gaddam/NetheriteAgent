import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/v1': {
        target: 'https://localhost',
        changeOrigin: true,
        secure: false,
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes, req, res) => {
            const setCookie = proxyRes.headers['set-cookie'];
            if (!setCookie) return;
            const stripDevSecure = (cookie: string) =>
              cookie.replace(/;\s*[Ss]ecure(?=(;|$))/g, '');
            proxyRes.headers['set-cookie'] = Array.isArray(setCookie)
              ? setCookie.map(stripDevSecure)
              : stripDevSecure(setCookie);
          });
        },
      },
      '/actuator': {
        target: 'https://localhost',
        changeOrigin: true,
        secure: false,
      },
    },
  },
});
