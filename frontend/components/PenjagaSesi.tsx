"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { usePathname } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { Clock } from "lucide-react";
import {
  OpenOrchestratorApi,
  TOKEN_KEY,
  alamatMasuk,
  alasanKeluarTerakhir,
  statusGalat,
} from "@/lib/api";
import { useT } from "@/lib/i18n";
import { KEBIJAKAN_CADANGAN, KUNCI_AKTIVITAS, PengawasSesi, type Lingkungan } from "@/lib/pengawasSesi";
import { keluar } from "@/lib/sesi";
import { Button, Galat } from "@/components/ui/primitives";
import { Dialog } from "@/components/Dialog";

/** Event yang menandakan ORANG sedang memakai halaman. Penarikan data tidak pernah termasuk. */
const EVENT_SEKETIKA = ["mousedown", "keydown", "wheel", "touchstart", "pointerdown"] as const;
/** Sering sekali — dibatasi sekali per detik sebelum diteruskan. */
const EVENT_BERUNTUN = ["mousemove", "touchmove", "scroll"] as const;
const BATAS_BERUNTUN_MS = 1000;

/**
 * Batas waktu sesi menganggur di dasbor (V18): satu PengawasSesi per tab,
 * dibuat sekali, dengan event peramban sebagai sumber aktivitas. Dipasang di
 * kerangka halaman, jadi berlaku di semua halaman sesudah masuk.
 *
 * Yang menegakkan tetap server; ini peringatan, hitung mundur, dan keluar
 * tepat waktu — juga di tab lain (event storage).
 */
export function PenjagaSesi() {
  const path = usePathname();
  const pengawas = useRef<PengawasSesi | null>(null);
  const [sisaDetik, setSisaDetik] = useState<number | null>(null);

  // Kebijakan dari server (sumber kebenarannya), disegarkan berkala — juga
  // memeriksa sesinya: yang sudah berakhir dijawab 401 dan keluar. Tidak
  // dihitung aktivitas.
  const sesi = useQuery({
    queryKey: ["sesi"],
    queryFn: OpenOrchestratorApi.session,
    refetchInterval: 120_000,
    refetchOnWindowFocus: false,
  });

  useEffect(() => {
    const ditolakServer = (e: unknown) => {
      if (statusGalat(e) === 401) return false;
      throw e;
    };

    const env: Lingkungan = {
      sekarang: () => Date.now(),
      setiap: (fn, ms) => window.setInterval(fn, ms),
      hentikanSetiap: (h) => window.clearInterval(h as number),
      nanti: (fn, ms) => window.setTimeout(fn, ms),
      batalkanNanti: (h) => window.clearTimeout(h as number),
      baca: (kunci) => {
        try {
          return window.localStorage.getItem(kunci);
        } catch {
          return null;
        }
      },
      tulis: (kunci, nilai) => {
        try {
          window.localStorage.setItem(kunci, nilai);
        } catch {
          /* penyimpanan dilarang: tab ini mengawasi dirinya sendiri */
        }
      },
      lapor: () => OpenOrchestratorApi.sessionActivity().then(() => true, ditolakServer),
      periksa: () => OpenOrchestratorApi.session().then(() => true, ditolakServer),
      peringatan: setSisaDetik,
      berakhir: () => void keluar("idle"),
    };

    const p = new PengawasSesi(env, KEBIJAKAN_CADANGAN);
    pengawas.current = p;
    p.mulai();

    const seketika = () => p.aktivitas();
    let terakhirBeruntun = 0;
    const beruntun = () => {
      const sekarang = Date.now();
      if (sekarang - terakhirBeruntun < BATAS_BERUNTUN_MS) return;
      terakhirBeruntun = sekarang;
      p.aktivitas();
    };
    // capture: gulir di <main> yang menggulir sendiri tidak menggelembung ke window.
    const opsi: AddEventListenerOptions = { passive: true, capture: true };
    for (const e of EVENT_SEKETIKA) window.addEventListener(e, seketika, opsi);
    for (const e of EVENT_BERUNTUN) window.addEventListener(e, beruntun, opsi);

    const terlihat = () => {
      if (document.visibilityState === "visible") p.saatTerlihat();
    };
    document.addEventListener("visibilitychange", terlihat);

    const simpanan = (e: StorageEvent) => {
      if (e.key === KUNCI_AKTIVITAS && e.newValue) p.saatAktivitasTabLain(Number(e.newValue));

      // Tab lain keluar (tombol Keluar, menganggur, atau 401): tab ini ikut.
      if (e.key === TOKEN_KEY && !e.newValue) {
        p.berhenti();
        window.location.href = alamatMasuk(alasanKeluarTerakhir() === "idle" ? "idle" : undefined);
      }
    };
    window.addEventListener("storage", simpanan);

    return () => {
      for (const e of EVENT_SEKETIKA) window.removeEventListener(e, seketika, opsi);
      for (const e of EVENT_BERUNTUN) window.removeEventListener(e, beruntun, opsi);
      document.removeEventListener("visibilitychange", terlihat);
      window.removeEventListener("storage", simpanan);
      p.berhenti();
      pengawas.current = null;
    };
  }, []);

  useEffect(() => {
    if (!sesi.data) return;
    pengawas.current?.ubahKebijakan({
      aktif: sesi.data.idleTimeoutEnabled,
      menit: sesi.data.idleTimeoutMinutes,
      peringatanMenit: sesi.data.warningBeforeLogoutMinutes,
    });
  }, [sesi.data]);

  // Pindah halaman di dasbor (termasuk tombol kembali/maju) = aktivitas orang.
  const pertama = useRef(true);
  useEffect(() => {
    if (pertama.current) {
      pertama.current = false;
      return;
    }
    pengawas.current?.aktivitas();
  }, [path]);

  if (sisaDetik === null) return null;

  return <PeringatanSesi sisaDetik={sisaDetik} pengawas={pengawas} />;
}

