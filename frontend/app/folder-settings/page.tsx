"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import { Eye, FolderMinus, FolderPlus, Pencil, Plus, Trash2, UserMinus, X } from "lucide-react";
import { OpenOrchestratorApi, errorText, type FolderAnggota, type FolderNode, type MesinFolder } from "@/lib/api";
import { useFolder } from "@/lib/folder";
import { useT } from "@/lib/i18n";
import { useIzin } from "@/lib/izin";
import { cn, dateTimeOf } from "@/lib/utils";
import { Badge, Button, Card, CardHeader, Galat, IconButton, labelKeadaanMesin } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { Dialog, kelasIsian } from "@/components/Dialog";
import { DialogFolder } from "@/components/DialogFolder";
import { DialogKonfirmasi } from "@/components/DialogKonfirmasi";
import { JudulHalaman, PerluFolder } from "@/components/HalamanFolder";
import { MenuAksi } from "@/components/MenuAksi";
import { useNotifikasi } from "@/components/Notifikasi";

type Tab = "umum" | "robot" | "mesin" | "pengguna";

/**
 * Setelan folder yang sedang dibuka: namanya, dan siapa yang bekerja di sana —
 * robot yang mengambil pekerjaannya, mesin tempat pekerjaannya boleh
 * berjalan, dan pengguna yang boleh melihatnya.
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
    if (diminta === "robot" || diminta === "mesin" || diminta === "pengguna" || diminta === "umum") setTab(diminta);
  }, []);

  const anggota = useQuery({
    queryKey: ["folderMembers", folder.id],
    queryFn: () => OpenOrchestratorApi.folderMembers(folder.id),
  });

  const mesin = useQuery({
    queryKey: ["folderMachines", folder.id],
    queryFn: () => OpenOrchestratorApi.folderMachines(folder.id),
    // Status mesin berubah tanpa ada yang mengubah apa pun di halaman ini.
    refetchInterval: tab === "mesin" ? 15_000 : false,
  });

  const tabs: { kode: Tab; label: string }[] = [
    { kode: "umum", label: "Umum" },
    { kode: "robot", label: "Robot" },
    { kode: "mesin", label: "Mesin" },
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
            {x.kode === "mesin" && mesin.data ? ` (${mesin.data.length})` : ""}
            {x.kode === "pengguna" && anggota.data ? ` (${anggota.data.users.length})` : ""}
          </button>
        ))}
      </nav>

      <Galat pesan={anggota.isError ? errorText(anggota.error) : ""} className="mb-4" />

      {tab === "umum" ? <TabUmum folder={folder} /> : null}
      {tab === "robot" && anggota.data ? <TabRobot folder={folder} anggota={anggota.data} /> : null}
      {tab === "mesin" ? (
        <>
          <Galat pesan={mesin.isError ? errorText(mesin.error) : ""} className="mb-4" />
          {anggota.data && mesin.data ? <TabMesin folder={folder} anggota={anggota.data} mesin={mesin.data} /> : null}
        </>
      ) : null}
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
          <p>{t("Pekerjaan folder ini hanya berjalan di mesin yang terdaftar di tab Mesin.")}</p>
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
  const beritahu = useNotifikasi();

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

  // Robot yang mesinnya belum terdaftar tidak mengambil job folder ini (V12):
  // mendaftarkannya cukup satu klik dari sini.
  const daftarkan = useMutation({
    mutationFn: (machineId: string) => OpenOrchestratorApi.addFolderMachines(folder.id, [machineId]),
    onSuccess: () => {
      segarkanMesinFolder(klien, folder.id);
      beritahu(t("Machine berhasil ditambahkan ke Folder."));
    },
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
            {
              judul: "Mesin|satu",
              sel: (r) => (
                <div>
                  <span className="text-muted">{r.machineName ?? "-"}</span>
                  {r.machineId && r.machineRegistered === false ? (
                    <p className="mt-0.5 flex flex-wrap items-center gap-x-2 text-xs text-warn">
                      <span>{t("Mesin ini belum terdaftar di folder ini; robot ini tidak mengambil job folder ini.")}</span>
                      {anggota.canManageMachines ? (
                        <button
                          type="button"
                          disabled={daftarkan.isPending}
                          onClick={() => daftarkan.mutate(r.machineId!)}
                          className="font-medium text-brand underline underline-offset-2 disabled:opacity-60"
                        >
                          {t("Daftarkan mesin")}
                        </button>
                      ) : null}
                    </p>
                  ) : null}
                </div>
              ),
            },
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
// Mesin
// ---------------------------------------------------------------------

/** Pendaftaran mesin mengubah apa yang bisa dipilih di Start Job dan angka di Beranda. */
function segarkanMesinFolder(klien: QueryClient, folderId: string) {
  klien.invalidateQueries({ queryKey: ["folderMachines", folderId] });
  klien.invalidateQueries({ queryKey: ["folderAvailableMachines", folderId] });
  klien.invalidateQueries({ queryKey: ["folderMembers", folderId] });
  klien.invalidateQueries({ queryKey: ["start-options"] });
  klien.invalidateQueries({ queryKey: ["process-machines"] });
  klien.invalidateQueries({ queryKey: ["machines"] });
  klien.invalidateQueries({ queryKey: ["dashboard"] });
}

