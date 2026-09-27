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
import { usePathname } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { OpenOrchestratorApi, type FolderNode, type FolderTree } from "@/lib/api";
import { useT } from "@/lib/i18n";

/**
 * Folder yang sedang dibuka, untuk seluruh halaman.
 *
 * Dipegang di SATU tempat, bukan di setiap halaman: bilah folder, dasbor, dan
 * setiap daftar harus sepakat tentang folder mana yang sedang dilihat. Setiap
 * kueri yang bergantung padanya memasukkan folderId ke kuncinya, jadi
 * berganti folder cukup dengan mengganti nilai ini — datanya menyusul sendiri.
 *
 * Pilihannya diingat per peramban, tidak ditaruh di alamat halaman: alamat
 * yang membawa folder berarti setiap tautan di seluruh antarmuka harus ingat
 * menyertakannya, dan satu tautan yang lupa melempar orang ke folder lain.
 */

const KUNCI = "openorchestrator.folder";

type Isi = {
  pohon: FolderTree | undefined;
  memuat: boolean;
  galat: boolean;
  /** Folder yang sedang dibuka; null kalau tidak ada satu pun yang boleh dibuka. */
  folder: FolderNode | null;
  folderId: string | null;
  pilih: (id: string) => void;
  /** Buka Folder Saya — dibuat lebih dulu kalau belum pernah dibuka. */
  bukaPribadi: () => Promise<void>;
  /** Folder dan seluruh leluhurnya, dari akar. */
  jalur: (id: string | null | undefined) => FolderNode[];
  /** "Keuangan / Tagihan", atau "Folder Saya". */
  namaJalur: (id: string | null | undefined) => string;
  /** Semua folder yang boleh dibuka, termasuk Folder Saya — untuk pilihan "pindahkan ke". */
  bolehDibuka: FolderNode[];
  bolehKelola: boolean;
};

const Konteks = createContext<Isi | null>(null);

export function FolderProvider({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const klien = useQueryClient();
  const { t } = useT();

  // Dibaca SESUDAH dipasang, alasannya sama dengan pilihan bahasa: di server
  // tidak ada localStorage, dan membacanya saat render pertama membuat isi
  // dari server dan dari peramban tidak sama.
  const [dipilih, setDipilih] = useState<string | null>(null);

  useEffect(() => {
    try {
      setDipilih(window.localStorage.getItem(KUNCI));
    } catch {
      /* tanpa penyimpanan: mulai dari folder bawaan */
    }
  }, []);

  const pohon = useQuery({
    queryKey: ["folders"],
    queryFn: OpenOrchestratorApi.folders,
    // Layar masuk belum punya token; pohonnya baru ditarik sesudah masuk.
    enabled: pathname !== "/login",
    staleTime: 30_000,
    // Folder yang dibuat atau dihapus orang lain muncul tanpa muat ulang.
    refetchInterval: 60_000,
  });

  const semua = useMemo(() => {
    const data = pohon.data;
    if (!data) return [] as FolderNode[];

    return data.personal ? [...data.folders, data.personal] : data.folders;
  }, [pohon.data]);

  const perId = useMemo(() => new Map(semua.map((f) => [f.id, f])), [semua]);

  const bolehDibuka = useMemo(() => semua.filter((f) => f.accessible !== false), [semua]);

  // Folder yang tersimpan bisa saja sudah dihapus, atau penggunanya sudah
  // tidak ditugaskan ke sana. Jatuhnya ke folder bawaan, lalu folder bersama
  // pertama yang boleh dibuka, lalu Folder Saya.
  const folder = useMemo<FolderNode | null>(() => {
    return (
      bolehDibuka.find((f) => f.id === dipilih) ??
      bolehDibuka.find((f) => f.isDefault) ??
      bolehDibuka.find((f) => !f.personal) ??
      bolehDibuka[0] ??
      null
    );
  }, [bolehDibuka, dipilih]);

  const pilih = useCallback((id: string) => {
    setDipilih(id);
    try {
      window.localStorage.setItem(KUNCI, id);
    } catch {
      /* diabaikan */
    }
  }, []);

  const bukaPribadi = useCallback(async () => {
    const ada = pohon.data?.personal;

    if (ada) {
      pilih(ada.id);
      return;
    }

    const baru = await OpenOrchestratorApi.personalFolder();
    await klien.invalidateQueries({ queryKey: ["folders"] });
    pilih(baru.id);
  }, [klien, pilih, pohon.data?.personal]);

  const jalur = useCallback(
    (id: string | null | undefined) => {
      const hasil: FolderNode[] = [];
      let x = id ? perId.get(id) : undefined;

      // Dibatasi supaya data yang rusak — induk yang menunjuk dirinya
      // sendiri — tidak membekukan halaman.
      for (let i = 0; x && i < 32; i++) {
        hasil.unshift(x);
        x = x.parentId ? perId.get(x.parentId) : undefined;
      }

      return hasil;
    },
    [perId],
  );

  const namaJalur = useCallback(
    (id: string | null | undefined) =>
      jalur(id)
        .map((f) => (f.personal ? t("Folder Saya") : f.name))
        .join(" / "),
    [jalur, t],
  );

  const isi = useMemo<Isi>(
    () => ({
      pohon: pohon.data,
      memuat: pohon.isLoading,
      galat: pohon.isError,
      folder,
      folderId: folder?.id ?? null,
      pilih,
      bukaPribadi,
      jalur,
      namaJalur,
      bolehDibuka,
      bolehKelola: pohon.data?.canManage ?? false,
    }),
    [pohon.data, pohon.isLoading, pohon.isError, folder, pilih, bukaPribadi, jalur, namaJalur, bolehDibuka],
  );

  return <Konteks.Provider value={isi}>{children}</Konteks.Provider>;
}

export function useFolder(): Isi {
  const isi = useContext(Konteks);
  if (!isi) throw new Error("useFolder harus dipakai di dalam FolderProvider.");
  return isi;
}