function PeringatanSesi({ sisaDetik, pengawas }: { sisaDetik: number; pengawas: React.RefObject<PengawasSesi | null> }) {
  const { t } = useT();
  const [memeriksa, setMemeriksa] = useState(false);
  const [galat, setGalat] = useState("");

  const tetapMasuk = useCallback(async () => {
    if (memeriksa) return;
    setMemeriksa(true);
    setGalat("");
    const diperpanjang = await pengawas.current?.tetapMasuk();
    // Sesi yang sudah berakhir di server dikeluarkan pengawas; yang tersisa hanya kegagalan jaringan.
    if (diperpanjang === false) setGalat(t("Server tidak terjangkau. Coba lagi."));
    setMemeriksa(false);
  }, [memeriksa, pengawas, t]);

  const menit = Math.floor(sisaDetik / 60);
  const detik = String(sisaDetik % 60).padStart(2, "0");

  return (
    <Dialog
      judul={t("Sesi Anda akan berakhir")}
      terbuka
      // Tutup lewat X, Esc, atau klik di luar = orangnya ada: sama dengan Tetap masuk.
      onTutup={tetapMasuk}
      lebar="max-w-md"
      aksi={
        <>
          <Button onClick={() => void keluar("keluar")}>{t("Keluar")}</Button>
          <Button variant="primary" onClick={tetapMasuk} disabled={memeriksa}>
            {memeriksa ? t("Memeriksa...") : t("Tetap masuk")}
          </Button>
        </>
      }
    >
      <div className="flex items-start gap-3">
        <Clock size={22} className="mt-0.5 shrink-0 text-warn" />
        <div>
          <p className="text-sm text-ink">{t("Anda tidak aktif beberapa saat. Sesi akan segera berakhir.")}</p>
          <p className="mt-2 text-sm text-muted" role="timer">
            {t("Keluar otomatis dalam")}{" "}
            <span className="font-semibold tabular-nums text-ink">
              {menit}:{detik}
            </span>
          </p>
        </div>
      </div>
      <Galat pesan={galat} className="mt-3" />
    </Dialog>
  );
}
