"use client";

import { useMemo, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ForgeHubApi, errorText, type FolderNode } from "@/lib/api";
import { useFolder } from "@/lib/folder";
import { useT } from "@/lib/i18n";
import { Button, Galat } from "@/components/ui/primitives";
import { Dialog, Isian, kelasIsian } from "@/components/Dialog";
import { PilihFolder, cabang } from "@/components/PilihFolder";

/**
 * Buat atau sunting folder bersama: nama, keterangan, dan induknya.
 *
 * Induk dipilih dari pohon yang sama dengan bilah folder. Saat menyunting,
 * folder itu sendiri dan seluruh keturunannya tidak ada di pilihan — memindah
 * folder ke dalam cabangnya sendiri akan memutusnya dari pohon, dan server
 * menolaknya juga.
 */
export function DialogFolder({
  awal,
  indukAwal,
  onTutup,
  onSelesai,
}: {
  /** Folder yang disunting; kosong berarti membuat folder baru. */
  awal?: FolderNode | null;
  indukAwal?: string | null;
  onTutup: () => void;
  onSelesai?: (id: string) => void;
}) {
  const { t } = useT();
  const klien = useQueryClient();
  const { pohon } = useFolder();

  const [nama, setNama] = useState(awal?.name ?? "");
  const [ket, setKet] = useState(awal?.description ?? "");
  const [induk, setInduk] = useState(awal ? (awal.parentId ?? "") : (indukAwal ?? ""));
  const [galat, setGalat] = useState("");

  const sembunyikan = useMemo(
    () => (awal ? cabang(pohon?.folders ?? [], awal.id) : undefined),
    [awal, pohon?.folders],
  );

  const simpan = useMutation({
    mutationFn: async () => {
      const body = { name: nama.trim(), description: ket.trim(), parentId: induk || null };

      if (awal) {
        await ForgeHubApi.updateFolder(awal.id, body);
        return awal.id;
      }

      return (await ForgeHubApi.createFolder(body)).id;
    },
    onSuccess: async (id) => {
      await klien.invalidateQueries({ queryKey: ["folders"] });
      onSelesai?.(id);
      onTutup();
    },
    onError: (e) => setGalat(errorText(e)),
  });

  function kirim() {
    setGalat("");
    if (!nama.trim()) return setGalat(t("Nama folder wajib diisi."));
    if (nama.includes("/") || nama.includes("\\")) return setGalat(t("Nama folder tidak boleh memuat garis miring."));

    simpan.mutate();
  }

  return (
    <Dialog
      judul={awal ? `${t("Sunting folder")} — ${awal.name}` : t("Folder baru")}
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
      <form
        onSubmit={(e) => {
          e.preventDefault();
          kirim();
        }}
      >
        <Isian label={t("Nama")}>
          <input value={nama} onChange={(e) => setNama(e.target.value)} maxLength={120} className={kelasIsian} />
        </Isian>

        <Isian label={t("Keterangan")}>
          <input value={ket} onChange={(e) => setKet(e.target.value)} maxLength={400} className={kelasIsian} />
        </Isian>

        <Isian
          label={t("Induk")}
          petunjuk={
            awal?.isDefault
              ? t("Folder bawaan harus tetap di akar.")
              : awal
                ? undefined
                : t("Folder baru mewarisi pengguna dan robot induknya.")
          }
        >
          <PilihFolder
            nilai={induk}
            onUbah={setInduk}
            pilihan="induk"
            akar={t("(Akar — tanpa induk)")}
            sembunyikan={sembunyikan}
            disabled={!!awal?.isDefault}
          />
        </Isian>

        <Galat pesan={galat} className="mt-2" />

        {/* Supaya Enter di isian mana pun ikut menyimpan. */}
        <button type="submit" className="sr-only" tabIndex={-1} aria-hidden="true" />
      </form>
    </Dialog>
  );
}
