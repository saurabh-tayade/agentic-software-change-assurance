import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

const proxy = { '/api/v1': { target: 'http://127.0.0.1:8080', changeOrigin: true } };

export default defineConfig({
    plugins: [react()],
    server: { host: '127.0.0.1', port: 5173, strictPort: true, cors: false, proxy },
    preview: { host: '127.0.0.1', port: 5173, strictPort: true, cors: false, proxy },
    test: { environment: 'jsdom', setupFiles: ['./src/test/setup.ts'], restoreMocks: true },
});
