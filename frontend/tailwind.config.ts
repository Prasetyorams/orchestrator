import type { Config } from "tailwindcss";

const config: Config = {
  content: [
    "./app/**/*.{ts,tsx}",
    "./components/**/*.{ts,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        // Palet yang diminta: sidebar biru tua/arang, isi krem, kartu putih.
        sidebar: "#1E293B",
        sidebarHover: "#334155",
        canvas: "#F5F4F0",
        card: "#FFFFFF",
        line: "#E5E3DD",
        ink: "#22252A",
        muted: "#6B7280",
        ok: "#2E9E5B",
        info: "#2A6FDB",
        warn: "#D9891F",
        danger: "#D64545",
      },
    },
  },
  plugins: [],
};

export default config;
