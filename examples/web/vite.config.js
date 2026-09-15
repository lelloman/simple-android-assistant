import { defineConfig } from 'vite';
export default defineConfig({ resolve: { alias: { vue: 'vue/dist/vue.esm-bundler.js' }, dedupe: ['vue'] }, build: { outDir: 'target/example-web', rollupOptions: { input: 'examples/web/index.html' } } });
