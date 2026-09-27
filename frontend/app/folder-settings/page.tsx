"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FolderPlus, Pencil, Trash2, UserMinus, X } from "lucide-react";
import { OpenOrchestratorApi, errorText, type FolderAnggota, type FolderNode } from "@/lib/api";
import { useFolder } from "@/lib/folder";
import { useT } from "@/lib/i18n";
import { useIzin } from "@/lib/izin";
import { cn, dateTimeOf } from "@/lib/utils";
import { Badge, Button, Card, CardHeader, Galat, IconButton } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { kelasIsian } from "@/components/Dialog";
import { DialogFolder } from "@/components/DialogFolder";
import { JudulHalaman, PerluFolder } from "@/components/HalamanFolder";

type Tab = "umum" | "robot" | "pengguna";

/**
 * Setelan folder yang sedang dibuka: namanya, dan siapa yang bekerja di sana —
 * robot yang mengambil pekerjaannya, dan pengguna yang boleh melihatnya.
 */
export default function SetelanFolder() {
  return <PerluFolder>{(folder) => <IsiSetelan folder={folder} />}</PerluFolder>;
}

function IsiSetelan({ folder }: { folder: FolderNode }) {
  const { t } = useT();
  const [tab, setTab] = useState<Tab>("umum");

  // Kartu "Users" dan "Machines" di Beranda membuka tab yang sesuai lewat
  // ?tab=. Dibaca sekali lewat window: useSearchParams menuntut pembungkus
  // Suspense di seluruh halaman hanya untuk satu nilai awal ini.
  useEffect(() => {
    const diminta = new URLSearchParams(window.location.search).get("tab");
    if (diminta === "robot" || diminta === "pengguna" || diminta === "umum") setTab(diminta);
  }, []);

  const anggota = useQuery({
    queryKey: ["folderMembers", folder.id],
    queryFn: () => OpenOrchestratorApi.folderMembers(folder.id),
  });

  const tabs: { kode: Tab; label: string }[] = [
    { kode: "umum", label: "Umum" },
    { kode: "robot", label: "Robot" },
    // Folder Saya hanya milik pemiliknya: tidak ada pengguna lain untuk ditugaskan.
    ...(folder.personal ? [] : [{ kode: "pengguna" as Tab, label: "Pengguna" }]),
  ];

  return (
    <div>
      <JudulHalaman judul={t("Setelan")} />

      <nav aria-label={t("Bagian")} className="mb-5 flex gap-1 border-b border-line">
        {tabs.map((x) => (
          <button
            key={x.kode}
            type="button"
            onClick={() => setTab(x.kode)}
            aria-current={tab === x.kode ? "page" : undefined}
            className={cn(
              "-mb-px whitespace-nowrap border-b-2 px-3 py-2 text-sm transition",
              tab === x.kode
                ? "border-brand font-medium text-brand"
                : "border-transparent text-muted hover:border-slate-300 hover:text-ink",
            )}
          >
            {t(x.label)}
            {x.kode === "robot" && anggota.data ? ` (${anggota.data.robots.length})` : ""}
            {x.kode === "pengguna" && anggota.data ? ` (${anggota.data.users.length})` : ""}
          </button>
        ))}
      </nav>

      <Galat pesan={anggota.isError ? errorText(anggota.error) : ""} className="mb-4" />

      {tab === "umum" ? <TabUmum folder={folder} /> : null}
      {tab === "robot" && anggota.data ? <TabRobot folder={folder} anggota={anggota.data} /> : null}
      {tab === "pengguna" && anggota.data && !folder.personal ? (
        <TabPengguna folder={folder} anggota={anggota.data} />
      ) : null}
    </div>
  );
}

// ---------------------------------------------------------------------
// Umum
// ---------------------------------------------------------------------

