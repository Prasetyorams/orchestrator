"use client";

import { useRef, useState } from "react";
import { CheckCircle2, CloudUpload, TriangleAlert } from "lucide-react";
import { OpenOrchestratorApi, dibatalkan, errorText, type UploadPackageResult } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { ukuranBerkas } from "@/lib/utils";
import { Button, Galat } from "@/components/ui/primitives";
import { Dialog } from "@/components/Dialog";
import { PemilihBerkas } from "@/components/PemilihBerkas";

const POLA_PAKET = /\.(nupkg|zip)$/i;

/**
 * Unggah Paket (V17): memasukkan paket yang sudah jadi ke umpan tanpa
 * OpenStudio. Pasangan Terbitkan dari Studio — hasilnya masuk ke umpan,
 * proses, dan riwayat versi yang sama.
 *
 * Nama, versi, dan titik masuk TIDAK diketik di sini: server membacanya dari
 * paketnya (.nuspec, project.json, nama berkas) dan memeriksanya dengan cara
 * yang sama dengan robot. Yang tampil sesudahnya adalah yang benar-benar
 * tersimpan, termasuk peringatan yang tidak bisa dipastikan server.
 */
export function DialogUnggahPaket({ onTutup, onSelesai }: { onTutup: () => void; onSelesai: () => void }) {
  const { t } = useT();
  const pembatal = useRef<AbortController | null>(null);

  const [berkas, setBerkas] = useState<File | null>(null);
  /** null: tidak sedang mengunggah; 0..1: bagian yang sudah terkirim. */
  const [kemajuan, setKemajuan] = useState<number | null>(null);
  const [galat, setGalat] = useState("");
  const [hasil, setHasil] = useState<UploadPackageResult | null>(null);

  const mengunggah = kemajuan !== null;
  // Seluruh berkas sudah terkirim; server sedang membaca dan menyimpannya.
  const memeriksa = mengunggah && kemajuan >= 1;

  function ambil(f: File | null | undefined) {
    if (mengunggah || !f) return;
    setGalat("");
    setHasil(null);

    if (!POLA_PAKET.test(f.name)) {
      setBerkas(null);
      setGalat(t("{0} bukan paket. Pilih berkas .nupkg atau .zip.", f.name));
      return;
    }
    setBerkas(f);
  }

  async function unggah() {
    if (!berkas || mengunggah) return;

    setGalat("");
    setKemajuan(0);
    const pengendali = new AbortController();
    pembatal.current = pengendali;

    try {
      const jawaban = await OpenOrchestratorApi.uploadPackage(berkas, {
        signal: pengendali.signal,
        onProgress: (bagian) => setKemajuan(Math.min(bagian, 1)),
      });
      setHasil(jawaban);
      setBerkas(null);
      onSelesai();
    } catch (e) {
      if (!dibatalkan(e)) setGalat(errorText(e));
    } finally {
      pembatal.current = null;
      setKemajuan(null);
    }
  }

  function tutup() {
    // Membatalkan kiriman yang belum selesai: server tidak menyimpan apa pun
    // dari unggahan yang terputus.
    pembatal.current?.abort();
    onTutup();
  }

  return (
    <Dialog
      judul={t("Unggah Paket")}
      terbuka
      onTutup={tutup}
      lebar="max-w-xl"
      aksi={
        <>
          <Button onClick={tutup} disabled={memeriksa}>
            {hasil && !berkas ? t("Tutup") : t("Batal")}
          </Button>
          <Button variant="primary" onClick={unggah} disabled={!berkas || mengunggah}>
            <CloudUpload size={14} className="mr-1.5" />
            {t("Unggah")}
          </Button>
        </>
      }
    >
      <div className="mb-3 rounded-lg border border-line bg-slate-50 px-3 py-2 text-xs text-muted">
        <p className="mb-1 font-medium text-ink">{t("Format yang diterima")}</p>
        <ul className="list-disc space-y-0.5 pl-4">
          <li>{t(".nupkg dari OpenStudio (Terbitkan → Folder lokal).")}</li>
          <li>{t(".zip proyek OpenStudio, mis. yang diunduh dari halaman ini.")}</li>
          <li>{t("Nama, versi, dan titik masuk dibaca dari paketnya; versi yang sudah ada ditolak.")}</li>
        </ul>
      </div>

      <PemilihBerkas
        accept=".nupkg,.zip"
        judul={t("Seret berkas paket ke sini")}
        petunjuk={t("atau klik untuk memilih berkas .nupkg / .zip")}
        berkas={berkas}
        kemajuan={kemajuan}
        memeriksa={t("Terkirim. Memeriksa dan menyimpan paket...")}
        onPilih={ambil}
        onHapus={() => setBerkas(null)}
      />

      <Galat pesan={galat} className="mt-3" />

      {hasil ? <HasilUnggah hasil={hasil} /> : null}
    </Dialog>
  );
}

/** Yang benar-benar tersimpan, dengan peringatan dari server (sudah diterjemahkan). */
function HasilUnggah({ hasil }: { hasil: UploadPackageResult }) {
  const { t, tp } = useT();

  const proses = {
    CREATED: t("Proses {0} dibuat di folder bawaan.", hasil.processName),
    UPDATED: t("Proses {0} kini memakai versi ini.", hasil.processName),
    UNCHANGED: t("Proses {0} tetap di versinya sekarang, yang lebih baru.", hasil.processName),
  }[hasil.processChange];

  return (
    <div className="mt-3 rounded-lg border border-line px-3 py-2.5 text-sm">
      <p className="flex items-center gap-2 font-medium text-ok">
        <CheckCircle2 size={16} className="shrink-0" />
        {t("{0} versi {1} diunggah.", hasil.name, hasil.version)}
      </p>
      <ul className="mt-1.5 space-y-0.5 pl-6 text-xs text-muted">
        <li>
          {t("Titik Masuk")}: <span className="text-ink">{hasil.entryPoint}</span> · {ukuranBerkas(hasil.sizeBytes)}
        </li>
        <li>{proses}</li>
        {hasil.libraries?.length ? (
          <li>{t("Library dari feed yang dipasang robot sendiri: {0}", hasil.libraries.join(", "))}</li>
        ) : null}
        {hasil.layout === "NUPKG" ? (
          <li>{t("Isi lib/net45/ disimpan di akar paket, sama dengan hasil Terbitkan dari Studio, supaya terbaca robot.")}</li>
        ) : hasil.layout === "ZIP_IN_FOLDER" ? (
          <li>{t("Isi folder di dalam zip dipindah ke akar paket, supaya terbaca robot.")}</li>
        ) : null}
      </ul>
      {hasil.warnings.length ? (
        <ul className="mt-2 space-y-1">
          {hasil.warnings.map((w) => (
            <li key={w} className="flex gap-2 text-xs text-warn">
              <TriangleAlert size={14} className="mt-0.5 shrink-0" />
              <span>{tp(w)}</span>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
