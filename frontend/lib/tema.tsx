"use client";

import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";

/**
 * Tema tampilan: Terang, Gelap, atau Ikuti sistem.
 *
 * Yang diubah hanya kelas .dark di <html>; warnanya sendiri ada di
 * globals.css. Pilihannya diingat per peramban. "Ikuti sistem" adalah
 * bawaannya dan benar-benar MENGIKUTI: sistem yang berganti ke gelap saat
 * matahari terbenam ikut mengganti OpenOrchestrator yang sedang terbuka, tanpa muat
 * ulang.
 *
 * Kelas awalnya dipasang oleh SKRIP_TEMA di app/layout.tsx sebelum halaman
 * digambar; penyedia ini mengambil alih sesudahnya.
 */

export type Tema = "terang" | "gelap" | "sistem";

export const KUNCI_TEMA = "openorchestrator.tema";

const MEDIA = "(prefers-color-scheme: dark)";

/**
 * Dijalankan apa adanya di <head>, sebelum React ada. Harus berdiri sendiri
 * dan tidak boleh gagal: penyimpanan yang dilarang berarti ikut sistem.
 */
export const SKRIP_TEMA = `(function(){try{var t=localStorage.getItem("${KUNCI_TEMA}");var g=t==="gelap"||(t!=="terang"&&window.matchMedia("${MEDIA}").matches);var h=document.documentElement;if(g)h.classList.add("dark");h.style.colorScheme=g?"dark":"light";}catch(e){}})();`;

function temaDikenal(x: string | null): x is Tema {
  return x === "terang" || x === "gelap" || x === "sistem";
}

type Isi = {
  /** Pilihan orangnya. */
  tema: Tema;
  setTema: (t: Tema) => void;
  /** Yang benar-benar tampil sekarang — "sistem" sudah diputuskan. */
  gelap: boolean;
};

const Konteks = createContext<Isi>({ tema: "sistem", setTema: () => {}, gelap: false });

export function TemaProvider({ children }: { children: ReactNode }) {
  // Dimulai dari "sistem" dan dibaca sesudah dipasang, sama seperti bahasa:
  // di server tidak ada localStorage, dan membacanya saat render pertama
  // membuat isi server dan peramban berbeda.
  const [tema, setTemaState] = useState<Tema>("sistem");
  const [sistemGelap, setSistemGelap] = useState(false);

  // Kelas di <html> baru disentuh SESUDAH pilihannya terbaca. Sebelum itu
  // keadaan awal di atas selalu "terang", dan menerapkannya akan mencabut
  // .dark yang sudah dipasang SKRIP_TEMA — kilatan putih sesaat, persis yang
  // dicegah skrip itu.
  const [siap, setSiap] = useState(false);

  useEffect(() => {
    try {
      const tersimpan = window.localStorage.getItem(KUNCI_TEMA);
      if (temaDikenal(tersimpan)) setTemaState(tersimpan);
    } catch {
      /* tanpa penyimpanan: ikut sistem */
    }

    const media = window.matchMedia(MEDIA);
    setSistemGelap(media.matches);
    setSiap(true);

    const ikuti = (e: MediaQueryListEvent) => setSistemGelap(e.matches);
    media.addEventListener("change", ikuti);

    return () => media.removeEventListener("change", ikuti);
  }, []);

  const gelap = tema === "gelap" || (tema === "sistem" && sistemGelap);

  useEffect(() => {
    if (!siap) return;

    const html = document.documentElement;
    html.classList.toggle("dark", gelap);
    html.style.colorScheme = gelap ? "dark" : "light";
  }, [gelap, siap]);

  const setTema = useCallback((t: Tema) => {
    setTemaState(t);
    try {
      window.localStorage.setItem(KUNCI_TEMA, t);
    } catch {
      /* diabaikan: pilihannya tetap berlaku sampai halaman ditutup */
    }
  }, []);

  return <Konteks.Provider value={{ tema, setTema, gelap }}>{children}</Konteks.Provider>;
}

export function useTema() {
  return useContext(Konteks);
}
