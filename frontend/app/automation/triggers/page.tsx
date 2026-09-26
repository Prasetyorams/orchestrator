"use client";

import { useCallback, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Pencil, Power, PowerOff, Trash2 } from "lucide-react";
import { ForgeHubApi, errorText, type FolderNode, type Trigger } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { useIzin } from "@/lib/izin";
import { dateTimeOf } from "@/lib/utils";
import { Badge, Button, Card, Galat, IconButton } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { Dialog, Isian, kelasIsian } from "@/components/Dialog";
import { BilahAlat, PerluFolder } from "@/components/HalamanFolder";

/**
 * Contoh cron yang bisa diklik.
 *
 * Ada karena hampir tidak ada yang hafal urutan lima ruasnya, dan yang
 * mengarangnya sendiri biasanya menuliskan sesuatu yang sah tapi bukan yang
 * dimaksudnya — lalu baru tahu seminggu kemudian.
 */
const CONTOH = [
  { cron: "*/15 * * * *", arti: "Tiap 15 menit" },
  { cron: "0 * * * *", arti: "Tiap jam, di menit ke-0" },
  { cron: "0 7 * * *", arti: "Tiap hari pukul 07:00" },
  { cron: "0 7 * * 1-5", arti: "Tiap hari kerja pukul 07:00" },
  { cron: "0 0 1 * *", arti: "Tiap tanggal 1 tengah malam" },
  { cron: "30 22 * * 6", arti: "Tiap Sabtu pukul 22:30" },
];

const ZONA = ["Asia/Jakarta", "Asia/Makassar", "Asia/Jayapura", "UTC"];

export default function Pemicu() {
  return <PerluFolder>{(folder) => <IsiPemicu folder={folder} />}</PerluFolder>;
}

