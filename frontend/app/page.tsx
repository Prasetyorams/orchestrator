"use client";

import { useEffect, useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import {
  AppWindow,
  CircleCheck,
  CircleStop,
  CircleX,
  Clock,
  CopyPlus,
  Hand,
  Laptop,
  Network,
  Play,
  Users,
  Zap,
} from "lucide-react";
import { ForgeHubApi, type Dashboard, type FolderNode, type Periode } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { useTema } from "@/lib/tema";
import { lokalTanggal } from "@/lib/bahasa";
import { cn, dateTimeOf, waktuSingkat } from "@/lib/utils";
import { Badge, Card, CardHeader, Galat, KpiCard } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { PilihTingkat } from "@/components/PilihTingkat";
import { Donat, type Irisan } from "@/components/Donat";
import { JudulHalaman, PerluFolder } from "@/components/HalamanFolder";

/**
 * Rentang yang bisa dipilih di setiap donat. Semuanya rentang KALENDER, sama
 * dengan yang dihitung server: "Mingguan" berarti minggu ini sejak Senin,
 * "Bulanan" bulan ini sejak tanggal 1 — bukan tujuh atau tiga puluh hari
 * terakhir.
 */
const PERIODE: { kode: Periode; label: string }[] = [
  { kode: "today", label: "Harian" },
  { kode: "week", label: "Mingguan" },
  { kode: "month", label: "Bulanan" },
  { kode: "year", label: "Tahunan" },
];

const TINGKAT_PERINGATAN = ["Info", "Warning", "Error"];

/** Awalan kunci penyimpanan pilihan periode; setiap donat menambahkan namanya. */
const KUNCI_PERIODE = "forgehub.dasbor.periode";

/**
 * Keadaan pekerjaan di donat, dalam URUTAN CINCINNYA.
 *
 * Urutan ini bukan urutan logis (aktif lalu selesai): urutan itu menaruh
 * Berhasil tepat di sebelah Gagal, hijau di sebelah merah, dan keduanya
 * nyaris sama bagi pembaca buta warna merah-hijau. Urutan di bawah dipilih
 * dengan validator palet: setiap pasangan yang bersebelahan — termasuk
 * sambungan akhir ke awal — terpisah ΔE ≥ 10,7 di bawah simulasi protan dan
 * deutan, dan ≥ 15,7 untuk penglihatan normal.
 *
 * Warnanya warna STATUS (baik, peringatan, serius, kritis), karena keadaan
 * pekerjaan memang berarti baik atau buruk; setiap irisan selalu berlabel.
 *
 * Tema gelap: warna status tidak berganti (paletnya memang dibuat untuk
 * kedua permukaan); hanya biru Berjalan yang memakai langkah gelapnya. Diuji
 * ulang di atas kartu gelap #171c23 — ΔE ≥ 10,7 buta warna, ≥ 15,7 normal,
 * semua ≥ 3:1 terhadap kartunya.
 */
const KEADAAN = [
  { kunci: "RUNNING", label: "Berjalan", warna: "#2a78d6", warnaGelap: "#3987e5", ikon: Play },
  { kunci: "STOPPING", label: "Menghentikan", warna: "#ec835a", ikon: Hand },
  { kunci: "FAULTED", label: "Gagal", warna: "#d03b3b", ikon: CircleX },
  { kunci: "PENDING", label: "Menunggu", warna: "#fab219", ikon: Clock },
  { kunci: "SUCCESSFUL", label: "Berhasil", warna: "#0ca30c", ikon: CircleCheck },
  { kunci: "STOPPED", label: "Dihentikan", warna: "#898781", ikon: CircleStop },
] as const;

/**
 * Warna irisan per proses: lima slot pertama palet kategori, dalam urutan
 * tetap, dan abu-abu terang untuk "lainnya". Diperiksa dengan validator:
 * setiap pasangan bersebelahan di cincin ΔE ≥ 9,1 (buta warna) dan ≥ 15,5
 * (normal), untuk dua sampai enam irisan.
 *
 * Tema gelap memakai langkah gelap kelima slot yang sama — bukan palet lain —
 * dan diuji ulang di atas kartu gelap: ΔE ≥ 8,4 buta warna, ≥ 15,4 normal,
 * semua ≥ 3:1, untuk dua sampai enam irisan termasuk "lainnya". Abu-abu
 * "lainnya" sama di kedua tema: yang lebih gelap terlalu mirip magenta dan
 * hijau bagi pembaca buta warna.
 */
const WARNA_PROSES = {
  terang: ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4"],
  gelap: ["#3987e5", "#d95926", "#199e70", "#c98500", "#d55181"],
};
const WARNA_LAINNYA = "#c3c2b7";

export default function Beranda() {
  return <PerluFolder>{(folder) => <IsiBeranda folder={folder} />}</PerluFolder>;
}

function IsiBeranda({ folder }: { folder: FolderNode }) {
  const { t, tp } = useT();

  // Setiap donat memilih rentangnya sendiri, dan pilihannya diingat sendiri.
  const [periodePekerjaan, setPeriodePekerjaan] = usePeriode("pekerjaan");
  const [periodeRiwayat, setPeriodeRiwayat] = usePeriode("riwayat");

  const [tingkat, setTingkat] = useState<string[]>([]);

  // Disegarkan tiap 5 detik. Dasbor yang tidak bergerak selama satu menit
  // terlihat sama dengan dasbor yang rusak.
  //
  // Angka keempat periode datang bersama dalam satu jawaban, jadi berganti
  // pilihan di sebuah donat langsung mengganti isinya — tanpa permintaan baru.
  const d = useQuery({
    queryKey: ["dashboard", folder.id],
    queryFn: () => ForgeHubApi.dashboard(folder.id),
    refetchInterval: 5_000,
  });

  // Peringatan milik seluruh penyewa, bukan folder: robot yang terputus harus
  // terlihat dari folder mana pun.
  const peringatan = useQuery({
    queryKey: ["alerts", "dasbor", tingkat],
    queryFn: () => ForgeHubApi.alerts({ severity: tingkat, limit: 20 }),
    refetchInterval: 10_000,
    placeholderData: keepPreviousData,
  });

  if (d.isLoading) return <p className="text-sm text-muted">{t("Memuat...")}</p>;

  if (d.isError || !d.data) return <Galat pesan={t("Tidak bisa mengambil data dasbor.")} />;

  const x = d.data;
  const pustaka = x.library;

  return (
    <div className="space-y-5">
      <JudulHalaman judul={t("Beranda")} />

      <section className="grid grid-cols-2 gap-3 sm:gap-4 md:grid-cols-3 xl:grid-cols-6">
        <KpiCard label={t("Processes")} value={pustaka.processes} icon={Network} href="/automation/processes" />
        <KpiCard label={t("Assets")} value={pustaka.assets} icon={AppWindow} href="/assets" />
        <KpiCard label={t("Queues")} value={pustaka.queues} icon={CopyPlus} href="/queues" />
        <KpiCard
          label={t("Triggers")}
          value={pustaka.triggers}
          icon={Zap}
          href="/automation/triggers"
          hint={t("{0} aktif", pustaka.triggersEnabled)}
        />
        <KpiCard label={t("Users")} value={pustaka.users} icon={Users} href="/folder-settings?tab=pengguna" />
        <KpiCard
          label={t("Machines")}
          value={pustaka.machines}
          icon={Laptop}
          href="/monitoring/robots"
          hint={t("Robot: {0}", pustaka.robots)}
        />
      </section>

      <section className="grid grid-cols-1 gap-5 xl:grid-cols-2">
        <KartuPekerjaan data={x} periode={periodePekerjaan} onPeriode={setPeriodePekerjaan} />

        <Card>
          <CardHeader
            title={t("Jobs History")}
            subtitle={t("Pekerjaan per proses · {0}", keteranganRentang(periodeRiwayat, x.periods[periodeRiwayat].start, t))}
            action={<PilihPeriode nilai={periodeRiwayat} onUbah={setPeriodeRiwayat} untuk={t("Jobs History")} />}
          />
          <div className="px-5 pb-4 pt-3">
            <DonatProses data={x} periode={periodeRiwayat} />
          </div>

          <h3 className="border-t border-line px-5 pb-1 pt-4 text-sm font-semibold text-ink">{t("Sedang Berjalan")}</h3>
          <DataTable
            data={x.jobsInProgress}
            kunci={(j) => j.id}
            perHalaman={0}
            rapat
            kosong="Tidak ada pekerjaan yang sedang berjalan."
            kolom={[
              { judul: "Proses|satu", sel: (j) => <span className="font-medium">{j.processName}</span> },
              { judul: "Robot|satu", sel: (j) => j.robotName ?? "-" },
              { judul: "Keadaan", sel: (j) => <Badge value={j.state} /> },
              {
                judul: "Kemajuan",
                sel: (j) => (
                  <div className="flex items-center gap-2">
                    <div className="h-1.5 w-14 overflow-hidden rounded-full bg-slate-100">
                      <div className="h-full rounded-full bg-brand" style={{ width: `${j.progress}%` }} />
                    </div>
                    <span className="text-xs tabular-nums text-muted">{j.progress}%</span>
                  </div>
                ),
              },
              {
                judul: "Dibuat",
                sel: (j) => (
                  <span className="whitespace-nowrap text-muted" title={dateTimeOf(j.createdAt)}>
                    {waktuSingkat(j.createdAt)}
                  </span>
                ),
              },
            ]}
          />
        </Card>
      </section>

      <section className="grid grid-cols-1 gap-5 xl:grid-cols-2">
        <Card>
          <CardHeader title={t("Robot")} subtitle={t("Robot yang ditugaskan ke folder ini")} />
          <DataTable
            data={x.activeRobots}
            kunci={(r) => r.name}
            perHalaman={0}
            rapat
            kosong="Belum ada robot yang ditugaskan ke folder ini."
            kolom={[
              { judul: "Robot|satu", sel: (r) => <span className="font-medium">{r.name}</span> },
              { judul: "Status", sel: (r) => <Badge value={r.status} /> },
              {
                judul: "CPU / Memori",
                sel: (r) => (
                  <span className="tabular-nums text-muted">
                    {Math.round(r.cpuPercent)}% / {Math.round(r.memoryMb)} MB
                  </span>
                ),
              },
              {
                judul: "Denyut",
                sel: (r) => (
                  <span className="whitespace-nowrap text-muted" title={dateTimeOf(r.lastHeartbeatAt)}>
                    {waktuSingkat(r.lastHeartbeatAt)}
                  </span>
                ),
              },
            ]}
          />
        </Card>

        <Card>
          <CardHeader title={t("Pemicu Berikutnya")} />
          <DataTable
            data={x.upcomingTriggers}
            kunci={(q) => q.name}
            perHalaman={0}
            rapat
            kosong="Tidak ada pemicu yang aktif."
            kolom={[
              { judul: "Nama", sel: (q) => <span className="font-medium">{q.name}</span> },
              { judul: "Proses|satu", sel: (q) => q.processName },
              {
                judul: "Jadwal",
                sel: (q) =>
                  q.cron ? (
                    <code className="rounded bg-slate-100 px-1.5 py-0.5 text-xs">{q.cron}</code>
                  ) : (
                    <span className="text-muted">{t("tiap {0} menit", q.intervalMinutes)}</span>
                  ),
              },
              {
                judul: "Jalan Berikutnya",
                sel: (q) => (
                  <span className="whitespace-nowrap text-muted" title={dateTimeOf(q.nextRunAt)}>
                    {waktuSingkat(q.nextRunAt)}
                  </span>
                ),
              },
            ]}
          />
        </Card>
      </section>

      <section className="grid grid-cols-1 gap-5 xl:grid-cols-2">
        <Card>
          <CardHeader title={t("Ringkasan Antrean")} />
          <DataTable
            data={x.queueSummary}
            kunci={(q) => q.name}
            perHalaman={0}
            rapat
            kosong="Belum ada antrean di folder ini."
            kolom={[
              { judul: "Nama", sel: (q) => <span className="font-medium">{q.name}</span> },
              { judul: "Baru", sel: (q) => <span className="tabular-nums">{q.newCount}</span> },
              { judul: "Diproses", sel: (q) => <span className="tabular-nums">{q.inProgressCount}</span> },
              { judul: "Berhasil", sel: (q) => <span className="tabular-nums text-ok">{q.successfulCount}</span> },
              {
                judul: "Gagal",
                sel: (q) => (
                  <span className={q.failedCount > 0 ? "tabular-nums text-danger" : "tabular-nums"}>
                    {q.failedCount}
                  </span>
                ),
              },
            ]}
          />
        </Card>

        <Card>
          <div className="flex flex-wrap items-center justify-between gap-2 border-b border-line px-5 py-3">
            <div>
              <h2 className="text-[15px] font-semibold text-ink">{t("Peringatan Terbaru")}</h2>
              <p className="mt-0.5 text-xs text-muted">{t("Seluruh penyewa")}</p>
            </div>
            <PilihTingkat pilihan={TINGKAT_PERINGATAN} terpilih={tingkat} onUbah={setTingkat} />
          </div>

          <div
            className={cn(
              "max-h-[26rem] overflow-y-auto transition-opacity",
              peringatan.isPlaceholderData && "opacity-60",
            )}
          >
            <DataTable
              data={peringatan.data ?? []}
              kunci={(a) => String(a.id)}
              perHalaman={0}
              rapat
              kosong={
                peringatan.isLoading
                  ? "Memuat..."
                  : tingkat.length
                    ? "Tidak ada peringatan pada tingkat ini."
                    : "Belum ada peringatan."
              }
              kolom={[
                { judul: "Tingkat", sel: (a) => <Badge value={a.severity.toUpperCase()} /> },
                {
                  judul: "Pesan",
                  sel: (a) => (
                    <div>
                      <p className="font-medium text-ink">{tp(a.title)}</p>
                      {a.message ? <p className="text-xs text-muted">{tp(a.message)}</p> : null}
                    </div>
                  ),
                },
                {
                  judul: "Waktu",
                  sel: (a) => (
                    <span className="whitespace-nowrap text-muted" title={dateTimeOf(a.createdAt)}>
                      {waktuSingkat(a.createdAt)}
                    </span>
                  ),
                },
              ]}
            />
          </div>
        </Card>
      </section>
    </div>
  );
}

// ---------------------------------------------------------------------
// Donat
// ---------------------------------------------------------------------

/**
 * Pekerjaan per keadaan: yang masih berjalan, menunggu, atau sedang dihentikan
 * — tanpa rentang, karena belum punya akhir — ditambah yang selesai di dalam
 * periodenya.
 */
function KartuPekerjaan({
  data,
  periode,
  onPeriode,
}: {
  data: Dashboard;
  periode: Periode;
  onPeriode: (p: Periode) => void;
}) {
  const { t } = useT();
  const { gelap } = useTema();
  const angka = data.periods[periode];

  const nilai: Record<(typeof KEADAAN)[number]["kunci"], number> = {
    RUNNING: data.jobs.running,
    STOPPING: data.jobs.stopping ?? 0,
    FAULTED: angka.faulted,
    PENDING: data.jobs.pending,
    SUCCESSFUL: angka.successful,
    STOPPED: angka.stopped ?? 0,
  };

  const irisan: Irisan[] = KEADAAN.map((k) => ({
    kunci: k.kunci,
    label: t(k.label),
    nilai: nilai[k.kunci],
    warna: (gelap && "warnaGelap" in k ? k.warnaGelap : undefined) ?? k.warna,
    ikon: k.ikon,
  }));

  const total = irisan.reduce((s, x) => s + x.nilai, 0);
  const selesai = angka.successful + angka.faulted;

  return (
    <Card>
      <CardHeader
        title={t("Jobs Pekerjaan")}
        subtitle={t("Pekerjaan per keadaan · {0}", keteranganRentang(periode, angka.start, t))}
        action={<PilihPeriode nilai={periode} onUbah={onPeriode} untuk={t("Jobs Pekerjaan")} />}
      />
      <div className="px-5 pb-5 pt-3">
        <Donat
          irisan={irisan}
          tengah={total}
          keteranganTengah={t("pekerjaan")}
          judul={t("Jobs Pekerjaan")}
          kosong={t("Belum ada pekerjaan pada rentang ini.")}
        />

        <p className="mt-3 border-t border-line pt-3 text-sm text-muted">
          {selesai > 0
            ? t("Tingkat keberhasilan {0}% dari {1} pekerjaan yang selesai.", angka.successRate, selesai)
            : t("Belum ada pekerjaan yang selesai pada rentang ini.")}
        </p>
      </div>
    </Card>
  );
}

/**
 * Pekerjaan per proses. Warnanya mengikuti PROSESNYA — urutan irisannya sama
 * di keempat periode (lihat DashboardService.prosesTerpilih) — jadi berganti
 * dari Harian ke Tahunan tidak mewarnai ulang proses yang sama.
 */
function DonatProses({ data, periode }: { data: Dashboard; periode: Periode }) {
  const { t } = useT();
  const { gelap } = useTema();
  const daftar = data.processBreakdown?.[periode] ?? [];
  const warna = gelap ? WARNA_PROSES.gelap : WARNA_PROSES.terang;

  const irisan: Irisan[] = daftar.map((p, i) => ({
    kunci: p.other ? "__lainnya" : (p.name ?? String(i)),
    label: p.other ? t("Lainnya ({0} proses)", p.processes ?? 0) : (p.name ?? "-"),
    nilai: p.count,
    warna: p.other ? WARNA_LAINNYA : warna[i % warna.length],
  }));

  const total = irisan.reduce((s, x) => s + x.nilai, 0);

  return (
    <Donat
      irisan={irisan}
      tengah={total}
      keteranganTengah={t("pekerjaan")}
      judul={t("Jobs History")}
      kosong={t("Belum ada pekerjaan pada rentang ini.")}
    />
  );
}

// ---------------------------------------------------------------------
// Pilihan periode
// ---------------------------------------------------------------------

/**
 * Rentang yang dipilih untuk SATU donat, diingat per peramban.
 *
 * Dibaca SESUDAH dipasang, alasannya sama dengan pilihan bahasa di
 * I18nProvider: di server tidak ada localStorage, dan membacanya saat render
 * pertama membuat isi dari server dan dari peramban tidak sama.
 */
function usePeriode(metrik: string): [Periode, (p: Periode) => void] {
  const kunci = `${KUNCI_PERIODE}.${metrik}`;
  const [periode, setPeriodeState] = useState<Periode>("today");

  useEffect(() => {
    try {
      const tersimpan = window.localStorage.getItem(kunci);
      if (PERIODE.some((p) => p.kode === tersimpan)) setPeriodeState(tersimpan as Periode);
    } catch {
      /* tanpa penyimpanan: mulai dari hari ini */
    }
  }, [kunci]);

  function setPeriode(p: Periode) {
    setPeriodeState(p);
    try {
      window.localStorage.setItem(kunci, p);
    } catch {
      /* diabaikan */
    }
  }

  return [periode, setPeriode];
}

/**
 * Pilihan rentang untuk satu kartu.
 *
 * <select> biasa, bukan menu buatan sendiri: papan ketik, pembaca layar, dan
 * layar sentuh sudah diurus peramban, dan pilihannya hanya empat.
 */
function PilihPeriode({
  nilai,
  onUbah,
  untuk,
}: {
  nilai: Periode;
  onUbah: (p: Periode) => void;
  /** Nama kartunya, untuk pembaca layar: "Rentang waktu: Jobs History". */
  untuk: string;
}) {
  const { t } = useT();

  return (
    <select
      value={nilai}
      onChange={(e) => onUbah(e.target.value as Periode)}
      aria-label={t("Rentang waktu: {0}", untuk)}
      className={cn(
        "shrink-0 cursor-pointer rounded-md border border-line bg-card py-1 pl-2 pr-1 text-xs text-ink",
        "outline-none transition hover:border-slate-300 focus:border-brand focus:ring-2 focus:ring-brand/20",
      )}
    >
      {PERIODE.map((p) => (
        <option key={p.kode} value={p.kode}>
          {t(p.label)}
        </option>
      ))}
    </select>
  );
}

/**
 * Keterangan rentang: "Hari ini", atau sejak kapan periodenya dimulai. Itu
 * menjawab "mingguan itu tujuh hari terakhir, atau sejak Senin?" tanpa orang
 * perlu bertanya.
 */
function keteranganRentang(kode: Periode, awal: string, t: ReturnType<typeof useT>["t"]): string {
  if (kode === "today") return t("Hari ini");

  const bentuk: Intl.DateTimeFormatOptions =
    kode === "week"
      ? { weekday: "short", day: "numeric", month: "short" }
      : kode === "month"
        ? { day: "numeric", month: "short" }
        : { day: "numeric", month: "short", year: "numeric" };

  // "YYYY-MM-DD" dari server adalah tanggal SETEMPAT server; sengaja tanpa
  // zona, karena yang dibutuhkan hanya nama hari dan bulannya.
  return t("Sejak {0}", new Date(`${awal}T00:00:00`).toLocaleDateString(lokalTanggal(), bentuk));
}
