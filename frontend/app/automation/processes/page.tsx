"use client";

import { useCallback, useMemo, useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FolderInput, Pencil, Play, Trash2, TriangleAlert } from "lucide-react";
import { OpenOrchestratorApi, errorText, type FolderNode, type Process } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { useIzin } from "@/lib/izin";
import { kelompokkanPaket } from "@/lib/paket";
import { cn, dateTimeOf } from "@/lib/utils";
import { Badge, Button, Card, Galat, IconButton } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { Dialog, Isian, kelasIsian } from "@/components/Dialog";
import { DialogPindah } from "@/components/DialogPindah";
import { BilahAlat, PerluFolder } from "@/components/HalamanFolder";

export default function Proses() {
  return <PerluFolder>{(folder) => <IsiProses folder={folder} />}</PerluFolder>;
}

function IsiProses({ folder }: { folder: FolderNode }) {
  const { t } = useT();
  const klien = useQueryClient();
  const { boleh } = useIzin();

  const [detail, setDetail] = useState<Process | null>(null);
  const [sunting, setSunting] = useState<Process | null>(null);
  const [baru, setBaru] = useState(false);
  const [pindah, setPindah] = useState<Process | null>(null);
  const [cari, setCari] = useState("");
  const [galat, setGalat] = useState("");

  const proses = useQuery({
    queryKey: ["processes", folder.id],
    queryFn: () => OpenOrchestratorApi.processes(folder.id),
    // Tombol Jalankan harus hidup lagi SENDIRI begitu pekerjaannya selesai,
    // tanpa orang menekan muat ulang. Selama ada yang berjalan, daftar
    // disegarkan tiap 3 detik; selebihnya cukup tiap 15 detik.
    refetchInterval: (q) => ((q.state.data ?? []).some((p) => p.activeJobs > 0) ? 3_000 : 15_000),
  });

  // Folder tanpa robot menjalankan apa pun menjadi pekerjaan yang menunggu
  // selamanya. Peringatannya ditaruh DI SINI, di tempat tombol Jalankan,
  // bukan baru ketahuan di halaman Pekerjaan.
  const robot = useQuery({
    queryKey: ["robots", folder.id],
    queryFn: () => OpenOrchestratorApi.robots(folder.id),
    enabled: boleh("robots.read"),
  });
  const tanpaRobot = robot.isSuccess && robot.data.length === 0;

  const segarkan = useCallback(() => {
    klien.invalidateQueries({ queryKey: ["processes"] });
    klien.invalidateQueries({ queryKey: ["dashboard"] });
  }, [klien]);

  const hapus = useMutation({
    mutationFn: (nama: string) => OpenOrchestratorApi.deleteProcess(nama, folder.id),
    onSuccess: segarkan,
    onError: (e) => setGalat(errorText(e)),
  });

  const jalankan = useMutation({
    mutationFn: (nama: string) =>
      OpenOrchestratorApi.startJob({ processName: nama, folderId: folder.id, source: "Dashboard" }),
    onMutate: (nama) => {
      setGalat("");

      // Tombolnya langsung mati, tidak menunggu penyegaran berikutnya. Tanpa
      // ini ada jeda beberapa detik saat tombol masih hidup, dan klik kedua
      // di jeda itu menjadwalkan pekerjaan kedua.
      klien.setQueryData<Process[]>(["processes", folder.id], (lama) =>
        lama?.map((p) =>
          p.name === nama ? { ...p, activeJobs: p.activeJobs + 1, activeState: p.activeState ?? "PENDING" } : p,
        ),
      );
    },
    onError: (e) => setGalat(errorText(e)),
    onSettled: () => {
      klien.invalidateQueries({ queryKey: ["jobs"] });
      segarkan();
    },
  });

  const tutupDialog = useCallback(() => {
    setBaru(false);
    setSunting(null);
  }, []);

  const kata = cari.trim().toLowerCase();
  const data = (proses.data ?? []).filter((p) => !kata || p.name.toLowerCase().includes(kata));

  return (
    <div>
      <BilahAlat
        aksi={
          boleh("processes.create") ? (
            <Button variant="primary" onClick={() => setBaru(true)}>
              {t("Tambah proses")}
            </Button>
          ) : null
        }
      >
        <input
          type="search"
          value={cari}
          onChange={(e) => setCari(e.target.value)}
          placeholder={t("Cari proses...")}
          aria-label={t("Cari proses...")}
          className={cn(kelasIsian, "w-56")}
        />
      </BilahAlat>

      {tanpaRobot ? (
        <div className="mb-4 flex flex-wrap items-center gap-2 rounded-lg border border-amber-200 bg-amber-50 px-4 py-2.5 text-sm text-amber-900">
          <TriangleAlert className="h-4 w-4 shrink-0" />
          <span>
            {t("Belum ada robot yang ditugaskan ke folder ini, jadi pekerjaan yang dijalankan di sini akan menunggu.")}
          </span>
          <Link href="/folder-settings?tab=robot" className="font-medium underline underline-offset-2">
            {t("Tugaskan robot")}
          </Link>
        </div>
      ) : null}

      <Galat pesan={galat} className="mb-4" />

      <Card>
        <DataTable
          data={data}
          kunci={(p) => p.name}
          onBuka={setDetail}
          kosong={
            proses.isLoading
              ? "Memuat..."
              : kata
                ? "Tidak ada yang cocok."
                : "Belum ada proses di folder ini. Terbitkan dari Studio — proses baru masuk ke folder Shared — atau tambahkan dari paket yang sudah ada."
          }
          kolom={[
            { judul: "Nama", sel: (p) => <span className="font-medium">{p.name}</span>, urut: (p) => p.name },
            {
              judul: "Paket|satu",
              sel: (p) =>
                p.packageName ? (
                  <span className="text-muted">
                    {p.packageName} <span className="tabular-nums">{p.packageVersion}</span>
                  </span>
                ) : (
                  <span className="text-muted">-</span>
                ),
              urut: (p) => p.packageVersion,
            },
            { judul: "Lingkungan|satu", sel: (p) => p.environment ?? "-", urut: (p) => p.environment },
            {
              judul: "Keadaan",
              sel: (p) => (p.activeState ? <Badge value={p.activeState} /> : <span className="text-muted">-</span>),
              urut: (p) => p.activeState,
            },
            {
              judul: "Pekerjaan",
              sel: (p) => <span className="tabular-nums">{p.jobCount}</span>,
              urut: (p) => p.jobCount,
            },
            {
              judul: "Jalan Terakhir",
              sel: (p) => <span className="text-muted">{dateTimeOf(p.lastRunAt)}</span>,
              urut: (p) => p.lastRunAt,
            },
            {
              judul: "",
              sel: (p) => {
                // Mati selama proses ini masih punya pekerjaan yang belum
                // selesai — menunggu robot, berjalan, atau sedang dihentikan.
                const berjalan = p.activeJobs > 0 || (jalankan.isPending && jalankan.variables === p.name);

                return (
                  <div className="flex justify-end gap-0.5">
                    {boleh("jobs.create") ? (
                      <IconButton
                        label={
                          berjalan
                            ? t("Sedang berjalan ({0}). Bisa dijalankan lagi setelah selesai.", t(p.activeState ? labelSingkat(p.activeState) : "Menunggu"))
                            : t("Jalankan")
                        }
                        tone="ok"
                        disabled={berjalan}
                        onClick={() => jalankan.mutate(p.name)}
                      >
                        <Play size={16} fill="currentColor" />
                      </IconButton>
                    ) : null}
                    {boleh("processes.update") ? (
                      <>
                        <IconButton label={t("Ubah")} onClick={() => setSunting(p)}>
                          <Pencil size={15} />
                        </IconButton>
                        <IconButton label={t("Pindahkan ke folder lain")} onClick={() => setPindah(p)}>
                          <FolderInput size={16} />
                        </IconButton>
                      </>
                    ) : null}
                    {boleh("processes.delete") ? (
                      <IconButton
                        label={t("Hapus")}
                        tone="danger"
                        onClick={() => {
                          if (window.confirm(`${t("Yakin menghapus")} "${p.name}"?`)) hapus.mutate(p.name);
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

      <p className="mt-3 text-xs text-muted">
        {t("Klik ganda pada barisnya untuk melihat riwayat jalan dan catatannya.")}
      </p>

      <DialogProses proses={detail} folderId={folder.id} onTutup={() => setDetail(null)} />

      {baru || sunting ? (
        <DialogSimpanProses
          key={sunting?.name ?? "baru"}
          awal={sunting}
          folder={folder}
          namaDipakai={(proses.data ?? []).map((p) => p.name)}
          onTutup={tutupDialog}
          onSelesai={segarkan}
        />
      ) : null}

      {pindah ? (
        <DialogPindah
          judul={t("Pindahkan proses \"{0}\"", pindah.name)}
          keterangan={t("Pemicu dan riwayat pekerjaannya ikut pindah.")}
          folderSekarang={folder.id}
          onTutup={() => setPindah(null)}
          onPindah={async (tujuan) => {
            await OpenOrchestratorApi.moveProcess(pindah.name, folder.id, tujuan);
            segarkan();
            klien.invalidateQueries({ queryKey: ["triggers"] });
          }}
        />
      ) : null}
    </div>
  );
}

/** Nama keadaan untuk kalimat, bukan untuk lencana. */
function labelSingkat(state: string) {
  return (
    {
      PENDING: "Menunggu",
      RUNNING: "Berjalan",
      STOPPING: "Menghentikan",
    } as Record<string, string>
  )[state] ?? state;
}

/**
 * Tambah proses dari paket yang sudah diterbitkan, atau ubah paket, versi,
 * dan lingkungannya — seperti "Add process" di Orchestrator.
 *
 * Paketnya dipilih dari umpan seluruh penyewa: paket tidak tinggal di folder,
 * prosesnya yang tinggal di folder.
 */
function DialogSimpanProses({
  awal,
  folder,
  namaDipakai,
  onTutup,
  onSelesai,
}: {
  awal: Process | null;
  folder: FolderNode;
  /** Nama proses yang sudah ada di folder ini. */
  namaDipakai: string[];
  onTutup: () => void;
  onSelesai: () => void;
}) {
  const { t } = useT();

  const paket = useQuery({ queryKey: ["packages", "semua"], queryFn: () => OpenOrchestratorApi.packages() });
  const lingkungan = useQuery({ queryKey: ["environments"], queryFn: OpenOrchestratorApi.environments });
  const ringkasan = useMemo(() => kelompokkanPaket(paket.data ?? []), [paket.data]);

  const [namaPaket, setNamaPaket] = useState(awal?.packageName ?? "");
  const [versi, setVersi] = useState(awal?.packageVersion ?? "");
  const [nama, setNama] = useState(awal?.name ?? "");
  const [namaDiubah, setNamaDiubah] = useState(!!awal);
  const [env, setEnv] = useState(awal?.environment ?? "Production");
  const [ket, setKet] = useState(awal?.description ?? "");
  // Setelan robot unattended. Batas waktu dalam MENIT di layar, detik di server;
  // kosong berarti tanpa batas.
  const [batasMenit, setBatasMenit] = useState(awal?.timeoutSeconds ? String(Math.round(awal.timeoutSeconds / 60)) : "");
  const [jeda, setJeda] = useState(String(awal?.stopGraceSeconds ?? 30));
  const [ulang, setUlang] = useState(String(awal?.maxRetries ?? 1));
  const [galat, setGalat] = useState("");

  const dipilih = ringkasan.find((r) => r.name === namaPaket);

  const simpan = useMutation({
    mutationFn: () =>
      OpenOrchestratorApi.saveProcess({
        name: nama.trim(),
        packageName: namaPaket || undefined,
        packageVersion: versi || undefined,
        environment: env || undefined,
        description: ket,
        folderId: folder.id,
        timeoutSeconds: batasMenit.trim() ? Math.round(Number(batasMenit) * 60) : 0,
        stopGraceSeconds: Number(jeda) || 30,
        maxRetries: Number(ulang),
      }),
    onSuccess: () => {
      onSelesai();
      onTutup();
    },
    onError: (e) => setGalat(errorText(e)),
  });

  function pilihPaket(n: string) {
    setNamaPaket(n);

    // Versi tertinggi lebih dulu, dan nama proses mengikuti nama paket
    // selama orangnya belum mengetik nama sendiri.
    const r = ringkasan.find((x) => x.name === n);
    setVersi(r?.terbaru.version ?? "");
    if (!namaDiubah) setNama(n);
  }

  function kirim() {
    setGalat("");
    if (!namaPaket) return setGalat(t("Pilih paketnya dulu."));
    if (!nama.trim()) return setGalat(t("Nama proses wajib diisi."));

    // Server memperbarui nama yang sudah ada di folder ini — itu yang dipakai
    // penerbitan ulang. Dari "Tambah proses", itu berarti diam-diam menimpa
    // proses lain; yang dimaksud orangnya hampir pasti nama yang berbeda.
    if (!awal && namaDipakai.includes(nama.trim())) {
      return setGalat(t("Proses '{0}' sudah ada di folder ini.", nama.trim()));
    }

    simpan.mutate();
  }

  return (
    <Dialog
      judul={awal ? `${t("Ubah proses")} — ${awal.name}` : t("Tambah proses")}
      terbuka
      onTutup={onTutup}
      aksi={
        <>
          <Button onClick={onTutup}>{t("Batal")}</Button>
          <Button variant="primary" onClick={kirim} disabled={simpan.isPending}>
            {simpan.isPending ? t("Menyimpan...") : t("Simpan")}
          </Button>
        </>
      }
    >
      <div className="grid gap-x-4 sm:grid-cols-2">
        <Isian label={t("Paket|satu")}>
          <select value={namaPaket} onChange={(e) => pilihPaket(e.target.value)} className={kelasIsian}>
            <option value="">—</option>
            {ringkasan.map((r) => (
              <option key={r.name} value={r.name}>
                {r.name}
              </option>
            ))}
          </select>
        </Isian>

        <Isian label={t("Versi")}>
          <select value={versi} onChange={(e) => setVersi(e.target.value)} className={kelasIsian} disabled={!dipilih}>
            {(dipilih?.versi ?? []).map((v) => (
              <option key={v.version} value={v.version}>
                {v.version}
                {v === dipilih?.terbaru ? ` (${t("Terbaru").toLowerCase()})` : ""}
              </option>
            ))}
          </select>
        </Isian>
      </div>

      <Isian
        label={t("Nama")}
        petunjuk={awal ? undefined : t("Nama proses unik di dalam folder ini. Folder lain boleh punya proses bernama sama.")}
      >
        <input
          value={nama}
          onChange={(e) => {
            setNama(e.target.value);
            setNamaDiubah(true);
          }}
          disabled={!!awal}
          className={kelasIsian}
        />
      </Isian>

      <Isian label={t("Lingkungan|satu")}>
        <select value={env} onChange={(e) => setEnv(e.target.value)} className={kelasIsian}>
          {(lingkungan.data?.map((l) => l.name) ?? ["Production"]).map((n) => (
            <option key={n} value={n}>
              {n}
            </option>
          ))}
        </select>
      </Isian>

      <Isian label={t("Keterangan")}>
        <input value={ket} onChange={(e) => setKet(e.target.value)} className={kelasIsian} />
      </Isian>

      <h3 className="mb-2 mt-1 text-xs font-semibold uppercase tracking-wide text-muted">{t("Robot unattended")}</h3>
      <div className="grid gap-x-4 sm:grid-cols-3">
        <Isian label={t("Batas waktu (menit)")} petunjuk={t("Kosong = tanpa batas.")}>
          <input type="number" min={1} max={10080} value={batasMenit} onChange={(e) => setBatasMenit(e.target.value)} className={kelasIsian} />
        </Isian>
        <Isian label={t("Jeda berhenti (detik)")} petunjuk={t("Sebelum Executor dimatikan paksa.")}>
          <input type="number" min={5} max={600} value={jeda} onChange={(e) => setJeda(e.target.value)} className={kelasIsian} />
        </Isian>
        <Isian label={t("Percobaan ulang otomatis")} petunjuk={t("Hanya kalau gagal sebelum workflow berjalan.")}>
          <select value={ulang} onChange={(e) => setUlang(e.target.value)} className={kelasIsian}>
            {["0", "1", "2"].map((n) => (
              <option key={n} value={n}>
                {n === "0" ? t("Tidak") : n}
              </option>
            ))}
          </select>
        </Isian>
      </div>

      {paket.isSuccess && ringkasan.length === 0 ? (
        <p className="mb-2 text-sm text-muted">
          {t("Belum ada paket yang diterbitkan. Terbitkan dari Studio: tab Design → grup OpenOrchestrator → Terbitkan.")}
        </p>
      ) : null}

      <Galat pesan={galat} />
    </Dialog>
  );
}

/**
 * Detail proses: riwayat jalannya DAN catatannya, di satu tempat.
 *
 * Halaman Catatan yang terpisah tetap ada untuk pencarian menyeluruh, tapi
 * orang yang sedang menyelidiki satu proses tidak seharusnya menyaring seluruh
 * catatan lebih dulu untuk sampai ke sini.
 */
function DialogProses({
  proses,
  folderId,
  onTutup,
}: {
  proses: Process | null;
  folderId: string;
  onTutup: () => void;
}) {
  const { t, tp } = useT();
  const [tab, setTab] = useState<"jalan" | "catatan">("jalan");

  // Disaring folder juga: proses bernama sama di folder lain adalah proses
  // lain, dengan riwayatnya sendiri.
  const jobs = useQuery({
    queryKey: ["jobs", "process", folderId, proses?.name],
    queryFn: () => OpenOrchestratorApi.jobs({ process: proses!.name, folderId, limit: 200 }),
    enabled: !!proses,
  });

  const log = useQuery({
    queryKey: ["logs", "process", folderId, proses?.name],
    queryFn: () => OpenOrchestratorApi.logs({ process: proses!.name, folderId, limit: 500 }),
    enabled: !!proses && tab === "catatan",
  });

  if (!proses) return null;

  return (
    <Dialog judul={proses.name} terbuka onTutup={onTutup} lebar="max-w-4xl">
      <div className="mb-4 flex gap-2 rounded-lg bg-slate-100 p-1">
        <button
          type="button"
          onClick={() => setTab("jalan")}
          className={`flex-1 rounded-md px-3 py-1.5 text-sm ${tab === "jalan" ? "bg-card font-medium shadow-sm" : "text-muted"}`}
        >
          {t("Pekerjaan")} ({proses.jobCount})
        </button>
        <button
          type="button"
          onClick={() => setTab("catatan")}
          className={`flex-1 rounded-md px-3 py-1.5 text-sm ${tab === "catatan" ? "bg-card font-medium shadow-sm" : "text-muted"}`}
        >
          {t("Catatan")}
        </button>
      </div>

      {tab === "jalan" ? (
        <div className="max-h-[26rem] overflow-y-auto rounded-lg border border-line">
          <DataTable
            data={jobs.data ?? []}
            kunci={(j) => j.id}
            perHalaman={0}
            kolom={[
              { judul: "Keadaan", sel: (j) => <Badge value={j.state} /> },
              { judul: "Robot|satu", sel: (j) => j.robotName ?? "-" },
              { judul: "Sumber", sel: (j) => <span className="text-muted">{j.source}</span> },
              { judul: "Dimulai", sel: (j) => <span className="text-muted">{dateTimeOf(j.startedAt)}</span> },
              { judul: "Selesai", sel: (j) => <span className="text-muted">{dateTimeOf(j.endedAt)}</span> },
              { judul: "Info", sel: (j) => <span className="text-muted">{j.info ? tp(j.info) : "-"}</span> },
            ]}
          />
        </div>
      ) : (
        <div className="max-h-[26rem] overflow-y-auto rounded-lg border border-line">
          {log.data?.length ? (
            log.data.map((l) => (
              <div key={l.id} className="flex gap-3 border-b border-line/70 px-3 py-1.5 text-xs last:border-0">
                <span className="w-32 shrink-0 tabular-nums text-muted">{dateTimeOf(l.loggedAt)}</span>
                <span className="w-14 shrink-0 font-medium">{l.level}</span>
                <span className="w-28 shrink-0 truncate text-muted">{l.robotName ?? "-"}</span>
                <span className="break-all">{tp(l.message)}</span>
              </div>
            ))
          ) : (
            <p className="px-3 py-8 text-center text-sm text-muted">
              {log.isFetching ? t("Memuat...") : t("Belum ada data.")}
            </p>
          )}
        </div>
      )}
    </Dialog>
  );
}