function IsiPemicu({ folder }: { folder: FolderNode }) {
  const { t } = useT();
  const klien = useQueryClient();
  const { boleh } = useIzin();

  const [sunting, setSunting] = useState<Trigger | null>(null);
  const [baru, setBaru] = useState(false);
  const [galat, setGalat] = useState("");

  const pemicu = useQuery({
    queryKey: ["triggers", folder.id],
    queryFn: () => ForgeHubApi.triggers(folder.id),
    refetchInterval: 10_000,
  });

  const segarkan = useCallback(() => {
    klien.invalidateQueries({ queryKey: ["triggers"] });
    klien.invalidateQueries({ queryKey: ["dashboard"] });
  }, [klien]);

  const alihkan = useMutation({
    mutationFn: (nama: string) => ForgeHubApi.toggleTrigger(nama, folder.id),
    onSuccess: segarkan,
    onError: (e) => setGalat(errorText(e)),
  });

  const hapus = useMutation({
    mutationFn: (nama: string) => ForgeHubApi.deleteTrigger(nama, folder.id),
    onSuccess: segarkan,
    onError: (e) => setGalat(errorText(e)),
  });

  const tutup = useCallback(() => {
    setBaru(false);
    setSunting(null);
  }, []);

  return (
    <div>
      <BilahAlat
        aksi={
          boleh("triggers.create") ? (
            <Button variant="primary" onClick={() => setBaru(true)}>
              {t("Tambah pemicu")}
            </Button>
          ) : null
        }
      />

      <Galat pesan={galat} className="mb-4" />

      <Card>
        <DataTable
          data={pemicu.data ?? []}
          kunci={(p) => p.name}
          onBuka={boleh("triggers.update") ? setSunting : undefined}
          kosong={pemicu.isLoading ? "Memuat..." : "Belum ada pemicu di folder ini."}
          kolom={[
            { judul: "Nama", sel: (p) => <span className="font-medium">{p.name}</span>, urut: (p) => p.name },
            { judul: "Proses|satu", sel: (p) => p.processName, urut: (p) => p.processName },
            {
              judul: "Jadwal",
              sel: (p) =>
                p.cron ? (
                  <code className="rounded bg-slate-100 px-1.5 py-0.5 text-xs">{p.cron}</code>
                ) : (
                  <span className="text-muted">{t("tiap {0} menit", p.intervalMinutes)}</span>
                ),
              urut: (p) => p.cron ?? `${p.intervalMinutes}m`,
            },
            { judul: "Zona Waktu", sel: (p) => <span className="text-muted">{p.timezone}</span>, urut: (p) => p.timezone },
            {
              judul: "Status",
              sel: (p) => (
                <Badge value={p.enabled ? "AVAILABLE" : "STOPPED"} label={t(p.enabled ? "Aktif" : "Nonaktif")} />
              ),
              urut: (p) => String(p.enabled),
            },
            {
              judul: "Jalan Berikutnya",
              sel: (p) => <span className="text-muted">{dateTimeOf(p.nextRunAt)}</span>,
              urut: (p) => p.nextRunAt,
            },
            {
              judul: "Jalan Terakhir",
              sel: (p) => <span className="text-muted">{dateTimeOf(p.lastRunAt)}</span>,
              urut: (p) => p.lastRunAt,
            },
            {
              judul: "",
              sel: (p) => (
                <div className="flex justify-end gap-0.5">
                  {boleh("triggers.update") ? (
                    <>
                      <IconButton
                        label={p.enabled ? t("Matikan") : t("Nyalakan")}
                        tone={p.enabled ? "default" : "ok"}
                        onClick={() => alihkan.mutate(p.name)}
                      >
                        {p.enabled ? <PowerOff size={16} /> : <Power size={16} />}
                      </IconButton>
                      <IconButton label={t("Ubah")} onClick={() => setSunting(p)}>
                        <Pencil size={15} />
                      </IconButton>
                    </>
                  ) : null}
                  {boleh("triggers.delete") ? (
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
              ),
            },
          ]}
        />
      </Card>

      <p className="mt-3 text-xs text-muted">
        {t("Pemicu tinggal di folder prosesnya: memindah proses ke folder lain ikut memindah pemicunya.")}
      </p>

      {baru || sunting ? (
        <DialogPemicu key={sunting?.name ?? "baru"} awal={sunting} folder={folder} onTutup={tutup} onSelesai={segarkan} />
      ) : null}
    </div>
  );
}

function DialogPemicu({
  awal,
  folder,
  onTutup,
  onSelesai,
}: {
  awal: Trigger | null;
  folder: FolderNode;
  onTutup: () => void;
  onSelesai: () => void;
}) {
  const { t } = useT();

  const proses = useQuery({ queryKey: ["processes", folder.id], queryFn: () => ForgeHubApi.processes(folder.id) });
  const { boleh } = useIzin();
  const robot = useQuery({
    queryKey: ["robots", folder.id],
    queryFn: () => ForgeHubApi.robots(folder.id),
    enabled: boleh("robots.read"),
  });

  const [nama, setNama] = useState(awal?.name ?? "");
  const [namaProses, setNamaProses] = useState(awal?.processName ?? "");
  const [namaRobot, setNamaRobot] = useState(awal?.robotName ?? "");
  const [pakaiCron, setPakaiCron] = useState(!!awal?.cron);
  const [cron, setCron] = useState(awal?.cron ?? "0 7 * * 1-5");
  const [selang, setSelang] = useState(awal?.intervalMinutes || 60);
  const [zona, setZona] = useState(awal?.timezone ?? "Asia/Jakarta");
  const [prioritas, setPrioritas] = useState(awal?.priority ?? "Normal");
  const [galat, setGalat] = useState("");

  const simpan = useMutation({
    mutationFn: ForgeHubApi.saveTrigger,
    onSuccess: () => {
      onSelesai();
      onTutup();
    },
    onError: (e) => setGalat(errorText(e)),
  });

  function kirim() {
    if (!nama.trim()) return setGalat(t("Nama pemicu wajib diisi."));
    if (!namaProses) return setGalat(t("Pilih prosesnya dulu."));

    simpan.mutate({
      name: nama.trim(),
      processName: namaProses,
      folderId: folder.id,
      robotName: namaRobot || undefined,
      timezone: zona,
      priority: prioritas,
      enabled: awal?.enabled ?? true,
      ...(pakaiCron ? { cron: cron.trim() } : { intervalMinutes: Number(selang) || 60 }),
    });
  }

  // Proses pemicu yang disunting bisa saja sudah dipindah keluar folder ini;
  // tetap ditampilkan supaya pilihannya tidak diam-diam berganti.
  const pilihanProses = (proses.data ?? []).map((p) => p.name);
  if (awal && !pilihanProses.includes(awal.processName)) pilihanProses.unshift(awal.processName);

  return (
    <Dialog
      judul={awal ? `${t("Sunting")} — ${awal.name}` : t("Tambah pemicu")}
      terbuka
      onTutup={onTutup}
      lebar="max-w-2xl"
      aksi={
        <>
          <Button onClick={onTutup}>{t("Batal")}</Button>
          <Button variant="primary" onClick={kirim} disabled={simpan.isPending}>
            {t("Simpan")}
          </Button>
        </>
      }
    >
      <div className="grid gap-x-4 sm:grid-cols-2">
        <Isian label={t("Nama")}>
          <input value={nama} onChange={(e) => setNama(e.target.value)} disabled={!!awal} className={kelasIsian} />
        </Isian>

        <Isian label={t("Proses|satu")}>
          <select value={namaProses} onChange={(e) => setNamaProses(e.target.value)} className={kelasIsian}>
            <option value="">—</option>
            {pilihanProses.map((p) => (
              <option key={p} value={p}>
                {p}
              </option>
            ))}
          </select>
        </Isian>
      </div>

      <Isian label={t("Robot|satu")}>
        <select value={namaRobot} onChange={(e) => setNamaRobot(e.target.value)} className={kelasIsian}>
          <option value="">{t("Robot mana pun di folder ini")}</option>
          {(robot.data ?? []).map((r) => (
            <option key={r.name} value={r.name}>
              {r.name}
            </option>
          ))}
        </select>
      </Isian>

      <div className="mb-3 flex gap-2 rounded-lg bg-slate-100 p-1">
        <button
          type="button"
          onClick={() => setPakaiCron(false)}
          className={`flex-1 rounded-md px-3 py-1.5 text-sm ${!pakaiCron ? "bg-card font-medium shadow-sm" : "text-muted"}`}
        >
          {t("Selang waktu")}
        </button>
        <button
          type="button"
          onClick={() => setPakaiCron(true)}
          className={`flex-1 rounded-md px-3 py-1.5 text-sm ${pakaiCron ? "bg-card font-medium shadow-sm" : "text-muted"}`}
        >
          Cron
        </button>
      </div>

      {pakaiCron ? (
        <>
          <Isian
            label={t("Ekspresi cron")}
            petunjuk={t("Lima ruas: menit jam tanggal bulan hari. Kalau tanggal DAN hari sama-sama diisi, cukup salah satu cocok.")}
          >
            <input value={cron} onChange={(e) => setCron(e.target.value)} className={`${kelasIsian} font-mono`} />
          </Isian>

          <div className="mb-3 flex flex-wrap gap-1.5">
            {CONTOH.map((c) => (
              <button
                key={c.cron}
                type="button"
                onClick={() => setCron(c.cron)}
                title={c.cron}
                className="rounded-full border border-line px-2.5 py-1 text-xs text-muted transition hover:border-brand hover:text-ink"
              >
                {t(c.arti)}
              </button>
            ))}
          </div>
        </>
      ) : (
        <Isian label={t("Jalankan tiap (menit)")}>
          <input
            type="number"
            min={1}
            value={selang}
            onChange={(e) => setSelang(Number(e.target.value))}
            className={kelasIsian}
          />
        </Isian>
      )}

      <div className="grid gap-x-4 sm:grid-cols-2">
        <Isian label={t("Zona Waktu")} petunjuk={t("Jadwalnya dihitung menurut zona ini, bukan waktu server.")}>
          <select value={zona} onChange={(e) => setZona(e.target.value)} className={kelasIsian}>
            {/* Zona tersimpan yang tidak ada di daftar pendek tetap ditawarkan,
                supaya menyunting pemicu lain tidak diam-diam mengganti zonanya. */}
            {(ZONA.includes(zona) ? ZONA : [zona, ...ZONA]).map((z) => (
              <option key={z}>{z}</option>
            ))}
          </select>
        </Isian>

        <Isian label={t("Prioritas")}>
          <select value={prioritas} onChange={(e) => setPrioritas(e.target.value)} className={kelasIsian}>
            <option>Low</option>
            <option>Normal</option>
            <option>High</option>
          </select>
        </Isian>
      </div>

      <Galat pesan={galat} />
    </Dialog>
  );
}