function TabUmum({ folder }: { folder: FolderNode }) {
  const { t, tp } = useT();
  const router = useRouter();
  const klien = useQueryClient();
  const { namaJalur, pilih } = useFolder();
  const { boleh } = useIzin();

  const [dialog, setDialog] = useState<"ubah" | "anak" | null>(null);
  const [galat, setGalat] = useState("");

  const tutup = useCallback(() => setDialog(null), []);

  const hapus = useMutation({
    mutationFn: () => OpenOrchestratorApi.deleteFolder(folder.id),
    onSuccess: async () => {
      await klien.invalidateQueries({ queryKey: ["folders"] });
      router.push("/");
    },
    onError: (e) => setGalat(errorText(e)),
  });

  const jenis = folder.isDefault ? "Folder bawaan" : folder.personal ? "Folder pribadi" : "Folder bersama";

  return (
    <div className="grid grid-cols-1 gap-5 xl:grid-cols-3">
      <Card className="xl:col-span-2">
        <CardHeader
          title={t("Folder ini")}
          action={
            !folder.personal && (boleh("folders.update") || boleh("folders.create")) ? (
              <div className="flex gap-1">
                {boleh("folders.update") ? (
                  <Button onClick={() => setDialog("ubah")}>
                    <Pencil size={14} className="mr-1.5" />
                    {t("Ubah")}
                  </Button>
                ) : null}
                {boleh("folders.create") ? (
                  <Button onClick={() => setDialog("anak")}>
                    <FolderPlus size={14} className="mr-1.5" />
                    {t("Subfolder baru")}
                  </Button>
                ) : null}
              </div>
            ) : undefined
          }
        />
        <dl className="grid gap-4 p-5 sm:grid-cols-2">
          <Medan label={t("Nama")} nilai={folder.personal ? t("Folder Saya") : folder.name} />
          <Medan label={t("Jenis")} nilai={t(jenis)} />
          <Medan label={t("Jalur")} nilai={namaJalur(folder.id)} />
          <Medan label={t("Dibuat")} nilai={dateTimeOf(folder.createdAt)} />
          <div className="sm:col-span-2">
            <Medan label={t("Keterangan")} nilai={tp(folder.description) || "-"} />
          </div>
        </dl>
      </Card>

      <Card>
        <CardHeader title={t("Tentang folder")} />
        <div className="space-y-2 p-5 text-sm text-muted">
          <p>{t("Proses, pemicu, antrean, aset, dan ember penyimpanan tinggal di satu folder.")}</p>
          <p>{t("Robot hanya mengambil pekerjaan dari folder tempat ia ditugaskan, kecuali pekerjaan yang menyebut namanya langsung.")}</p>
          <p>{t("Nama proses dan pemicu cukup unik di dalam folder. Nama antrean, aset, dan ember tetap unik untuk seluruh penyewa, karena robot memanggilnya lewat nama.")}</p>
        </div>

        {boleh("folders.delete") && !folder.isDefault ? (
          <div className="border-t border-line p-5">
            <Button
              className="text-danger"
              disabled={hapus.isPending}
              onClick={() => {
                if (window.confirm(t("Hapus folder \"{0}\"? Folder harus sudah kosong; riwayat pekerjaannya pindah ke induknya.", folder.personal ? t("Folder Saya") : folder.name))) {
                  hapus.mutate();
                }
              }}
            >
              <Trash2 size={14} className="mr-1.5" />
              {t("Hapus folder")}
            </Button>
            <Galat pesan={galat} className="mt-3" />
          </div>
        ) : null}
      </Card>

      {dialog === "ubah" ? <DialogFolder awal={folder} onTutup={tutup} /> : null}
      {dialog === "anak" ? <DialogFolder indukAwal={folder.id} onTutup={tutup} onSelesai={(id) => pilih(id)} /> : null}
    </div>
  );
}

function Medan({ label, nilai }: { label: string; nilai: string }) {
  return (
    <div>
      <dt className="text-xs text-muted">{label}</dt>
      <dd className="mt-0.5 break-words font-medium text-ink">{nilai}</dd>
    </div>
  );
}

