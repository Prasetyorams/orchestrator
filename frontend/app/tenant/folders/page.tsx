"use client";

import { useCallback, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowRight, FolderLock, FolderPlus, Pencil, Trash2 } from "lucide-react";
import { ForgeHubApi, errorText, statusGalat, type FolderKelola } from "@/lib/api";
import { useFolder } from "@/lib/folder";
import { useT } from "@/lib/i18n";
import { useIzin } from "@/lib/izin";
import { dateTimeOf } from "@/lib/utils";
import { Button, Card, CardHeader, Galat, IconButton } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { DialogFolder } from "@/components/DialogFolder";
import { JudulHalaman } from "@/components/HalamanFolder";
import { urutPohon } from "@/components/PilihFolder";

/**
 * Pengelolaan folder seluruh penyewa: pohonnya, isinya, dan Folder Saya
 * setiap orang.
 *
 * Folder yang masih berisi tidak bisa dihapus dari sini — tombolnya mati dan
 * menjelaskan sebabnya — karena menghapus folder tidak boleh sekaligus
 * menghapus proses dan aset yang mungkin masih dijalankan robot.
 */
export default function KelolaFolder() {
  const { t, tp } = useT();
  const { boleh } = useIzin();
  const router = useRouter();
  const klien = useQueryClient();
  const { pilih, memuat } = useFolder();

  const [sunting, setSunting] = useState<FolderKelola | null>(null);
  const [baruDi, setBaruDi] = useState<string | null | undefined>(undefined);
  const [galat, setGalat] = useState("");

  const folders = useQuery({
    queryKey: ["folders", "manage"],
    queryFn: ForgeHubApi.foldersManage,
    enabled: boleh("folders.read"),
    retry: false,
  });

  const bersama = useMemo(() => (folders.data ?? []).filter((f) => !f.personal), [folders.data]);
  const pribadi = useMemo(() => (folders.data ?? []).filter((f) => f.personal), [folders.data]);

  // Urutan pohon, dengan kedalaman untuk indentasi nama.
  const baris = useMemo(() => {
    const perId = new Map(bersama.map((f) => [f.id, f]));
    return urutPohon(bersama).map((b) => ({ ...perId.get(b.node.id)!, kedalaman: b.kedalaman }));
  }, [bersama]);

  const hapus = useMutation({
    mutationFn: ForgeHubApi.deleteFolder,
    onSuccess: () => klien.invalidateQueries({ queryKey: ["folders"] }),
    onError: (e) => setGalat(errorText(e)),
  });

  const tutup = useCallback(() => {
    setSunting(null);
    setBaruDi(undefined);
  }, []);

  function buka(id: string) {
    pilih(id);
    router.push("/");
  }

  const isi = (f: FolderKelola) => f.processCount + f.triggerCount + f.queueCount + f.assetCount + f.bucketCount;

  function alasanTakBisaHapus(f: FolderKelola): string | null {
    if (f.isDefault) return t("Folder bawaan tidak bisa dihapus.");
    if (f.childCount > 0) return t("Masih punya subfolder.");
    if (isi(f) > 0) return t("Masih berisi proses, pemicu, antrean, aset, atau ember.");
    return null;
  }

  if (memuat) return <p className="text-sm text-muted">{t("Memuat...")}</p>;

  if (!boleh("folders.read") || statusGalat(folders.error) === 403) {
    return (
      <div>
        <JudulHalaman judul={t("Folder|tab")} konteks="tenant" />
        <Galat pesan={t("Peran Anda tidak mengizinkan membuka halaman ini.")} />
      </div>
    );
  }

  return (
    <div>
      <JudulHalaman
        judul={t("Folder|tab")}
        konteks="tenant"
        aksi={
          boleh("folders.create") ? (
            <Button variant="primary" onClick={() => setBaruDi(null)}>
              <FolderPlus size={15} className="mr-1.5" />
              {t("Folder baru")}
            </Button>
          ) : null
        }
      />

      <Galat pesan={galat || (folders.isError ? errorText(folders.error) : "")} className="mb-4" />

      <Card>
        <DataTable
          data={baris}
          kunci={(f) => f.id}
          perHalaman={0}
          onBuka={(f) => buka(f.id)}
          kosong={folders.isLoading ? "Memuat..." : "Belum ada folder."}
          kolom={[
            {
              judul: "Nama",
              sel: (f) => (
                <div style={{ paddingLeft: f.kedalaman * 18 }}>
                  <p className="font-medium text-ink">
                    {f.name}
                    {f.isDefault ? (
                      <span className="ml-2 rounded-full bg-brandSoft px-2 py-0.5 text-[11px] font-medium text-brand">
                        {t("bawaan")}
                      </span>
                    ) : null}
                  </p>
                  {f.description ? <p className="text-xs text-muted">{tp(f.description)}</p> : null}
                </div>
              ),
            },
            { judul: "Proses", sel: (f) => <span className="tabular-nums">{f.processCount}</span> },
            { judul: "Antrean", sel: (f) => <span className="tabular-nums">{f.queueCount}</span> },
            { judul: "Aset", sel: (f) => <span className="tabular-nums">{f.assetCount}</span> },
            { judul: "Pengguna", sel: (f) => <span className="tabular-nums">{f.userCount}</span> },
            { judul: "Robot", sel: (f) => <span className="tabular-nums">{f.robotCount}</span> },
            { judul: "Dibuat", sel: (f) => <span className="text-muted">{dateTimeOf(f.createdAt)}</span> },
            {
              judul: "",
              sel: (f) => {
                const alasan = alasanTakBisaHapus(f);

                return (
                  <div className="flex justify-end gap-0.5">
                    <IconButton label={t("Buka folder ini")} onClick={() => buka(f.id)}>
                      <ArrowRight size={16} />
                    </IconButton>
                    {boleh("folders.create") ? (
                      <IconButton label={t("Subfolder baru")} onClick={() => setBaruDi(f.id)}>
                        <FolderPlus size={16} />
                      </IconButton>
                    ) : null}
                    {boleh("folders.update") ? (
                      <IconButton label={t("Ubah")} onClick={() => setSunting(f)}>
                        <Pencil size={15} />
                      </IconButton>
                    ) : null}
                    {boleh("folders.delete") ? (
                      <IconButton
                        label={alasan ?? t("Hapus")}
                        tone="danger"
                        disabled={!!alasan}
                        onClick={() => {
                          if (window.confirm(t("Hapus folder \"{0}\"? Riwayat pekerjaannya pindah ke induknya.", f.name))) {
                            hapus.mutate(f.id);
                          }
                        }}
                      >
                        <Trash2 size={16} />
                      </IconButton>
                    ) : null}
                  </div>
                );
              },
            },
          ]}
        />
      </Card>

      <Card className="mt-5">
        <CardHeader
          title={t("Folder Saya milik pengguna")}
          subtitle={t("Folder pribadi hanya terlihat oleh pemiliknya. Yang kosong ikut terhapus saat penggunanya dihapus.")}
        />
        <DataTable
          data={pribadi}
          kunci={(f) => f.id}
          perHalaman={0}
          kosong="Belum ada yang membuka Folder Saya."
          kolom={[
            {
              judul: "Pemilik",
              sel: (f) => (
                <span className="flex items-center gap-2 font-medium">
                  <FolderLock size={14} className="text-muted" />@{f.ownerUsername ?? "?"}
                </span>
              ),
            },
            { judul: "Proses", sel: (f) => <span className="tabular-nums">{f.processCount}</span> },
            { judul: "Aset", sel: (f) => <span className="tabular-nums">{f.assetCount}</span> },
            { judul: "Robot", sel: (f) => <span className="tabular-nums">{f.robotCount}</span> },
            { judul: "Dibuat", sel: (f) => <span className="text-muted">{dateTimeOf(f.createdAt)}</span> },
            {
              judul: "",
              sel: (f) => {
                const kosong = isi(f) === 0;

                return !boleh("folders.delete") ? null : (
                  <div className="flex justify-end">
                    <IconButton
                      label={kosong ? t("Hapus") : t("Masih berisi proses, pemicu, antrean, aset, atau ember.")}
                      tone="danger"
                      disabled={!kosong}
                      onClick={() => {
                        if (window.confirm(t("Hapus Folder Saya milik \"{0}\"?", f.ownerUsername ?? "?"))) {
                          hapus.mutate(f.id);
                        }
                      }}
                    >
                      <Trash2 size={16} />
                    </IconButton>
                  </div>
                );
              },
            },
          ]}
        />
      </Card>

      {sunting ? <DialogFolder awal={sunting} onTutup={tutup} /> : null}
      {baruDi !== undefined ? <DialogFolder indukAwal={baruDi} onTutup={tutup} /> : null}
    </div>
  );
}
