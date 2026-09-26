"use client";

import type { FolderNode } from "@/lib/api";
import { useFolder } from "@/lib/folder";
import { useT } from "@/lib/i18n";
import { kelasIsian } from "@/components/Dialog";

export type Baris = { node: FolderNode; kedalaman: number };

/**
 * Folder dalam urutan pohon — induk lalu anak-anaknya — beserta kedalamannya.
 *
 * Folder bawaan selalu paling atas, sisanya menurut nama tanpa membedakan
 * huruf besar dan dengan angka dibaca sebagai angka ("Folder 2" sebelum
 * "Folder 10"). Folder yang induknya tidak ikut dalam daftar diperlakukan
 * sebagai akar, supaya tidak ada yang hilang dari pandangan.
 */
export function urutPohon(folders: FolderNode[]): Baris[] {
  const ada = new Set(folders.map((f) => f.id));
  const anak = new Map<string, FolderNode[]>();
  const akar: FolderNode[] = [];

  for (const f of folders) {
    if (f.parentId && ada.has(f.parentId)) {
      const daftar = anak.get(f.parentId);
      if (daftar) daftar.push(f);
      else anak.set(f.parentId, [f]);
    } else {
      akar.push(f);
    }
  }

  const urut = (xs: FolderNode[]) =>
    [...xs].sort(
      (a, b) =>
        Number(b.isDefault) - Number(a.isDefault) ||
        a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: "base" }),
    );

  const hasil: Baris[] = [];
  const kunjung = (xs: FolderNode[], kedalaman: number) => {
    for (const f of urut(xs)) {
      hasil.push({ node: f, kedalaman });
      kunjung(anak.get(f.id) ?? [], kedalaman + 1);
    }
  };

  kunjung(akar, 0);
  return hasil;
}

/** Sebuah folder beserta seluruh keturunannya. */
export function cabang(folders: FolderNode[], id: string): Set<string> {
  const hasil = new Set<string>([id]);
  let bertambah = true;

  while (bertambah) {
    bertambah = false;
    for (const f of folders) {
      if (f.parentId && hasil.has(f.parentId) && !hasil.has(f.id)) {
        hasil.add(f.id);
        bertambah = true;
      }
    }
  }

  return hasil;
}

/**
 * Pilihan folder berbentuk pohon, dengan &lt;select&gt; biasa.
 *
 * - "buka": folder yang boleh dibuka, termasuk Folder Saya — tujuan
 *   memindahkan proses, aset, antrean, atau ember.
 * - "induk": semua folder bersama — induk sebuah folder. Folder Saya tidak
 *   pernah bisa menjadi induk.
 */
export function PilihFolder({
  nilai,
  onUbah,
  pilihan,
  akar,
  sembunyikan,
  disabled,
  id,
}: {
  nilai: string;
  onUbah: (id: string) => void;
  pilihan: "buka" | "induk";
  /** Teks untuk pilihan kosong, mis. "(akar)". Tanpa ini tidak ada pilihan kosong. */
  akar?: string;
  /** Folder yang tidak boleh dipilih. */
  sembunyikan?: Set<string>;
  disabled?: boolean;
  id?: string;
}) {
  const { t } = useT();
  const { pohon, bolehDibuka } = useFolder();

  const bersama = pilihan === "induk" ? (pohon?.folders ?? []) : bolehDibuka.filter((f) => !f.personal);
  const baris = urutPohon(bersama).filter((b) => !sembunyikan?.has(b.node.id));
  const pribadi = pilihan === "buka" ? bolehDibuka.find((f) => f.personal) : undefined;

  return (
    <select id={id} value={nilai} onChange={(e) => onUbah(e.target.value)} disabled={disabled} className={kelasIsian}>
      {akar !== undefined ? <option value="">{akar}</option> : null}

      {baris.map(({ node, kedalaman }) => (
        // Spasi tak-putus: spasi biasa di awal teks <option> dibuang peramban.
        <option key={node.id} value={node.id} disabled={node.accessible === false}>
          {`${"\u00a0".repeat(kedalaman * 3)}${node.name}`}
        </option>
      ))}

      {pribadi && !sembunyikan?.has(pribadi.id) ? <option value={pribadi.id}>{t("Folder Saya")}</option> : null}
    </select>
  );
}
