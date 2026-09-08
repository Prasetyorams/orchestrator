"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";

/**
 * QueryClient dibuat di dalam useState, bukan sebagai modul global.
 *
 * Modul global dipakai bersama oleh semua permintaan di server, jadi cache
 * satu pengguna bisa terlihat oleh pengguna lain. Dibuat per komponen, tiap
 * pemuatan halaman punya cache-nya sendiri.
 */
export function Providers({ children }: { children: ReactNode }) {
  const [client] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: 5_000,
            refetchOnWindowFocus: false,
            retry: 1,
          },
        },
      }),
  );

  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}
