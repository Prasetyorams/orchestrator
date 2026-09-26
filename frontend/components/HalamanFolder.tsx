"use client";

import { useState, type ReactNode } from "react";
import { Building2, Folder, FolderLock, Lock } from "lucide-react";
import { errorText, type FolderNode } from "@/lib/api";
import { useFolder } from "@/lib/folder";
import { useT } from "@/lib/i18n";
import { Button, Card, Galat } from "@/components/ui/primitives";

/**
 * Halaman milik folder: isinya baru digambar sesudah foldernya diketahui.
 *
 * Isinya diberikan sebagai fungsi yang menerima folder itu, jadi setiap kueri
 * di dalamnya selalu punya folderId — tidak ada kueri yang terlanjur berjalan
 * tanpa folder lalu menarik isi seluruh penyewa.
 */
export function PerluFolder({ children }: { children: (folder: FolderNode) => ReactNode }) {
  const { t } = useT();
  const { folder, memuat, galat, bukaPribadi } = useFolder();
  const [galatPribadi, setGalatPribadi] = useState("");

  if (memuat) return <p className="text-sm text-muted">{t("Memuat...")}</p>;

  if (galat) return <Galat pesan={t("Daftar folder tidak bisa diambil.")} />;

  if (!folder) {
    return (
      <Card className="mx-auto max-w-lg p-8 text-center">
        <Folder className="mx-auto h-10 w-10 text-muted" strokeWidth={1.5} />
        <h1 className="mt-3 text-base font-semibold text-ink">{t("Belum ada folder yang bisa dibuka")}</h1>
        <p className="mt-1 text-sm text-muted">
          {t("Anda belum ditugaskan ke folder bersama mana pun. Minta Administrator menugaskan Anda, atau pakai Folder Saya.")}
        </p>
        <Button
          variant="primary"
          className="mt-4"
          onClick={() => bukaPribadi().catch((e) => setGalatPribadi(errorText(e)))}
        >
          {t("Buka Folder Saya")}
        </Button>
        <Galat pesan={galatPribadi} className="mt-3" />
      </Card>
    );
  }

  return <>{children(folder)}</>;
}

/**
 * Pengganti isi halaman yang tidak boleh dibuka peran orangnya.
 *
 * Menu sudah tidak menawarkan halaman itu, jadi yang sampai ke sini datang
 * lewat alamat langsung atau penanda lama. Tanpa ini yang terlihat adalah
 * tabel kosong — yang terbaca sebagai "belum ada data", bukan "tidak boleh".
 */
export function TanpaIzin() {
  const { t } = useT();

  return (
    <Card className="mx-auto max-w-lg p-8 text-center">
      <Lock className="mx-auto h-10 w-10 text-muted" strokeWidth={1.5} />
      <h1 className="mt-3 text-base font-semibold text-ink">{t("Tidak ada akses")}</h1>
      <p className="mt-1 text-sm text-muted">
        {t("Peran Anda tidak mengizinkan membuka halaman ini. Minta Administrator menambahkan izinnya di Tenant › Pengguna › Peran.")}
      </p>
    </Card>
  );
}

/**
 * Judul halaman beserta tempatnya: jalur folder untuk halaman Folders, atau
 * "Tenant" untuk halaman pengelolaan. Orang yang berpindah folder lewat bilah
 * kiri harus bisa melihat sekilas isi folder mana yang sedang dibacanya.
 */
export function JudulHalaman({
  judul,
  konteks = "folder",
  aksi,
  children,
}: {
  judul: string;
  konteks?: "folder" | "tenant";
  /** Tombol di kanan. */
  aksi?: ReactNode;
  /** Penyaring di sebelah judul. */
  children?: ReactNode;
}) {
  const { t } = useT();
  const { folder, namaJalur } = useFolder();

  const Ikon = konteks === "tenant" ? Building2 : folder?.personal ? FolderLock : Folder;
  const tempat = konteks === "tenant" ? t("Tenant") : folder ? namaJalur(folder.id) : "";

  return (
    <div className="mb-4 flex flex-wrap items-end gap-3">
      <div className="min-w-0">
        {tempat ? (
          <p className="flex items-center gap-1.5 text-xs text-muted">
            <Ikon className="h-3.5 w-3.5 shrink-0" />
            <span className="truncate">{tempat}</span>
          </p>
        ) : null}
        <h1 className="mt-0.5 text-xl font-semibold text-ink">{judul}</h1>
      </div>

      {children ? <div className="flex flex-wrap items-center gap-2">{children}</div> : null}

      {aksi ? <div className="ml-auto flex flex-wrap items-center gap-2">{aksi}</div> : null}
    </div>
  );
}

/**
 * Baris alat di atas daftar: penyaring di kiri, tombol di kanan. Dipakai
 * halaman yang judulnya sudah ditulis oleh bagiannya (Automations,
 * Pemantauan, dan bagian Tenant yang punya sub-tab).
 */
export function BilahAlat({ children, aksi }: { children?: ReactNode; aksi?: ReactNode }) {
  if (!children && !aksi) return null;

  return (
    <div className="mb-4 flex flex-wrap items-center gap-3">
      {children}
      {aksi ? <div className="ml-auto flex flex-wrap items-center gap-2">{aksi}</div> : null}
    </div>
  );
}
