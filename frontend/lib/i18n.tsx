"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import {
  bahasaDikenal,
  setBahasaAktif,
  terjemahkan,
  terjemahkanPesan,
  type Bahasa,
} from "@/lib/bahasa";

// Diekspor ulang dari sini supaya komponen cukup mengimpor satu tempat.
export { BAHASA, type Bahasa } from "@/lib/bahasa";

type Isi = {
  bahasa: Bahasa;
  setBahasa: (b: Bahasa) => void;
  /** Teks antarmuka; {0}, {1}, ... diisi dari nilai sesudahnya. */
  t: (teks: string, ...nilai: (string | number)[]) => string;
  /** Teks dari server: galat, peringatan, keterangan pekerjaan, catatan. */
  tp: (teks: string | null | undefined) => string;
};

const Konteks = createContext<Isi>({
  bahasa: "id",
  setBahasa: () => {},
  t: (s, ...nilai) => terjemahkan("id", s, nilai),
  tp: (s) => s ?? "",
});

const KUNCI = "openorchestrator.bahasa";

export function I18nProvider({ children }: { children: ReactNode }) {
  // Dimulai dari "id" di server DAN di render pertama peramban.
  //
  // Membaca localStorage langsung saat useState akan membuat HTML server
  // (selalu "id") berbeda dari render pertama klien, dan React menolaknya
  // sebagai hydration mismatch. Jadi pilihannya dipasang sesudah pemasangan.
  const [bahasa, setBahasaState] = useState<Bahasa>("id");

  // Diset SAAT render, bukan di efek: anak-anak yang dirender dalam putaran
  // yang sama — tabel dengan tanggalnya — harus sudah memakai bahasa baru,
  // bukan tertinggal satu putaran di bahasa lama.
  setBahasaAktif(bahasa);

  useEffect(() => {
    try {
      const tersimpan = window.localStorage.getItem(KUNCI);
      if (bahasaDikenal(tersimpan)) setBahasaState(tersimpan);
    } catch {
      // Peramban yang melarang penyimpanan tetap boleh memakai OpenOrchestrator;
      // yang hilang cuma ingatan pilihan bahasanya.
    }
  }, []);

  // Pembaca layar dan terjemahan otomatis peramban membaca lang di <html>;
  // tanpa ini keduanya mengira halaman berbahasa Inggris masih Indonesia.
  useEffect(() => {
    document.documentElement.lang = bahasa;
  }, [bahasa]);

  const setBahasa = useCallback((b: Bahasa) => {
    setBahasaState(b);
    try {
      window.localStorage.setItem(KUNCI, b);
    } catch {
      /* diabaikan, alasannya sama dengan di atas */
    }
  }, []);

  const t = useCallback(
    (teks: string, ...nilai: (string | number)[]) => terjemahkan(bahasa, teks, nilai),
    [bahasa],
  );

  const tp = useCallback((teks: string | null | undefined) => terjemahkanPesan(bahasa, teks), [bahasa]);

  const isi = useMemo(() => ({ bahasa, setBahasa, t, tp }), [bahasa, setBahasa, t, tp]);

  return <Konteks.Provider value={isi}>{children}</Konteks.Provider>;
}

export function useT() {
  return useContext(Konteks);
}
