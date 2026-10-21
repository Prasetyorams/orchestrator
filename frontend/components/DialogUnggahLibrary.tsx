"use client";

import { useRef, useState } from "react";
import { CheckCircle2, CloudUpload, TriangleAlert } from "lucide-react";
import { OpenOrchestratorApi, dibatalkan, errorText, type UploadLibraryResult } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { ukuranBerkas } from "@/lib/utils";
import { Button, Galat } from "@/components/ui/primitives";
import { Dialog } from "@/components/Dialog";
import { PemilihBerkas } from "@/components/PemilihBerkas";

const POLA_LIBRARY = /\.nupkg$/i;

/**
 * Unggah Library (V20): .nupkg hasil Terbitkan proyek Library di OpenStudio
 * masuk ke feed library. Id dan versinya dibaca server dari .nuspec-nya;
 * robot memasangnya sendiri sebelum menjalankan proses yang memakainya.
 */
export function DialogUnggahLibrary({ onTutup, onSelesai }: { onTutup: () => void; onSelesai: () => void }) {
  const { t } = useT();
  const pembatal = useRef<AbortController | null>(null);

  const [berkas, setBerkas] = useState<File | null>(null);
  /** null: tidak sedang mengunggah; 0..1: bagian yang sudah terkirim. */
  const [kemajuan, setKemajuan] = useState<number | null>(null);
  const [galat, setGalat] = useState("");
  const [hasil, setHasil] = useState<UploadLibraryResult | null>(null);

  const mengunggah = kemajuan !== null;
  const memeriksa = mengunggah && kemajuan >= 1;

  function ambil(f: File | null | undefined) {
    if (mengunggah || !f) return;
    setGalat("");
    setHasil(null);

    if (!POLA_LIBRARY.test(f.name)) {
      setBerkas(null);
      setGalat(t("{0} bukan library. Pilih berkas .nupkg hasil Terbitkan proyek Library.", f.name));
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
      const jawaban = await OpenOrchestratorApi.uploadLibrary(berkas, {
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
    pembatal.current?.abort();
    onTutup();
  }

  return (
    <Dialog
      judul={t("Unggah Library")}
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
          <li>{t(".nupkg hasil Terbitkan proyek Library di OpenStudio (tag OpenStudio Library, satu lib/net45/<Id>.dll).")}</li>
          <li>{t("Id dan versi dibaca dari .nuspec-nya; versi yang sudah ada ditolak.")}</li>
          <li>{t("Robot memasang library ini sendiri sebelum menjalankan proses yang memakainya.")}</li>
        </ul>
      </div>

      <PemilihBerkas
        accept=".nupkg"
        judul={t("Seret berkas library ke sini")}
        petunjuk={t("atau klik untuk memilih berkas .nupkg")}
        berkas={berkas}
        kemajuan={kemajuan}
        memeriksa={t("Terkirim. Memeriksa dan menyimpan library...")}
        onPilih={ambil}
        onHapus={() => setBerkas(null)}
      />

      <Galat pesan={galat} className="mt-3" />

      {hasil ? <HasilUnggah hasil={hasil} /> : null}
    </Dialog>
  );
}

function HasilUnggah({ hasil }: { hasil: UploadLibraryResult }) {
  const { t, tp } = useT();
  const dependensi = Object.entries(hasil.dependencies).map(([id, versi]) => `${id} ${versi}`.trim());

  return (
    <div className="mt-3 rounded-lg border border-line px-3 py-2.5 text-sm">
      <p className="flex items-center gap-2 font-medium text-ok">
        <CheckCircle2 size={16} className="shrink-0" />
        {t("{0} versi {1} diunggah.", hasil.id, hasil.version)}
      </p>
      <ul className="mt-1.5 space-y-0.5 pl-6 text-xs text-muted">
        <li>
          {t("DLL")}: <span className="text-ink">{hasil.assemblyFile}</span> · {ukuranBerkas(hasil.sizeBytes)}
        </li>
        {dependensi.length ? <li>{t("Dependensi: {0}", dependensi.join(", "))}</li> : null}
        <li className="break-all">SHA-256: {hasil.sha256}</li>
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
