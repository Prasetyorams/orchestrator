"use client";

import { useEffect, useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { OpenOrchestratorApi, errorText } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { cn, dateTimeOf } from "@/lib/utils";
import { Button, Card, Galat } from "@/components/ui/primitives";
import { DataTable } from "@/components/DataTable";
import { kelasIsian } from "@/components/Dialog";
import { JudulHalaman } from "@/components/HalamanFolder";

/**
 * Jejak audit: siapa mengubah apa, dan kapan.
 *
 * Hanya perubahan yang dilakukan orang — lalu lintas robot tidak tercatat di
 * sini. Tidak ada tombol hapus, dengan sengaja: jejak yang bisa dibersihkan
 * oleh orang yang jejaknya tercatat bukan lagi jejak.
 */
export default function Audit() {
  const { t } = useT();

  const [komponen, setKomponen] = useState("");
  const [kata, setKata] = useState("");
  const [tunda, setTunda] = useState("");

  // Ditunda seperti pencarian di bilah atas: mengetik satu nama tidak perlu
  // menjadi sepuluh permintaan.
  useEffect(() => {
    const id = setTimeout(() => setTunda(kata.trim()), 300);
    return () => clearTimeout(id);
  }, [kata]);

  const daftarKomponen = useQuery({
    queryKey: ["audit", "komponen"],
    queryFn: OpenOrchestratorApi.auditComponents,
    retry: false,
  });

  const jejak = useQuery({
    queryKey: ["audit", komponen, tunda],
    queryFn: () => OpenOrchestratorApi.audit({ component: komponen, q: tunda, limit: 500 }),
    refetchInterval: 30_000,
    placeholderData: keepPreviousData,
    retry: false,
  });

  return (
    <div>
      <JudulHalaman
        judul={t("Audit")}
        konteks="tenant"
        aksi={<Button onClick={() => jejak.refetch()}>{t("Muat ulang")}</Button>}
      >
        <select
          value={komponen}
          onChange={(e) => setKomponen(e.target.value)}
          aria-label={t("Komponen")}
          className={cn(kelasIsian, "w-48")}
        >
          <option value="">{t("Semua komponen")}</option>
          {(daftarKomponen.data ?? []).map((k) => (
            <option key={k.component} value={k.component}>
              {`${t(k.component)} (${k.total})`}
            </option>
          ))}
        </select>

        <input
          type="search"
          value={kata}
          onChange={(e) => setKata(e.target.value)}
          placeholder={t("Cari pengguna atau sasaran...")}
          aria-label={t("Cari pengguna atau sasaran...")}
          className={cn(kelasIsian, "w-64")}
        />
      </JudulHalaman>

      <Galat pesan={jejak.isError ? errorText(jejak.error) : ""} className="mb-4" />

      <Card className={cn("transition-opacity", jejak.isPlaceholderData && "opacity-60")}>
        <DataTable
          data={jejak.data ?? []}
          kunci={(a) => String(a.id)}
          perHalaman={50}
          kosong={jejak.isLoading ? "Memuat..." : "Belum ada perubahan yang tercatat."}
          kolom={[
            {
              judul: "Waktu",
              sel: (a) => <span className="whitespace-nowrap tabular-nums text-muted">{dateTimeOf(a.createdAt)}</span>,
              urut: (a) => a.id,
            },
            { judul: "Pengguna", sel: (a) => <span className="font-medium">{a.username ?? "-"}</span>, urut: (a) => a.username },
            { judul: "Komponen", sel: (a) => t(a.component), urut: (a) => a.component },
            { judul: "Aksi", sel: (a) => t(a.action), urut: (a) => a.action },
            { judul: "Sasaran", sel: (a) => <span className="break-all">{a.target ?? "-"}</span> },
            {
              judul: "Rincian",
              sel: (a) => <code className="break-all text-[11px] text-muted">{a.detail ?? ""}</code>,
            },
          ]}
        />
      </Card>

      <p className="mt-3 text-xs text-muted">
        {t("Yang tercatat hanya perubahan yang berhasil dilakukan orang — denyut, catatan, dan laporan robot tidak.")}
      </p>
    </div>
  );
}
