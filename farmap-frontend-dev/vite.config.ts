import { defineConfig } from "vite";
import react from "@vitejs/plugin-react-swc";
import tailwindcss from "@tailwindcss/vite";
import path from "node:path";

const backendProxy = {
  target: "http://127.0.0.1:8080",
  changeOrigin: true,
};

const apiProxy = {
  "/api": backendProxy,
  "/user": backendProxy,
  "/weather": backendProxy,
  "/ai-model": backendProxy,
  "/historical-cases": backendProxy,
  "/expert": backendProxy,
  "/monitor": backendProxy,
  "/uploads": backendProxy,
};

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  base: "/farmap/",
  server: {
    open: true,
    // usable in local area network
    host: true,
    port: 5173,
    proxy: apiProxy,
  },
  preview: {
    host: true,
    port: 5173,
    proxy: apiProxy,
  },
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "src"),
    },
  },
});
