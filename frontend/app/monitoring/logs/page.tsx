"use client";

import { useEffect, useMemo, useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { ChevronDown, ChevronUp, Columns3, Download, ListFilter, RotateCw, Search, X } from "lucide-react";
import { OpenOrchestratorApi, errorText, type FolderNode, type LogLine, type SaringanLog } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { cn, dateTimeOf } from "@/lib/utils";
import { Badge, Button, Card, Galat } from "@/components/ui/primitives";
import { DataTable, type Kolom } from "@/components/DataTable";
import { kelasIsian } from "@/components/Dialog";
import { BilahAlat, PerluFolder } from "@/components/HalamanFolder";
import { WARNA_TINGKAT } from "@/components/PilihTingkat";
import { DaftarCentang, DaftarPilihan, DaftarSakelar, Munculan, PilSaringan, Pilihan } from "@/components/Saringan";

// Tanpa TRACE dan DEBUG: OpenOrchestrator tidak menyimpan maupun menampilkan tingkat
// rincian (lihat LogLevel.rincian di backend), jadi pilihan itu selalu kosong.
// WARN juga membawa baris WARNING — satu tingkat dengan dua ejaan.
const TINGKAT = ["INFO", "WARN", "ERROR", "FATAL"];

/** Rentang waktu siap pakai; kuncinya sama dengan yang dibaca server (?time=). */
const RENTANG = [
  { kunci: "", label: "Semua" },
  { kunci: "15m", label: "15 menit terakhir" },
  { kunci: "30m", label: "30 menit terakhir" },
  { kunci: "1h", label: "1 jam terakhir" },
  { kunci: "6h", label: "6 jam terakhir" },
  { kunci: "12h", label: "12 jam terakhir" },
  { kunci: "24h", label: "24 jam terakhir" },
  { kunci: "today", label: "Hari ini" },
  { kunci: "yesterday", label: "Kemarin" },
  { kunci: "7d", label: "7 hari terakhir" },
];

/** Paling banyak sekian baris terbaru yang cocok; tabel memecahnya per 50. */
const BATAS_BARIS = 500;

type Saringan = {
  /** "" = semua waktu; kunci RENTANG; atau "custom" dengan dari/sampai. */
  waktu: string;
  dari: string;
  sampai: string;
  tingkat: string[];
  mesin: string;
  proses: string;
  host: string;
  /** Satu pekerjaan — dari "Lihat Log Job Ini" di menu halaman Pekerjaan. */
  jobId: string;
};

const SARINGAN_AWAL: Saringan = { waktu: "", dari: "", sampai: "", tingkat: [], mesin: "", proses: "", host: "", jobId: "" };

/** Kolom yang bisa disembunyikan; Waktu dan Pesan selalu tampil. */
const KOLOM_PILIHAN = ["Tingkat", "Robot|satu", "Mesin|satu", "Host Identity", "Proses|satu"];

/** Pilihan kolom disimpan per peramban — kenyamanan tampilan, bukan data. */
const KUNCI_KOLOM = "openorchestrator.catatan.kolom";

export default function Catatan() {
  return <PerluFolder>{(folder) => <IsiCatatan folder={folder} />}</PerluFolder>;
}

function IsiCatatan({ folder }: { folder: FolderNode }) {
  const { t, tp } = useT();

  const [saringan, setSaringan] = useState<Saringan>(SARINGAN_AWAL);
  const [cari, setCari] = useState("");
  const [q, setQ] = useState("");
  const [ikuti, setIkuti] = useState(true);
  const [tampilSaringan, setTampilSaringan] = useState(true);
  const [kolom, setKolom] = useState<string[]>(KOLOM_PILIHAN);
  const [ulang, setUlang] = useState(0);
  const [siap, setSiap] = useState(false);
  const [galat, setGalat] = useState("");
  const [mengekspor, setMengekspor] = useState(false);

  // Saringan dibaca dari alamat halaman sekali saat dipasang — dari tautan
  // "Lihat Log Job Ini"/"Lihat Semua Log Proses", markah, atau muat ulang.
  // Lewat window: useSearchParams menuntut pembungkus Suspense di seluruh
  // halaman hanya untuk nilai awal ini.
  useEffect(() => {
    const alamat = new URLSearchParams(window.location.search);
    // Kunci waktu dan tanggal yang tidak bisa dibaca dibuang, bukan dikirim:
    // tautan lama atau salah ketik cukup berarti "semua waktu", bukan halaman
    // yang gagal. Rentang siap pakai menang atas dari/sampai, seperti di server.
    const tanggal = (s: string | null) => (s && !Number.isNaN(Date.parse(s)) ? s : "");
    const kunciWaktu = (alamat.get("time") ?? "").toLowerCase();
    const siapPakai = RENTANG.some((r) => r.kunci !== "" && r.kunci === kunciWaktu);
    const dari = siapPakai ? "" : tanggal(alamat.get("from"));
    const sampai = siapPakai ? "" : tanggal(alamat.get("to"));

    setSaringan({
      waktu: siapPakai ? kunciWaktu : dari || sampai ? "custom" : "",
      dari,
      sampai,
      tingkat: TINGKAT.filter((x) =>
        (alamat.get("level") ?? "").toUpperCase().split(",").map((s) => s.trim()).includes(x),
      ),
      mesin: alamat.get("machine") ?? "",
      proses: alamat.get("process") ?? "",
      host: alamat.get("host") ?? "",
      jobId: alamat.get("jobId") ?? "",
    });

    const kata = alamat.get("q") ?? "";
    setCari(kata);
    setQ(kata);

    try {
      const tersimpan = JSON.parse(window.localStorage.getItem(KUNCI_KOLOM) ?? "null");
      if (Array.isArray(tersimpan)) setKolom(KOLOM_PILIHAN.filter((k) => tersimpan.includes(k)));
    } catch {
      // Penyimpanan peramban tidak bisa dibaca: pakai kolom bawaan.
    }

    setSiap(true);
  }, []);

  // Pencarian dikirim sesudah orangnya berhenti mengetik, bukan setiap huruf.
  useEffect(() => {
    const id = setTimeout(() => setQ(cari.trim()), 300);
    return () => clearTimeout(id);
  }, [cari]);

  // Alamat halaman mengikuti saringan: muat ulang, markah, dan tautan yang
  // dibagikan menampilkan data yang sama dengan yang terlihat sekarang.
  useEffect(() => {
    if (!siap) return;

    const alamat = new URLSearchParams();
    if (saringan.waktu) alamat.set("time", saringan.waktu);
    if (saringan.waktu === "custom" && saringan.dari) alamat.set("from", saringan.dari);
    if (saringan.waktu === "custom" && saringan.sampai) alamat.set("to", saringan.sampai);
    if (saringan.tingkat.length) alamat.set("level", saringan.tingkat.join(","));
    if (saringan.mesin) alamat.set("machine", saringan.mesin);
    if (saringan.proses) alamat.set("process", saringan.proses);
    if (saringan.host) alamat.set("host", saringan.host);
    if (saringan.jobId) alamat.set("jobId", saringan.jobId);
    if (q) alamat.set("q", q);

    const kueri = alamat.toString();
    window.history.replaceState(null, "", window.location.pathname + (kueri ? `?${kueri}` : ""));
  }, [siap, saringan, q]);

  const khusus = saringan.waktu === "custom";

  const parameter: SaringanLog = {
    // Satu pekerjaan sudah cukup menyempitkan; foldernya folder pekerjaan itu.
    folderId: saringan.jobId ? undefined : folder.id,
    jobId: saringan.jobId,
    level: saringan.tingkat,
    machine: saringan.mesin,
    process: saringan.proses,
    host: saringan.host,
    time: saringan.waktu,
    from: khusus ? saringan.dari : undefined,
    to: khusus ? saringan.sampai : undefined,
    q,
  };

  const log = useQuery({
    queryKey: ["logs", parameter],
    queryFn: () => OpenOrchestratorApi.logs({ ...parameter, limit: BATAS_BARIS }),
    enabled: siap,
    // Hanya menyegarkan sendiri saat "ikuti" menyala. Tabel yang melompat ke
    // baris terbaru tiap tiga detik membuat orang yang sedang membaca satu
    // baris kehilangan tempatnya.
    refetchInterval: ikuti ? 3_000 : false,
    // Mengganti saringan tidak mengosongkan tabel lebih dulu.
    placeholderData: keepPreviousData,
    retry: ulangiKalauBukanGalatSaringan,
  });

  // Pilihan Mesin, Proses, dan Host Identity: nilai yang ada di catatan
  // folder dan rentang waktu ini, dari server — bukan daftar tetap.
  const pilihan = useQuery({
    queryKey: ["logs-filters", parameter.folderId, parameter.jobId, parameter.time, parameter.from, parameter.to],
    queryFn: () =>
      OpenOrchestratorApi.logFilterOptions({
        folderId: parameter.folderId,
        jobId: parameter.jobId,
        time: parameter.time,
        from: parameter.from,
        to: parameter.to,
      }),
    enabled: siap,
    staleTime: 30_000,
    placeholderData: keepPreviousData,
    retry: ulangiKalauBukanGalatSaringan,
  });

  const job = useQuery({
    queryKey: ["job", saringan.jobId],
    queryFn: () => OpenOrchestratorApi.job(saringan.jobId),
    enabled: !!saringan.jobId,
  });

  const jumlahAktif =
    (saringan.waktu ? 1 : 0) +
    (saringan.tingkat.length ? 1 : 0) +
    (saringan.mesin ? 1 : 0) +
    (saringan.proses ? 1 : 0) +
    (saringan.host ? 1 : 0) +
    (saringan.jobId ? 1 : 0);
  const disaring = jumlahAktif > 0 || !!q;

  function ubah(sebagian: Partial<Saringan>) {
    setSaringan((lama) => ({ ...lama, ...sebagian }));
  }

  function ubahKolom(baru: string[]) {
    setKolom(baru);
    try {
      window.localStorage.setItem(KUNCI_KOLOM, JSON.stringify(baru));
    } catch {
      // Tidak tersimpan: pilihan tetap berlaku sampai halaman ditutup.
    }
  }

  /** Semua kembali ke bawaan: saringan, pencarian, kolom, urutan, dan halaman tabel. */
  function reset() {
    setSaringan(SARINGAN_AWAL);
    setCari("");
    setQ("");
    setKolom(KOLOM_PILIHAN);
    setUlang((n) => n + 1);
    try {
      window.localStorage.removeItem(KUNCI_KOLOM);
    } catch {
      // Tidak apa-apa: kolom bawaan sudah dipasang.
    }
  }

  async function ekspor() {
    setGalat("");
    setMengekspor(true);

    try {
      // Jam setempat, sama dengan waktu di tabel — bukan jam UTC dari toISOString.
      const d = new Date();
      const cap = `${d.getFullYear()}${dua(d.getMonth() + 1)}${dua(d.getDate())}-${dua(d.getHours())}${dua(d.getMinutes())}${dua(d.getSeconds())}`;
      await OpenOrchestratorApi.exportLogs(parameter, `catatan-${cap}.csv`);
    } catch (e) {
      setGalat(errorText(e));
    } finally {
      setMengekspor(false);
    }
  }

  // Tabel dipasang ulang saat saringan berubah: halamannya kembali ke yang
  // pertama — bukan tertinggal di halaman lima dari hasil yang kini hanya
  // punya satu halaman — dan urutan kolom kembali bawaan.
  const kunciTabel = JSON.stringify([saringan, q, ulang]);

  const semuaKolom: (Kolom<LogLine> & { kunci: string })[] = [
    {
      kunci: "Waktu",
      judul: "Waktu",
      sel: (l) => <span className="whitespace-nowrap tabular-nums text-muted">{dateTimeOf(l.loggedAt)}</span>,
      urut: (l) => l.id,
    },
    { kunci: "Tingkat", judul: "Tingkat", sel: (l) => <Badge value={l.level} />, urut: (l) => l.level },
    {
      kunci: "Robot|satu",
      judul: "Robot|satu",
      sel: (l) => <span className="text-muted">{l.robotName ?? "-"}</span>,
      urut: (l) => l.robotName,
    },
    {
      kunci: "Mesin|satu",
      judul: "Mesin|satu",
      sel: (l) => <span className="text-muted">{l.machineName ?? "-"}</span>,
      urut: (l) => l.machineName,
    },
    {
      kunci: "Host Identity",
      judul: "Host Identity",
      sel: (l) => <span className="whitespace-nowrap text-muted">{l.hostIdentity ?? "-"}</span>,
      urut: (l) => l.hostIdentity,
    },
    {
      kunci: "Proses|satu",
      judul: "Proses|satu",
      sel: (l) => <span className="text-muted">{l.processName ?? "-"}</span>,
      urut: (l) => l.processName,
    },
    { kunci: "Pesan", judul: "Pesan", sel: (l) => <span className="break-all">{tp(l.message)}</span> },
  ];

  const kolomTampil = semuaKolom.filter((k) => !KOLOM_PILIHAN.includes(k.kunci) || kolom.includes(k.kunci));

  // Nilai yang sedang dipilih tetap ada di daftarnya walaupun tidak muncul di
  // rentang ini — supaya tetap terlihat dan bisa dilepas.
  const opsi = useMemo(() => {
    const dengan = (daftar: string[] | undefined, nilai: string) =>
      nilai && !(daftar ?? []).includes(nilai) ? [nilai, ...(daftar ?? [])] : (daftar ?? []);

    return {
      mesin: dengan(pilihan.data?.machines, saringan.mesin),
      proses: dengan(pilihan.data?.processes, saringan.proses),
      host: dengan(pilihan.data?.hostIdentities, saringan.host),
    };
  }, [pilihan.data, saringan.mesin, saringan.proses, saringan.host]);

  const belumAdaNilai = t("Belum ada nilai di catatan rentang ini.");

  return (
    <div>
      <BilahAlat
        aksi={
          <>
            <label className="flex items-center gap-2 text-sm text-muted">
              <input
                type="checkbox"
                checked={ikuti}
                onChange={(e) => setIkuti(e.target.checked)}
                className="h-4 w-4 rounded border-line"
              />
              {t("Ikuti otomatis")}
            </label>
            <Button variant="ghost" onClick={() => log.refetch()} className="px-2.5">
              <span className="sr-only">{t("Muat ulang")}</span>
              <RotateCw size={16} aria-hidden="true" className={cn(log.isFetching && "animate-spin")} />
            </Button>
            <Button variant="primary" onClick={ekspor} disabled={mengekspor}>
              <Download size={16} className="mr-1.5" aria-hidden="true" />
              {mengekspor ? t("Mengekspor...") : t("Ekspor")}
            </Button>
          </>
        }
      >
        <div className="relative w-full sm:w-64">
          <Search size={16} aria-hidden="true" className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
          <input
            type="search"
            value={cari}
            onChange={(e) => setCari(e.target.value)}
            placeholder={t("Cari pesan...")}
            aria-label={t("Cari pesan...")}
            className={cn(kelasIsian, "pl-9")}
          />
        </div>

        <Munculan
          label={t("Kolom")}
          lebar="w-56"
          kelasTombol={(buka) =>
            cn(
              "inline-flex items-center gap-1.5 rounded-lg px-2.5 py-2 text-sm font-medium text-ink transition hover:bg-slate-100",
              buka && "bg-slate-100",
            )
          }
          tombol={
            <>
              <Columns3 size={16} aria-hidden="true" />
              {t("Kolom")}
              <ChevronDown size={14} aria-hidden="true" className="text-muted" />
            </>
          }
        >
          {() => (
            <>
              <DaftarSakelar
                pilihan={KOLOM_PILIHAN}
                terpilih={kolom}
                labelDaftar={t("Kolom")}
                label={(k) => t(k)}
                onUbah={ubahKolom}
              />
              <p className="mt-1 border-t border-line px-2.5 pb-1 pt-2 text-xs text-muted">
                {t("Waktu dan Pesan selalu tampil.")}
              </p>
            </>
          )}
        </Munculan>

        <span className="hidden h-6 w-px bg-line sm:block" aria-hidden="true" />

        <button
          type="button"
          onClick={() => setTampilSaringan(!tampilSaringan)}
          aria-expanded={tampilSaringan}
          className="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-2 text-sm font-medium text-ink transition hover:bg-slate-100"
        >
          <ListFilter size={16} aria-hidden="true" />
          {t("Saringan")}
          {jumlahAktif ? (
            <span className="rounded-full bg-brand px-1.5 text-[11px] font-semibold leading-5 text-white">{jumlahAktif}</span>
          ) : null}
          {tampilSaringan ? (
            <ChevronUp size={14} aria-hidden="true" className="text-muted" />
          ) : (
            <ChevronDown size={14} aria-hidden="true" className="text-muted" />
          )}
        </button>

        <span className="hidden h-6 w-px bg-line sm:block" aria-hidden="true" />

        <button
          type="button"
          onClick={reset}
          className="rounded-lg px-2 py-2 text-sm font-medium text-brand transition hover:underline hover:underline-offset-2"
        >
          {t("Kembalikan ke bawaan")}
        </button>
      </BilahAlat>

      {tampilSaringan ? (
        <div role="group" aria-label={t("Saringan")} className="-mt-1 mb-4 flex flex-wrap items-center gap-2">
          <PilSaringan label={t("Waktu")} nilai={labelWaktu(saringan, t)} aktif={!!saringan.waktu} lebar="w-80">
            {(tutup) => (
              <PanelWaktu
                saringan={saringan}
                onPilih={(waktu, dari, sampai) => {
                  ubah({ waktu, dari, sampai });
                  tutup();
                }}
              />
            )}
          </PilSaringan>

          <PilSaringan
            label={t("Tingkat")}
            nilai={saringan.tingkat.length ? saringan.tingkat.join(", ") : t("Semua")}
            aktif={saringan.tingkat.length > 0}
            lebar="w-56"
          >
            {() => (
              <DaftarCentang
                pilihan={TINGKAT}
                terpilih={saringan.tingkat}
                labelSemua={t("Semua")}
                onUbah={(tingkat) => ubah({ tingkat })}
                tanda={(x) => <span aria-hidden="true" className={cn("h-2 w-2 rounded-full", WARNA_TINGKAT[x] ?? "bg-slate-400")} />}
              />
            )}
          </PilSaringan>

          <PilSaringan label={t("Mesin|satu")} nilai={saringan.mesin || t("Semua")} aktif={!!saringan.mesin}>
            {(tutup) => (
              <DaftarPilihan
                pilihan={opsi.mesin}
                nilai={saringan.mesin}
                labelSemua={t("Semua")}
                kosong={belumAdaNilai}
                onPilih={(mesin) => {
                  ubah({ mesin });
                  tutup();
                }}
              />
            )}
          </PilSaringan>

          <PilSaringan label={t("Proses|satu")} nilai={saringan.proses || t("Semua")} aktif={!!saringan.proses}>
            {(tutup) => (
              <DaftarPilihan
                pilihan={opsi.proses}
                nilai={saringan.proses}
                labelSemua={t("Semua")}
                kosong={belumAdaNilai}
                onPilih={(proses) => {
                  ubah({ proses });
                  tutup();
                }}
              />
            )}
          </PilSaringan>

          <PilSaringan label={t("Host Identity")} nilai={saringan.host || t("Semua")} aktif={!!saringan.host}>
            {(tutup) => (
              <DaftarPilihan
                pilihan={opsi.host}
                nilai={saringan.host}
                labelSemua={t("Semua")}
                kosong={belumAdaNilai}
                onPilih={(host) => {
                  ubah({ host });
                  tutup();
                }}
              />
            )}
          </PilSaringan>

          {saringan.jobId ? (
            <span className="inline-flex items-center gap-1.5 rounded-lg border border-brandLine bg-brandSoft py-1.5 pl-3 pr-1.5 text-sm text-ink">
              <span className="font-semibold">{t("Pekerjaan|satu")}:</span>
              <span className="font-medium text-brand">{job.data?.processName ?? "…"}</span>
              <code className="text-xs text-muted">{saringan.jobId.slice(0, 8)}</code>
              <button
                type="button"
                onClick={() => ubah({ jobId: "" })}
                aria-label={t("Hapus saringan pekerjaan")}
                title={t("Hapus saringan pekerjaan")}
                className="rounded p-0.5 text-muted transition hover:bg-slate-100 hover:text-ink"
              >
                <X size={14} />
              </button>
            </span>
          ) : null}
        </div>
      ) : null}

      {/* Galat saringan dari server — mis. rentang khusus dari alamat yang
          tidak bisa dibaca — tampil di sini, bukan sebagai tabel kosong. */}
      <Galat pesan={galat || (log.error ? errorText(log.error) : "")} className="mb-4" />

      <Card className={cn("transition-opacity", log.isPlaceholderData && "opacity-60")}>
        <DataTable
          key={kunciTabel}
          data={log.data ?? []}
          kunci={(l) => String(l.id)}
          perHalaman={50}
          kosong={
            log.isLoading || !siap
              ? "Memuat..."
              : disaring
                ? "Tidak ada catatan yang cocok dengan saringan."
                : "Belum ada catatan di folder ini."
          }
          aksiKosong={
            disaring && !log.isLoading ? <Button onClick={reset}>{t("Reset saringan")}</Button> : undefined
          }
          kolom={kolomTampil}
        />
      </Card>

      <p className="mt-3 text-xs text-muted">
        {t("Paling banyak {0} baris terbaru yang cocok dengan saringan. Ekspor mengunduh sampai 50.000 baris dengan saringan yang sama.", BATAS_BARIS)}
      </p>
    </div>
  );
}

/** "Semua", "1 jam terakhir", atau rentang khusus "30 Sep 08.00 – 30 Sep 12.00". */
function labelWaktu(s: Saringan, t: (teks: string, ...nilai: (string | number)[]) => string): string {
  if (s.waktu !== "custom") return t(RENTANG.find((r) => r.kunci === s.waktu)?.label ?? "Semua");

  if (s.dari && s.sampai) return `${dateTimeOf(s.dari)} – ${dateTimeOf(s.sampai)}`;
  if (s.dari) return t("Sejak {0}", dateTimeOf(s.dari));
  if (s.sampai) return t("Sampai {0}", dateTimeOf(s.sampai));
  return t("Rentang khusus");
}

const dua = (n: number) => String(n).padStart(2, "0");

/**
 * Sekali ulang untuk gangguan jaringan atau server (5xx), tidak untuk 4xx:
 * saringan yang ditolak server ("Waktu awal harus sebelum waktu akhir") tidak
 * sembuh dengan diulang, dan galatnya harus langsung tampil — bukan menunggu
 * percobaan ulang yang ditahan selama tab peramban tidak terlihat.
 */
function ulangiKalauBukanGalatSaringan(kali: number, e: unknown): boolean {
  const status = (e as { response?: { status?: number } })?.response?.status ?? 0;
  return kali < 1 && (status === 0 || status >= 500);
}

/** ISO → nilai isian datetime-local (waktu setempat peramban, tanpa detik). */
function isoKeLokal(iso: string): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  return `${d.getFullYear()}-${dua(d.getMonth() + 1)}-${dua(d.getDate())}T${dua(d.getHours())}:${dua(d.getMinutes())}`;
}