/** "Production 2 · Testing 1"; kosong kalau mesinnya tanpa runtime. */
function rincianRuntime(runtimes: Record<string, number>, t: (teks: string) => string): string {
  return Object.entries(runtimes)
    .filter(([, n]) => n > 0)
    .map(([tipe, n]) => `${t(tipe)} ${n}`)
    .join(" · ");
}

function totalRuntime(runtimes: Record<string, number>): number {
  return Object.values(runtimes).reduce((a, b) => a + b, 0);
}

/**
 * Mesin yang terdaftar di folder ini. Hanya mesin ini yang menjalankan job
 * folder ini — robot folder ini di mesin lain tidak mengambilnya.
 * "Hapus dari Folder" hanya memutus pendaftarannya; mesinnya sendiri tetap
 * ada, dan dihapus (kalau perlu) dari halaman Mesin.
 */
function TabMesin({ folder, anggota, mesin }: { folder: FolderNode; anggota: FolderAnggota; mesin: MesinFolder[] }) {
  const { t } = useT();
  const klien = useQueryClient();
  const beritahu = useNotifikasi();
  const bolehAtur = anggota.canManageMachines;
  const namaFolder = folder.personal ? t("Folder Saya") : folder.name;

  const [tambah, setTambah] = useState(false);
  const [lihat, setLihat] = useState<MesinFolder | null>(null);
  const [keluarkan, setKeluarkan] = useState<MesinFolder | null>(null);
  const [galatHapus, setGalatHapus] = useState("");

  const hapus = useMutation({
    mutationFn: (m: MesinFolder) => OpenOrchestratorApi.removeFolderMachine(folder.id, m.id),
    onSuccess: () => {
      setKeluarkan(null);
      segarkanMesinFolder(klien, folder.id);
      beritahu(t("Machine berhasil dihapus dari Folder."));
    },
    onError: (e) => setGalatHapus(errorText(e)),
  });

  return (
    <div className="space-y-4">
      {bolehAtur ? (
        <div>
          <Button variant="primary" onClick={() => setTambah(true)}>
            <Plus size={14} className="mr-1.5" />
            {t("Tambah Mesin")}
          </Button>
        </div>
      ) : null}

      <Card>
        <DataTable
          data={mesin}
          kunci={(m) => m.id}
          perHalaman={0}
          onBuka={(m) => setLihat(m)}
          kosong="Belum ada mesin yang terdaftar di folder ini. Job folder ini akan menunggu sampai ada."
          kolom={[
            { judul: "Nama Mesin", sel: (m) => <span className="font-medium">{m.name}</span>, urut: (m) => m.name.toLowerCase() },
            { judul: "Nama Host", sel: (m) => <span className="text-muted">{m.hostname ?? "-"}</span>, urut: (m) => m.hostname ?? "" },
            { judul: "Tipe", sel: (m) => m.type, urut: (m) => m.type },
            { judul: "Status|mesin", sel: (m) => <Badge value={m.status} />, urut: (m) => m.status },
            {
              judul: "Runtime",
              sel: (m) => {
                const rincian = rincianRuntime(m.runtimes, t);
                return (
                  <span title={rincian || undefined}>
                    <span className="font-medium">{totalRuntime(m.runtimes)}</span>
                    {rincian ? <span className="ml-1.5 text-xs text-muted">{rincian}</span> : null}
                  </span>
                );
              },
              urut: (m) => totalRuntime(m.runtimes),
            },
            {
              judul: "",
              sel: (m) => (
                <div className="flex justify-end">
                  <MenuAksi
                    label={t("Aksi untuk {0}", m.name)}
                    item={[
                      { label: t("Lihat"), ikon: Eye, onPilih: () => setLihat(m) },
                      { pemisah: true },
                      {
                        label: t("Hapus dari Folder"),
                        ikon: FolderMinus,
                        bahaya: true,
                        nonaktif: !bolehAtur,
                        petunjuk: bolehAtur ? undefined : t("Hanya pengelola folder yang bisa mengatur mesin folder ini."),
                        onPilih: () => {
                          setGalatHapus("");
                          setKeluarkan(m);
                        },
                      },
                    ]}
                  />
                </div>
              ),
            },
          ]}
        />
      </Card>

      <p className="text-xs text-muted">
        {t("Mesin baru selalu terdaftar di folder Shared. Subfolder baru mewarisi mesin induknya saat dibuat. Job yang sedang berjalan tidak dihentikan saat mesinnya dihapus dari folder.")}
      </p>

      {tambah ? <DialogTambahMesin folder={folder} onTutup={() => setTambah(false)} /> : null}
      {lihat ? <DialogLihatMesin mesin={lihat} onTutup={() => setLihat(null)} /> : null}
      {keluarkan ? (
        <DialogKonfirmasi
          judul={t("Hapus dari Folder")}
          label={t("Hapus|dari folder")}
          bahaya
          sibuk={hapus.isPending}
          galat={galatHapus}
          onYa={() => hapus.mutate(keluarkan)}
          onTutup={() => setKeluarkan(null)}
        >
          <p>{t("Apakah Anda yakin ingin menghapus Machine {0} dari Folder {1}?", keluarkan.name, namaFolder)}</p>
          <p className="text-muted">
            {t("Machine tidak akan dihapus dari sistem. Machine hanya tidak lagi tersedia pada Folder ini.")}
          </p>
        </DialogKonfirmasi>
      ) : null}
    </div>
  );
}

