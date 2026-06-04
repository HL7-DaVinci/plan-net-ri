import { defineConfig } from "vite";
import { devtools } from "@tanstack/devtools-vite";
import viteReact from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import tsConfigPaths from "vite-tsconfig-paths";
import { tanstackRouter } from "@tanstack/router-plugin/vite";
import { fileURLToPath, URL } from "node:url";

// https://vitejs.dev/config/
export default defineConfig(({ command, isPreview }) => ({
  // The production build (and `vite preview` of it) is served under /crawler so the
  // web root is left for the HAPI tester overlay. Dev (`vite`) runs at the root.
  base: command === "build" || isPreview ? "/crawler/" : "/",
  plugins: [
    devtools(),
    tanstackRouter({
      target: "react",
      autoCodeSplitting: true,
    }),
    tsConfigPaths(),
    viteReact(),
    tailwindcss(),
  ],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          // Split React into its own chunk
          react: ["react", "react-dom"],
          // Split TanStack libraries
          tanstack: [
            "@tanstack/react-query",
            "@tanstack/react-router",
            "@tanstack/react-table",
            "@tanstack/react-virtual",
          ],
        // Split Radix UI components
        radix: [
          "radix-ui",
          "cmdk",
        ],
        },
      },
    },
  },
}));
