"use client";

import { useCallback, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ForgeHubApi, errorText, unduh, type Package } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { kelompokkanPaket, type RingkasanPaket } from "@/lib/paket";
import { dateTimeOf, kunciUrutVersi, ukuranBerkas } from "@/lib/utils";
import { Button, Card } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { Dialog } from "@/components/Dialog";

export default function Paket() {
  const { t } = useT();

  const [dibuka, setDibuka] = useState<string | null>(null);
  const [galat, setGalat] = useState("");

  const paket = useQuery({ queryKey: ["packages"], queryFn: ForgeHubApi.packages });
  // Satu baris per PAKET dengan versi tertingginya; API memberi satu baris per versi.
  const ringkasan = useMemo(() => kelompokkanPaket(paket.data ?? []), [paket.data]);

  // Dicari ulang dari data terkini, bukan disimpan sebagai objek: kalau
  // daftarnya disegarkan selagi dialog terbuka, yang tampil tetap yang baru.
  const detail = ringkasan.find((r) => r.name === dibuka) ?? null;

  // Stabil, supaya Dialog tidak menjalankan ulang efek fokusnya tiap kali
  // halaman ini dirender ulang oleh penarikan data.
  const tutup = useCallback(() => setDibuka(null), []);

  const unduhVersi = useCallback(async (p: Package) => {
    setGalat("");
    try {
      await unduh(ForgeHubApi.packageUrl(p.name, p.version), `${p.name}.${p.version}.zip`);
    } catch (e) {
      setGalat(errorText(e));
    }
  }, []);

  return (
    <div className="space-y-4">
      <h1 className="text-lg font-semibold text-ink">{t("Paket")}</h1>

      {galat ? <p className="rounded-lg bg-red-50 px-4 py-2 text-sm text-danger">{galat}</p> : null}
      {paket.isError ? (
        <p className="rounded-lg bg-red-50 px-4 py-2 text-sm text-danger">{errorText(paket.error)}</p>
      ) : null}

      <p className="text-sm text-muted">
        {t("Satu baris per paket, dengan versi tertingginya. Klik ganda untuk melihat semua versinya.")}
      </p>

      <Card>
        <DataTable
          data={ringkasan}
          kunci={(r) => r.name}
          onBuka={(r) => setDibuka(r.name)}
          kosong={
            paket.isLoading
              ? "Memuat..."
              : "Belum ada paket yang diterbitkan. Terbitkan dari Studio: tab Design → grup ForgeHub → Terbitkan."
          }
          kolom={[
            {
              judul: "Nama",
              sel: (r) => (
                <button
                  type="button"
                  onClick={() => setDibuka(r.name)}
                  className="font-medium text-ink hover:text-info hover:underline"
                >
                  {r.name}
                </button>
              ),
              urut: (r) => r.name.toLowerCase(),
            },
            {
              judul: "Versi Terbaru",
              sel: (r) => <span className="font-medium tabular-nums">{r.terbaru.version}</span>,
              urut: (r) => kunciUrutVersi(r.terbaru.version),
            },
            {
              judul: "Jumlah Versi",
              sel: (r) => <span className="tabular-nums">{r.versi.length}</span>,
              urut: (r) => r.versi.length,
            },
            {
              judul: "Keterangan",
              sel: (r) => <span className="text-muted">{r.terbaru.description ?? "-"}</span>,
            },
            {
              judul: "Diterbitkan",
              sel: (r) => <span className="text-muted">{dateTimeOf(r.terbaru.publishedAt)}</span>,
              urut: (r) => r.terbaru.publishedAt,
            },
            {
              judul: "Diterbitkan Oleh",
              sel: (r) => <span className="text-muted">{r.terbaru.publishedBy ?? "-"}</span>,
              urut: (r) => r.terbaru.publishedBy,
            },
            {
              judul: "",
              sel: (r) => (
                <div className="flex justify-end gap-1.5">
                  <Button variant="ghost" onClick={() => unduhVersi(r.terbaru)}>
                    {t("Unduh")}
                  </Button>
                  <Button variant="ghost" onClick={() => setDibuka(r.name)}>
                    {t("Riwayat versi")}
                  </Button>
                </div>
              ),
            },
          ]}
        />
      </Card>

      {detail ? <DialogVersi paket={detail} onTutup={tutup} onUnduh={unduhVersi} /> : null}
    </div>
  );
}

function DialogVersi({
  paket,
  onTutup,
  onUnduh,
}: {
  paket: RingkasanPaket;
  onTutup: () => void;
  onUnduh: (p: Package) => void;
}) {
  const { t } = useT();

  return (
    <Dialog judul={`${paket.name} · ${t("Riwayat versi")}`} terbuka onTutup={onTutup} lebar="max-w-3xl">
      <div className="max-h-[26rem] overflow-y-auto rounded-lg border border-line">
        <DataTable
          data={paket.versi}
          kunci={(v) => v.version}
          perHalaman={0}
          kolom={[
            {
              judul: "Versi",
              sel: (v) => (
                <span className="flex items-center gap-2">
                  <span className="font-medium tabular-nums">{v.version}</span>
                  {v === paket.terbaru ? (
                    <span className="rounded-full bg-ok/10 px-2 py-0.5 text-[11px] font-medium text-ok">
                      {t("Terbaru")}
                    </span>
                  ) : null}
                </span>
              ),
              urut: (v) => kunciUrutVersi(v.version),
            },
            {
              judul: "Diterbitkan",
              sel: (v) => <span className="text-muted">{dateTimeOf(v.publishedAt)}</span>,
              urut: (v) => v.publishedAt,
            },
            { judul: "Diterbitkan Oleh", sel: (v) => v.publishedBy ?? "-" },
            {
              judul: "Titik Masuk",
              sel: (v) => <span className="text-muted">{v.entryPoint ?? "-"}</span>,
            },
            {
              judul: "Ukuran",
              sel: (v) => <span className="tabular-nums text-muted">{ukuranBerkas(v.sizeBytes)}</span>,
              urut: (v) => v.sizeBytes,
            },
            {
              judul: "",
              sel: (v) => (
                <div className="flex justify-end">
                  <Button variant="ghost" onClick={() => onUnduh(v)}>
                    {t("Unduh")}
                  </Button>
                </div>
              ),
            },
          ]}
        />
      </div>
    </Dialog>
  );
}