// ---------------------------------------------------------------------
// Robot
// ---------------------------------------------------------------------

function TabRobot({ folder, anggota }: { folder: FolderNode; anggota: FolderAnggota }) {
  const { t } = useT();
  const klien = useQueryClient();

  const [pilihan, setPilihan] = useState("");
  const [galat, setGalat] = useState("");

  const semua = useQuery({
    queryKey: ["robots", "semua"],
    queryFn: () => OpenOrchestratorApi.robots(),
    enabled: anggota.canManageRobots,
  });

  const sudah = new Set(anggota.robots.map((r) => r.name));
  const tersedia = (semua.data ?? []).filter((r) => !sudah.has(r.name));

  function segarkan() {
    klien.invalidateQueries({ queryKey: ["folderMembers", folder.id] });
    klien.invalidateQueries({ queryKey: ["robots"] });
    klien.invalidateQueries({ queryKey: ["dashboard"] });
  }

  const tugaskan = useMutation({
    mutationFn: (nama: string) => OpenOrchestratorApi.assignRobot(folder.id, nama),
    onSuccess: () => {
      setPilihan("");
      segarkan();
    },
    onError: (e) => setGalat(errorText(e)),
  });

  const lepas = useMutation({
    mutationFn: (nama: string) => OpenOrchestratorApi.unassignRobot(folder.id, nama),
    onSuccess: segarkan,
    onError: (e) => setGalat(errorText(e)),
  });

  return (
    <div className="space-y-4">
      {anggota.canManageRobots ? (
        <div className="flex flex-wrap items-center gap-2">
          <select
            value={pilihan}
            onChange={(e) => setPilihan(e.target.value)}
            aria-label={t("Robot|satu")}
            className={cn(kelasIsian, "w-64")}
          >
            <option value="">{t("Pilih robot...")}</option>
            {tersedia.map((r) => (
              <option key={r.name} value={r.name}>
                {`${r.name}${r.machineName ? ` (${r.machineName})` : ""}`}
              </option>
            ))}
          </select>
          <Button variant="primary" disabled={!pilihan || tugaskan.isPending} onClick={() => tugaskan.mutate(pilihan)}>
            {t("Tugaskan robot")}
          </Button>
        </div>
      ) : null}

      <Galat pesan={galat} />

      <Card>
        <DataTable
          data={anggota.robots}
          kunci={(r) => r.name}
          perHalaman={0}
          kosong="Belum ada robot yang ditugaskan ke folder ini. Pekerjaannya akan menunggu sampai ada."
          kolom={[
            { judul: "Robot|satu", sel: (r) => <span className="font-medium">{r.name}</span>, urut: (r) => r.name },
            { judul: "Mesin|satu", sel: (r) => <span className="text-muted">{r.machineName ?? "-"}</span> },
            { judul: "Tipe", sel: (r) => r.type },
            { judul: "Ditugaskan", sel: (r) => <span className="text-muted">{dateTimeOf(r.assignedAt)}</span> },
            {
              judul: "",
              sel: (r) =>
                anggota.canManageRobots ? (
                  <div className="flex justify-end">
                    <IconButton
                      label={t("Lepas dari folder ini")}
                      tone="danger"
                      onClick={() => {
                        if (window.confirm(t("Lepas robot \"{0}\" dari folder ini? Robot itu tidak akan mengambil pekerjaan folder ini lagi.", r.name))) {
                          lepas.mutate(r.name);
                        }
                      }}
                    >
                      <X size={16} />
                    </IconButton>
                  </div>
                ) : null,
            },
          ]}
        />
      </Card>

      <p className="text-xs text-muted">
        {t("Robot baru yang tersambung untuk pertama kali selalu masuk ke folder Shared. Subfolder baru mewarisi robot induknya saat dibuat.")}
      </p>
    </div>
  );
}

