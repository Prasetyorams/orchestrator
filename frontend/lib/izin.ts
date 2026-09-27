"use client";

import { useQuery } from "@tanstack/react-query";
import { OpenOrchestratorApi } from "@/lib/api";

/**
 * Izin peran orang yang sedang masuk, dari /api/auth/me.
 *
 * Polanya sama dengan di server (security/Izin.java): "*" berarti semuanya,
 * "processes.*" semua tindakan atas proses, "*.read" membaca apa pun, dan
 * "processes.read" satu tindakan saja.
 *
 * Yang MENJAGA adalah server — setiap endpoint diperiksa di sana. Di sini
 * izin hanya dipakai untuk tidak menawarkan tombol yang pasti ditolak: tombol
 * yang selalu berakhir "tidak berhak" hanya mengajari orang untuk berhenti
 * mencoba.
 */

export function cocokIzin(pola: readonly string[], izin: string): boolean {
  const titik = izin.indexOf(".");
  const sumber = izin.slice(0, titik);
  const aksi = izin.slice(titik + 1);

  return pola.some((p) => p === "*" || p === izin || p === `${sumber}.*` || p === `*.${aksi}`);
}

export function useIzin() {
  const me = useQuery({ queryKey: ["me"], queryFn: OpenOrchestratorApi.me, staleTime: 30_000 });
  const pola = me.data?.permissions;

  return {
    /** Izinnya sudah diketahui. */
    siap: me.isSuccess,
    /**
     * Selama izinnya belum diketahui — dan dari server lama yang belum
     * mengirimnya — semuanya dianggap boleh: menyembunyikan lalu memunculkan
     * menu terasa lebih rusak daripada sebaliknya, dan server tetap menolak
     * yang memang tidak boleh.
     */
    boleh: (izin: string) => !pola || cocokIzin(pola, izin),
    /** Boleh salah satu. */
    bolehSalahSatu: (daftar: readonly string[]) => !pola || daftar.some((i) => cocokIzin(pola, i)),
  };
}