/**
 * Pilihan waktu: rentang siap pakai, atau rentang khusus dengan dua isian.
 *
 * Isian datetime-local berarti waktu setempat PERAMBAN — sama dengan waktu
 * yang tampil di tabel — dan dikirim ke server sebagai ISO dengan zona.
 */
function PanelWaktu({
  saringan,
  onPilih,
}: {
  saringan: Saringan;
  onPilih: (waktu: string, dari: string, sampai: string) => void;
}) {
  const { t } = useT();
  const [khusus, setKhusus] = useState(saringan.waktu === "custom");
  const [dari, setDari] = useState(isoKeLokal(saringan.dari));
  const [sampai, setSampai] = useState(isoKeLokal(saringan.sampai));
  const [galat, setGalat] = useState("");

  function terapkan() {
    if (!dari && !sampai) return setGalat(t("Isi waktu awal, waktu akhir, atau keduanya."));

    const awal = dari ? new Date(dari).toISOString() : "";
    const akhir = sampai ? new Date(sampai).toISOString() : "";

    if (awal && akhir && awal >= akhir) return setGalat(t("Waktu awal harus sebelum waktu akhir."));

    onPilih("custom", awal, akhir);
  }

  return (
    <div>
      <div role="listbox" aria-label={t("Waktu")}>
        {RENTANG.map((r) => (
          <Pilihan key={r.kunci || "semua"} terpilih={!khusus && saringan.waktu === r.kunci} onPilih={() => onPilih(r.kunci, "", "")}>
            {t(r.label)}
          </Pilihan>
        ))}
        <Pilihan terpilih={khusus} onPilih={() => setKhusus(true)}>
          {t("Rentang khusus")}…
        </Pilihan>
      </div>

      {khusus ? (
        <div className="mt-1.5 space-y-2 border-t border-line px-1 pb-1 pt-2.5">
          <label className="block">
            <span className="mb-1 block text-xs font-medium text-muted">{t("Dari")}</span>
            <input type="datetime-local" value={dari} onChange={(e) => setDari(e.target.value)} className={kelasIsian} />
          </label>
          <label className="block">
            <span className="mb-1 block text-xs font-medium text-muted">{t("Sampai")}</span>
            <input type="datetime-local" value={sampai} onChange={(e) => setSampai(e.target.value)} className={kelasIsian} />
          </label>
          {galat ? <p className="text-xs text-danger">{galat}</p> : null}
          <div className="flex justify-end">
            <Button variant="primary" onClick={terapkan}>
              {t("Terapkan")}
            </Button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