// ---------------------------------------------------------------------
// Pengguna
// ---------------------------------------------------------------------

function TabPengguna({ folder, anggota }: { folder: FolderNode; anggota: FolderAnggota }) {
  const { t } = useT();
  const klien = useQueryClient();

  const [pilihan, setPilihan] = useState("");
  const [galat, setGalat] = useState("");

  const semua = useQuery({ queryKey: ["users"], queryFn: OpenOrchestratorApi.users, enabled: anggota.canManageUsers });

  const sudah = new Set(anggota.users.map((u) => u.username));
  const tersedia = (semua.data ?? []).filter((u) => !sudah.has(u.username));

  function segarkan() {
    klien.invalidateQueries({ queryKey: ["folderMembers", folder.id] });
    klien.invalidateQueries({ queryKey: ["users"] });
    klien.invalidateQueries({ queryKey: ["dashboard"] });
  }

  const tugaskan = useMutation({
    mutationFn: (username: string) => OpenOrchestratorApi.assignUser(folder.id, username),
    onSuccess: () => {
      setPilihan("");
      segarkan();
    },
    onError: (e) => setGalat(errorText(e)),
  });

  const lepas = useMutation({
    mutationFn: (username: string) => OpenOrchestratorApi.unassignUser(folder.id, username),
    onSuccess: segarkan,
    onError: (e) => setGalat(errorText(e)),
  });

  return (
    <div className="space-y-4">
      {anggota.canManageUsers ? (
        <div className="flex flex-wrap items-center gap-2">
          <select
            value={pilihan}
            onChange={(e) => setPilihan(e.target.value)}
            aria-label={t("Pengguna")}
            className={cn(kelasIsian, "w-64")}
          >
            <option value="">{t("Pilih pengguna...")}</option>
            {tersedia.map((u) => (
              <option key={u.username} value={u.username}>
                {`${u.displayName} (@${u.username})`}
              </option>
            ))}
          </select>
          <Button variant="primary" disabled={!pilihan || tugaskan.isPending} onClick={() => tugaskan.mutate(pilihan)}>
            {t("Tugaskan pengguna")}
          </Button>
        </div>
      ) : null}

      <Galat pesan={galat} />

      <Card>
        <DataTable
          data={anggota.users}
          kunci={(u) => u.username}
          perHalaman={0}
          kosong="Belum ada pengguna yang ditugaskan ke folder ini."
          kolom={[
            {
              judul: "Nama pengguna",
              sel: (u) => <span className="font-medium">{u.username}</span>,
              urut: (u) => u.username,
            },
            { judul: "Nama", sel: (u) => u.displayName, urut: (u) => u.displayName },
            { judul: "Peran", sel: (u) => <Badge value={u.role.toUpperCase()} label={u.role} /> },
            {
              judul: "Status",
              sel: (u) => (
                <Badge value={u.isActive ? "AVAILABLE" : "STOPPED"} label={t(u.isActive ? "Aktif" : "Nonaktif")} />
              ),
            },
            { judul: "Ditugaskan", sel: (u) => <span className="text-muted">{dateTimeOf(u.assignedAt)}</span> },
            {
              judul: "",
              sel: (u) =>
                anggota.canManageUsers ? (
                  <div className="flex justify-end">
                    <IconButton
                      label={t("Lepas dari folder ini")}
                      tone="danger"
                      onClick={() => {
                        if (window.confirm(t("Lepas \"{0}\" dari folder ini? Ia tidak akan melihat folder ini lagi.", u.username))) {
                          lepas.mutate(u.username);
                        }
                      }}
                    >
                      <UserMinus size={16} />
                    </IconButton>
                  </div>
                ) : null,
            },
          ]}
        />
      </Card>

      <p className="text-xs text-muted">
        {t("Administrator selalu bisa membuka semua folder bersama. Pengguna lain hanya melihat folder tempat ia ditugaskan.")}
      </p>
    </div>
  );
}