/**
 * Daftarkan mesin ke folder ini — satu atau beberapa sekaligus. Yang
 * ditawarkan hanya mesin yang belum terdaftar dan tidak dinonaktifkan; di
 * Folder Saya, hanya mesin tempat robot pemiliknya bekerja (aturan server).
 */
function DialogTambahMesin({ folder, onTutup }: { folder: FolderNode; onTutup: () => void }) {
  const { t } = useT();
  const klien = useQueryClient();
  const beritahu = useNotifikasi();
  const { boleh } = useIzin();

  const [cari, setCari] = useState("");
  const [pilih, setPilih] = useState<Set<string>>(new Set());
  const [galat, setGalat] = useState("");

  const tersedia = useQuery({
    queryKey: ["folderAvailableMachines", folder.id],
    queryFn: () => OpenOrchestratorApi.availableFolderMachines(folder.id),
  });

  const kata = cari.trim().toLowerCase();
  const daftar = (tersedia.data ?? []).filter(
    (m) => !kata || m.name.toLowerCase().includes(kata) || (m.hostname ?? "").toLowerCase().includes(kata),
  );
  // Yang terpilih tapi sudah tidak ditawarkan (data disegarkan) tidak ikut dikirim.
  const terpilih = (tersedia.data ?? []).filter((m) => pilih.has(m.id)).map((m) => m.id);
  const semuaTerpilih = daftar.length > 0 && daftar.every((m) => pilih.has(m.id));

  const simpan = useMutation({
    mutationFn: () => OpenOrchestratorApi.addFolderMachines(folder.id, terpilih),
    onSuccess: () => {
      segarkanMesinFolder(klien, folder.id);
      beritahu(t("Machine berhasil ditambahkan ke Folder."));
      onTutup();
    },
    onError: (e) => setGalat(errorText(e)),
  });

  function alih(id: string) {
    const baru = new Set(pilih);
    if (baru.has(id)) baru.delete(id);
    else baru.add(id);
    setPilih(baru);
  }

  function alihSemua() {
    const baru = new Set(pilih);
    for (const m of daftar) {
      if (semuaTerpilih) baru.delete(m.id);
      else baru.add(m.id);
    }
    setPilih(baru);
  }

  return (
    <Dialog
      judul={t("Tambah Mesin ke Folder")}
      terbuka
      onTutup={onTutup}
      lebar="max-w-2xl"
      aksi={
        <>
          <Button onClick={onTutup}>{t("Batal")}</Button>
          <Button variant="primary" disabled={terpilih.length === 0 || simpan.isPending} onClick={() => simpan.mutate()}>
            {simpan.isPending
              ? t("Menyimpan...")
              : terpilih.length > 1
                ? t("Tambah {0} Mesin", terpilih.length)
                : t("Tambah Mesin")}
          </Button>
        </>
      }
    >
      <p className="mb-3 text-sm text-muted">
        {t("Folder: {0}", folder.personal ? t("Folder Saya") : folder.name)}
      </p>

      {(tersedia.data?.length ?? 0) > 6 ? (
        <input
          type="search"
          value={cari}
          onChange={(e) => setCari(e.target.value)}
          placeholder={t("Cari mesin...")}
          aria-label={t("Cari mesin...")}
          className={cn(kelasIsian, "mb-3")}
        />
      ) : null}

      {tersedia.isLoading ? <p className="text-sm text-muted">{t("Memuat...")}</p> : null}
      <Galat pesan={tersedia.isError ? errorText(tersedia.error) : ""} className="mb-3" />

      {tersedia.isSuccess && tersedia.data.length === 0 ? (
        <p className="text-sm text-muted">
          {folder.personal
            ? t("Tidak ada mesin yang bisa ditambahkan. Di Folder Saya hanya bisa didaftarkan mesin tempat robot Anda sendiri bekerja.")
            : t("Tidak ada mesin yang bisa ditambahkan: semua mesin sudah terdaftar di folder ini atau dinonaktifkan.")}
        </p>
      ) : null}

      {daftar.length > 0 ? (
        <fieldset>
          <legend className="sr-only">{t("Mesin yang tersedia")}</legend>
          <label className="mb-1 flex cursor-pointer items-center gap-3 border-b border-line px-1 pb-2 text-sm text-muted">
            <input
              type="checkbox"
              checked={semuaTerpilih}
              onChange={alihSemua}
              aria-label={t("Pilih semua ({0})", daftar.length)}
              className="h-4 w-4 accent-brand"
            />
            {t("Pilih semua ({0})", daftar.length)}
          </label>
          <ul className="max-h-80 overflow-y-auto">
            {daftar.map((m) => {
              const rincian = rincianRuntime(m.runtimes, t);
              return (
                <li key={m.id}>
                  <label className="flex cursor-pointer items-start gap-3 rounded-lg px-1 py-2 hover:bg-slate-50">
                    <input
                      type="checkbox"
                      checked={pilih.has(m.id)}
                      onChange={() => alih(m.id)}
                      aria-label={m.name}
                      className="mt-0.5 h-4 w-4 shrink-0 accent-brand"
                    />
                    <span className="min-w-0 flex-1">
                      <span className="flex flex-wrap items-center gap-2">
                        <span className="font-medium text-ink">{m.name}</span>
                        <Badge value={m.status} />
                      </span>
                      <span className="mt-0.5 block text-xs text-muted">
                        {[m.hostname, m.type, rincian || t("Tanpa runtime"),
                          m.folderRobots > 0 ? t("{0} robot folder ini", m.folderRobots) : null]
                          .filter(Boolean)
                          .join(" · ")}
                      </span>
                    </span>
                  </label>
                </li>
              );
            })}
          </ul>
        </fieldset>
      ) : null}

      {tersedia.isSuccess && tersedia.data.length > 0 && daftar.length === 0 ? (
        <p className="text-sm text-muted">{t("Tidak ada mesin yang cocok.")}</p>
      ) : null}

      {boleh("machines.create") ? (
        <Link href="/tenant/robots/machines" className="mt-3 inline-block text-sm font-medium text-brand underline underline-offset-2">
          {t("Buat mesin baru")}
        </Link>
      ) : null}

      <Galat pesan={galat} className="mt-3" />
    </Dialog>
  );
}

