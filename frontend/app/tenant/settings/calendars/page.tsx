"use client";

import { useCallback, useMemo, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Download, Pencil, Plus, Trash2, Upload, X } from "lucide-react";
import { OpenOrchestratorApi, errorText, type Calendar, type CalendarDay } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { useIzin } from "@/lib/izin";
import {
  CSV_MAKS_BYTE,
  bacaTeks,
  contohCsv,
  gabung,
  tanggalSah,
  uraiCsv,
  uraiTempelan,
  type BarisSalah,
} from "@/lib/hariNonKerja";
import { dateTimeOf, simpanBlob, tanggalOf } from "@/lib/utils";
import { Button, Card, Galat, IconButton } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { Dialog, Isian, kelasIsian } from "@/components/Dialog";

/**
 * Setelan › Hari Non-Kerja (V16), seperti Non-Working Days di UiPath
 * Orchestrator: kalender berisi tanggal saat pemicu yang memakainya tidak
 * berjalan. Kalender milik penyewa; pemicu di folder mana pun boleh memakainya.
 */
export default function HariNonKerja() {
  const { t } = useT();
  const klien = useQueryClient();
  const { boleh } = useIzin();

  const [sunting, setSunting] = useState<Calendar | null>(null);
  const [baru, setBaru] = useState(false);
  const [galat, setGalat] = useState("");

  const kalender = useQuery({ queryKey: ["calendars"], queryFn: OpenOrchestratorApi.calendars });

  const segarkan = useCallback(() => {
    klien.invalidateQueries({ queryKey: ["calendars"] });
    klien.invalidateQueries({ queryKey: ["calendar"] });
    // Jalan berikutnya pemicu ikut berubah saat tanggal kalendernya berubah.
    klien.invalidateQueries({ queryKey: ["triggers"] });
    klien.invalidateQueries({ queryKey: ["dashboard"] });
  }, [klien]);

  const hapus = useMutation({
    mutationFn: (id: string) => OpenOrchestratorApi.deleteCalendar(id),
    onSuccess: segarkan,
    onError: (e) => setGalat(errorText(e)),
  });

  const tutup = useCallback(() => {
    setBaru(false);
    setSunting(null);
  }, []);

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
        <p className="max-w-3xl text-sm text-muted">
          {t(
            "Pemicu yang memakai kalender tidak berjalan pada tanggal-tanggalnya: jadwalnya melompat ke hari kerja berikutnya. Tanggal dinilai menurut zona waktu pemicu, bukan waktu server.",
          )}
        </p>
        {boleh("calendars.create") ? (
          <Button variant="primary" onClick={() => setBaru(true)}>
            {t("Tambah kalender")}
          </Button>
        ) : null}
      </div>

      <Galat pesan={galat} className="mb-4" />

      <Card>
        <DataTable
          data={kalender.data ?? []}
          kunci={(k) => k.id}
          onBuka={boleh("calendars.update") ? setSunting : undefined}
          kosong={kalender.isLoading ? "Memuat..." : "Belum ada kalender hari non-kerja."}
          kolom={[
            {
              judul: "Nama",
              sel: (k) => (
                <div>
                  <div className="font-medium">{k.name}</div>
                  {k.description ? <div className="text-xs text-muted">{k.description}</div> : null}
                </div>
              ),
              urut: (k) => k.name,
            },
            { judul: "Jumlah tanggal", sel: (k) => <span className="tabular-nums">{k.dayCount}</span>, urut: (k) => k.dayCount },
            {
              judul: "Berikutnya",
              sel: (k) => (
                <span className="text-muted">
                  {k.nextDay ? tanggalOf(k.nextDay) : "-"}
                  {k.upcomingDayCount > 1 ? ` (+${k.upcomingDayCount - 1})` : ""}
                </span>
              ),
              urut: (k) => k.nextDay ?? "9999",
            },
            {
              judul: "Dipakai pemicu",
              sel: (k) => <span className="tabular-nums">{k.triggerCount}</span>,
              urut: (k) => k.triggerCount,
            },
            { judul: "Diubah", sel: (k) => <span className="text-muted">{dateTimeOf(k.updatedAt)}</span>, urut: (k) => k.updatedAt },
            {
              judul: "",
              sel: (k) => (
                <div className="flex justify-end gap-0.5">
                  {boleh("calendars.update") ? (
                    <IconButton label={t("Ubah")} onClick={() => setSunting(k)}>
                      <Pencil size={15} />
                    </IconButton>
                  ) : null}
                  {boleh("calendars.delete") ? (
                    <IconButton
                      label={t("Hapus")}
                      tone="danger"
                      onClick={() => {
                        setGalat("");
                        if (window.confirm(`${t("Yakin menghapus")} "${k.name}"?`)) hapus.mutate(k.id);
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

      {baru || sunting ? (
        <DialogKalender key={sunting?.id ?? "baru"} awal={sunting} onTutup={tutup} onSelesai={segarkan} />
      ) : null}
    </div>
  );
}

function DialogKalender({
  awal,
  onTutup,
  onSelesai,
}: {
  awal: Calendar | null;
  onTutup: () => void;
  onSelesai: () => void;
}) {
  const { t } = useT();

  const rinci = useQuery({
    queryKey: ["calendar", awal?.id],
    queryFn: () => OpenOrchestratorApi.calendar(awal!.id),
    enabled: !!awal,
  });

  // Isian baru diisi sesudah rinciannya datang; sampai itu dialognya menunggu.
  if (awal && !rinci.data) {
    return (
      <Dialog judul={`${t("Sunting")} — ${awal.name}`} terbuka onTutup={onTutup} lebar="max-w-3xl">
        {rinci.isError ? <Galat pesan={errorText(rinci.error)} /> : <p className="text-sm text-muted">{t("Memuat...")}</p>}
      </Dialog>
    );
  }

  return (
    <IsiDialogKalender
      awal={awal}
      hariAwal={rinci.data?.days ?? []}
      pemicu={rinci.data?.triggers ?? []}
      onTutup={onTutup}
      onSelesai={onSelesai}
    />
  );
}

function IsiDialogKalender({
  awal,
  hariAwal,
  pemicu,
  onTutup,
  onSelesai,
}: {
  awal: Calendar | null;
  hariAwal: CalendarDay[];
  pemicu: string[];
  onTutup: () => void;
  onSelesai: () => void;
}) {
  const { t } = useT();

  const [nama, setNama] = useState(awal?.name ?? "");
  const [keterangan, setKeterangan] = useState(awal?.description ?? "");
  const [hari, setHari] = useState<CalendarDay[]>(hariAwal);
  const [tanggalBaru, setTanggalBaru] = useState("");
  const [catatanBaru, setCatatanBaru] = useState("");
  const [tempelan, setTempelan] = useState("");
  const [info, setInfo] = useState("");
  const berkasCsv = useRef<HTMLInputElement>(null);
  const [tahun, setTahun] = useState<string>(() => {
    const ini = String(new Date().getFullYear());
    return hariAwal.some((h) => h.date.startsWith(ini)) || hariAwal.length === 0 ? ini : "semua";
  });
  const [galat, setGalat] = useState("");

  const daftarTahun = useMemo(() => {
    const set = new Set(hari.map((h) => h.date.slice(0, 4)));
    set.add(String(new Date().getFullYear()));
    return [...set].sort();
  }, [hari]);

  const tampil = tahun === "semua" ? hari : hari.filter((h) => h.date.startsWith(tahun));

  const simpan = useMutation({
    mutationFn: () => {
      const isi = { name: nama.trim(), description: keterangan.trim() || undefined, days: hari };
      return awal ? OpenOrchestratorApi.updateCalendar(awal.id, isi) : OpenOrchestratorApi.createCalendar(isi);
    },
    onSuccess: () => {
      onSelesai();
      onTutup();
    },
    onError: (e) => setGalat(errorText(e)),
  });

  /** "baris 3: tanggal tidak dikenali (2026-02-30)"; paling banyak lima. */
  function daftarSalah(salah: BarisSalah[]) {
    const lima = salah.slice(0, 5).map((x) => t("baris {0}: {1} ({2})", x.baris, t(x.alasan), x.isi));
    if (salah.length > 5) lima.push(t("dan {0} baris lain", salah.length - 5));
    return lima.join("; ");
  }

  function tambahSatu() {
    setGalat("");
    setInfo("");
    if (!tanggalSah(tanggalBaru)) return setGalat(t("Pilih tanggalnya dulu."));
    setHari((lama) => gabung(lama, [{ date: tanggalBaru, note: catatanBaru.trim() || null }]));
    setTahun(tanggalBaru.slice(0, 4));
    setTanggalBaru("");
    setCatatanBaru("");
  }

  function tambahTempelan() {
    setGalat("");
    setInfo("");
    const { hari: baru, salah } = uraiTempelan(tempelan);
    if (salah.length) return setGalat(t("Belum ditambahkan, ada baris yang salah: {0}", daftarSalah(salah)));
    if (!baru.length) return;
    setHari((lama) => gabung(lama, baru));
    setTahun("semua");
    setTempelan("");
  }

  /**
   * CSV dibaca di peramban dan digabung ke daftar; yang tersimpan tetap
   * lewat Simpan, sama seperti tanggal yang ditambah satu per satu. Satu
   * baris salah menolak seluruh berkas: daftar setengah masuk lebih sulit
   * diperiksa daripada berkas yang diperbaiki lalu diunggah ulang.
   */
  async function unggahCsv(berkas: File) {
    setGalat("");
    setInfo("");
    if (berkas.size > CSV_MAKS_BYTE) return setGalat(t("Berkas {0} terlalu besar; paling besar 1 MB.", berkas.name));

    let hasil;
    try {
      hasil = uraiCsv(bacaTeks(new Uint8Array(await berkas.arrayBuffer())));
    } catch {
      return setGalat(t("Berkas {0} tidak bisa dibaca.", berkas.name));
    }

    if (hasil.masalah) return setGalat(`${berkas.name}: ${t(hasil.masalah)}`);
    if (hasil.salah.length) {
      return setGalat(t("{0} belum ditambahkan, ada baris yang salah: {1}", berkas.name, daftarSalah(hasil.salah)));
    }

    const sudahAda = new Set(hari.map((h) => h.date));
    const baru = hasil.hari.filter((h) => !sudahAda.has(h.date)).length;
    setHari((lama) => gabung(lama, hasil.hari));
    setTahun("semua");
    setInfo(t("{0} tanggal dibaca dari {1}, {2} di antaranya baru. Klik Simpan untuk menyimpannya.", hasil.hari.length, berkas.name, baru));
  }

  /** Contoh untuk tahun yang sedang dilihat, atau tahun ini. */
  function unduhContoh() {
    const y = Number(tahun) || new Date().getFullYear();
    simpanBlob(new Blob([contohCsv(y)], { type: "text/csv;charset=utf-8" }), `contoh-hari-non-kerja-${y}.csv`);
  }

  function kirim() {
    setGalat("");
    setInfo("");
    if (!nama.trim()) return setGalat(t("Nama kalender wajib diisi."));
    simpan.mutate();
  }

  return (
    <Dialog
      judul={awal ? `${t("Sunting")} — ${awal.name}` : t("Tambah kalender")}
      terbuka
      onTutup={onTutup}
      lebar="max-w-3xl"
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
          <input value={nama} onChange={(e) => setNama(e.target.value)} maxLength={120} className={kelasIsian} placeholder={t("mis. Libur Nasional 2026")} />
        </Isian>
        <Isian label={t("Keterangan")}>
          <input value={keterangan} onChange={(e) => setKeterangan(e.target.value)} maxLength={400} className={kelasIsian} />
        </Isian>
      </div>

      <div className="mb-3 grid gap-2 sm:grid-cols-[10rem_1fr_auto] sm:items-end">
        <Isian label={t("Tanggal")}>
          <input type="date" value={tanggalBaru} onChange={(e) => setTanggalBaru(e.target.value)} className={kelasIsian} />
        </Isian>
        <Isian label={t("Catatan|tanggal")}>
          <input
            value={catatanBaru}
            onChange={(e) => setCatatanBaru(e.target.value)}
            maxLength={200}
            onKeyDown={(e) => {
              if (e.key === "Enter") tambahSatu();
            }}
            className={kelasIsian}
            placeholder={t("mis. Hari Raya Idulfitri")}
          />
        </Isian>
        <div className="mb-3">
          <Button onClick={tambahSatu}>
            <Plus size={14} className="mr-1.5" />
            {t("Tambah")}
          </Button>
        </div>
      </div>

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <Button onClick={() => berkasCsv.current?.click()}>
          <Upload size={14} className="mr-1.5" />
          {t("Unggah CSV")}
        </Button>
        <Button variant="ghost" onClick={unduhContoh}>
          <Download size={14} className="mr-1.5" />
          {t("Unduh contoh CSV")}
        </Button>
        <input
          ref={berkasCsv}
          type="file"
          accept=".csv,.txt,text/csv"
          className="hidden"
          onChange={(e) => {
            const berkas = e.target.files?.[0];
            if (berkas) void unggahCsv(berkas);

            // Dikosongkan supaya berkas yang SAMA, sesudah diperbaiki, bisa dipilih lagi.
            e.target.value = "";
          }}
        />
        <span className="text-xs text-muted">
          {t("Kolom: tanggal, sampai (boleh kosong), catatan. Tanggal 2026-12-25 atau 25/12/2026.")}
        </span>
      </div>
      {info ? <p className="mb-3 text-xs text-ok">{info}</p> : null}

      <details className="mb-3 rounded-lg border border-line px-3 py-2">
        <summary className="cursor-pointer text-sm text-muted">{t("Tempel daftar tanggal")}</summary>
        <p className="mt-2 text-xs text-muted">
          {t("Satu baris per tanggal atau rentang, catatan sesudahnya. Contoh:")}
        </p>
        <pre className="mt-1 rounded bg-slate-100 px-2 py-1 text-xs">{"2026-12-25 Natal\n2026-12-24..2026-12-26 Cuti bersama"}</pre>
        <textarea
          value={tempelan}
          onChange={(e) => setTempelan(e.target.value)}
          rows={4}
          className={`${kelasIsian} mt-2 font-mono`}
        />
        <div className="mt-2">
          <Button onClick={tambahTempelan} disabled={!tempelan.trim()}>
            {t("Tambahkan ke kalender")}
          </Button>
        </div>
      </details>

      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <span className="text-sm font-medium">{t("{0} tanggal", hari.length)}</span>
        <select value={tahun} onChange={(e) => setTahun(e.target.value)} className={`${kelasIsian} w-auto`}>
          <option value="semua">{t("Semua tahun")}</option>
          {daftarTahun.map((y) => (
            <option key={y} value={y}>
              {y}
            </option>
          ))}
        </select>
      </div>

      <div className="max-h-72 overflow-y-auto rounded-lg border border-line">
        {tampil.length === 0 ? (
          <p className="px-3 py-4 text-sm text-muted">{t("Belum ada tanggal di tahun ini.")}</p>
        ) : (
          <ul className="divide-y divide-line">
            {tampil.map((h) => (
              <li key={h.date} className="flex items-center gap-3 px-3 py-1.5">
                <span className="w-40 shrink-0 text-sm tabular-nums">{tanggalOf(h.date)}</span>
                <input
                  value={h.note ?? ""}
                  onChange={(e) =>
                    setHari((lama) => lama.map((x) => (x.date === h.date ? { ...x, note: e.target.value || null } : x)))
                  }
                  maxLength={200}
                  className={`${kelasIsian} py-1`}
                  placeholder={t("Catatan|tanggal")}
                />
                <IconButton label={t("Hapus")} tone="danger" onClick={() => setHari((lama) => lama.filter((x) => x.date !== h.date))}>
                  <X size={15} />
                </IconButton>
              </li>
            ))}
          </ul>
        )}
      </div>

      {pemicu.length ? (
        <p className="mt-3 text-xs text-muted">
          {t("Dipakai pemicu: {0}. Jalan berikutnya pemicu itu dihitung ulang saat disimpan.", pemicu.join(", "))}
        </p>
      ) : null}

      <Galat pesan={galat} className="mt-3" />
    </Dialog>
  );
}