/** Rincian satu mesin folder ini — untuk semua yang boleh membuka folder, tanpa izin halaman Mesin. */
function DialogLihatMesin({ mesin, onTutup }: { mesin: MesinFolder; onTutup: () => void }) {
  const { t } = useT();
  const { boleh } = useIzin();
  const rincian = rincianRuntime(mesin.runtimes, t);

  return (
    <Dialog
      judul={mesin.name}
      terbuka
      onTutup={onTutup}
      aksi={<Button onClick={onTutup}>{t("Tutup")}</Button>}
    >
      <dl className="grid gap-4 sm:grid-cols-2">
        <Medan label={t("Nama Host")} nilai={mesin.hostname ?? "-"} />
        <Medan label={t("Tipe")} nilai={mesin.type} />
        <div>
          <dt className="text-xs text-muted">{t("Status|mesin")}</dt>
          <dd className="mt-1">
            <Badge value={mesin.status} />
          </dd>
        </div>
        <Medan label={t("Keadaan")} nilai={t(labelKeadaanMesin(mesin.state))} />
        <Medan label={t("Runtime")} nilai={rincian ? `${totalRuntime(mesin.runtimes)} · ${rincian}` : t("Tanpa runtime")} />
        <Medan label={t("Robot folder ini di mesin ini")} nilai={String(mesin.folderRobots)} />
        <Medan
          label={t("Didaftarkan")}
          nilai={mesin.assignedAt ? `${dateTimeOf(mesin.assignedAt)}${mesin.assignedBy ? ` · ${mesin.assignedBy}` : ""}` : "-"}
        />
      </dl>

      {boleh("machines.read") ? (
        <Link
          href={`/tenant/robots/machines?ubah=${encodeURIComponent(mesin.name)}`}
          className="mt-4 inline-block text-sm font-medium text-brand underline underline-offset-2"
        >
          {t("Buka konfigurasi mesin ini")}
        </Link>
      ) : null}
    </Dialog>
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
