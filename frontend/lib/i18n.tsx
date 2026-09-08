"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react";

/**
 * Bahasa antarmuka: Indonesia, Inggris, Jawa.
 *
 * KUNCINYA adalah teks Indonesianya sendiri, sama seperti di Studio dan
 * JakRunner. Teks yang belum diterjemahkan karena itu jatuh kembali ke bahasa
 * Indonesia yang BENAR — bukan menjadi kode seperti "nav.home" yang bocor ke
 * layar. Dan sumbernya tetap bisa dibaca tanpa membuka kamusnya.
 *
 * Kamus yang belum lengkap tidak pernah merusak antarmuka; ia hanya membuat
 * sebagian tetap berbahasa Indonesia.
 */
export type Bahasa = "id" | "en" | "jv";

export const BAHASA: { kode: Bahasa; nama: string }[] = [
  { kode: "id", nama: "Indonesia" },
  { kode: "en", nama: "English" },
  { kode: "jv", nama: "Jawa" },
];

const EN: Record<string, string> = {
  // navigasi
  Beranda: "Home",
  Pemantauan: "Monitoring",
  Pekerjaan: "Jobs",
  Catatan: "Logs",
  Pemicu: "Triggers",
  Otomasi: "Automation",
  Proses: "Processes",
  Paket: "Packages",
  Antrean: "Queues",
  Aset: "Assets",
  Kredensial: "Credentials",
  Robot: "Robots",
  Mesin: "Machines",
  Lingkungan: "Environments",
  Gudang: "Storage",
  Penyewa: "Tenants",
  Pengguna: "Users",
  Peran: "Roles",
  Lisensi: "Licensing",
  Setelan: "Settings",

  // kartu dasbor
  "Robot Aktif": "Active robots",
  "Pekerjaan Berjalan": "Running jobs",
  "Berhasil Hari Ini": "Succeeded today",
  "Gagal Hari Ini": "Failed today",
  "Tingkat Keberhasilan": "Success rate",
  "Sedang Berjalan": "In progress",
  "Pemicu Berikutnya": "Upcoming triggers",
  "Peringatan Terbaru": "Recent alerts",
  "Ringkasan Antrean": "Queue summary",
  "Riwayat 14 Hari": "Last 14 days",

  // kolom
  Nama: "Name",
  Status: "State",
  Keadaan: "State",
  Prioritas: "Priority",
  Sumber: "Source",
  Kemajuan: "Progress",
  Dibuat: "Created",
  Dimulai: "Started",
  Selesai: "Ended",
  Keterangan: "Description",
  Versi: "Version",
  Ukuran: "Size",
  Diterbitkan: "Published",
  Berkas: "Files",
  Tingkat: "Level",
  Pesan: "Message",
  Waktu: "Time",
  Jadwal: "Schedule",
  "Jalan Berikutnya": "Next run",
  "Jalan Terakhir": "Last run",
  "Zona Waktu": "Time zone",
  Percobaan: "Retries",
  Rujukan: "Reference",
  Tipe: "Type",
  Denyut: "Heartbeat",
  Memori: "Memory",

  // tombol dan aksi
  Muat: "Refresh",
  "Muat ulang": "Refresh",
  Jalankan: "Run",
  Hentikan: "Stop",
  Hapus: "Delete",
  Simpan: "Save",
  Batal: "Cancel",
  Tutup: "Close",
  Tambah: "Add",
  Sunting: "Edit",
  Unduh: "Download",
  Unggah: "Upload",
  Cari: "Search",
  Detail: "Details",
  Nyalakan: "Enable",
  Matikan: "Disable",
  Keluar: "Sign out",
  Masuk: "Sign in",
  "Tandai semua dibaca": "Mark all read",

  // pesan
  "Belum ada data.": "Nothing here yet.",
  "Memuat...": "Loading...",
  "Tidak ada yang cocok.": "No matches.",
  "Yakin menghapus": "Delete",
  Halaman: "Page",
  dari: "of",
  dipilih: "selected",
  baris: "rows",
  "Nama pengguna": "Username",
  "Kata sandi": "Password",
  Bahasa: "Language",

  // keadaan kosong
  "Tidak ada pekerjaan yang sedang berjalan.": "No jobs are running.",
  "Tidak ada pemicu yang aktif.": "No triggers are enabled.",
  "Belum ada robot yang mendaftar.": "No robot has registered yet.",

  // potongan yang dirangkai
  terdaftar: "registered",
  terputus: "disconnected",
  menunggu: "waiting",
  "pekerjaan hari ini": "jobs today",
  berhasil: "succeeded",
  gagal: "failed",
  "CPU / Memori": "CPU / memory",
  "Semua keadaan": "All states",
  "Semua tingkat": "All levels",
  "Semua proses": "All processes",
};

const JV: Record<string, string> = {
  Beranda: "Ngarep",
  Pemantauan: "Ngawasi",
  Pekerjaan: "Pagawéan",
  Catatan: "Cathetan",
  Pemicu: "Pamicu",
  Otomasi: "Otomatisasi",
  Proses: "Proses",
  Paket: "Pakèt",
  Antrean: "Antrèan",
  Aset: "Asèt",
  Kredensial: "Kredensial",
  Robot: "Robot",
  Mesin: "Mesin",
  Lingkungan: "Lingkungan",
  Gudang: "Gudhang",
  Penyewa: "Panyéwa",
  Pengguna: "Pangguna",
  Peran: "Peran",
  Lisensi: "Lisènsi",
  Setelan: "Setelan",

  "Robot Aktif": "Robot urip",
  "Pekerjaan Berjalan": "Pagawéan mlaku",
  "Berhasil Hari Ini": "Kasil dina iki",
  "Gagal Hari Ini": "Gagal dina iki",
  "Tingkat Keberhasilan": "Tingkat kasil",
  "Sedang Berjalan": "Lagi mlaku",
  "Pemicu Berikutnya": "Pamicu sabanjuré",
  "Peringatan Terbaru": "Pènget anyar",
  "Ringkasan Antrean": "Ringkesan antrèan",
  "Riwayat 14 Hari": "Riwayat 14 dina",

  Nama: "Jeneng",
  Status: "Kahanan",
  Keadaan: "Kahanan",
  Prioritas: "Prioritas",
  Sumber: "Sumber",
  Kemajuan: "Kemajuan",
  Dibuat: "Digawé",
  Dimulai: "Diwiwiti",
  Selesai: "Rampung",
  Keterangan: "Katrangan",
  Versi: "Vèrsi",
  Ukuran: "Ukuran",
  Diterbitkan: "Diterbitaké",
  Berkas: "Berkas",
  Tingkat: "Tingkat",
  Pesan: "Pesen",
  Waktu: "Wektu",
  Jadwal: "Jadwal",
  "Jalan Berikutnya": "Mlaku sabanjuré",
  "Jalan Terakhir": "Mlaku pungkasan",
  "Zona Waktu": "Zona wektu",
  Percobaan: "Nyoba",
  Rujukan: "Rujukan",
  Tipe: "Jinis",
  Denyut: "Denyut",
  Memori: "Mèmori",

  Muat: "Muat manèh",
  "Muat ulang": "Muat manèh",
  Jalankan: "Lakokna",
  Hentikan: "Mandhegna",
  Hapus: "Busak",
  Simpan: "Simpen",
  Batal: "Batal",
  Tutup: "Tutup",
  Tambah: "Tambah",
  Sunting: "Sunting",
  Unduh: "Undhuh",
  Unggah: "Unggah",
  Cari: "Golèk",
  Detail: "Rincian",
  Nyalakan: "Uripna",
  Matikan: "Patènana",
  Keluar: "Metu",
  Masuk: "Mlebu",
  "Tandai semua dibaca": "Tandhani kabèh wis diwaca",

  "Belum ada data.": "Durung ana data.",
  "Memuat...": "Ngemot...",
  "Tidak ada yang cocok.": "Ora ana sing cocog.",
  "Yakin menghapus": "Busak",
  Halaman: "Kaca",
  dari: "saka",
  dipilih: "dipilih",
  baris: "baris",
  "Nama pengguna": "Jeneng pangguna",
  "Kata sandi": "Tembung sandi",
  Bahasa: "Basa",

  // keadaan kosong
  "Tidak ada pekerjaan yang sedang berjalan.": "Ora ana pagawéan sing mlaku.",
  "Tidak ada pemicu yang aktif.": "Ora ana pamicu sing urip.",
  "Belum ada robot yang mendaftar.": "Durung ana robot sing ndhaftar.",

  // potongan yang dirangkai
  terdaftar: "kadhaftar",
  terputus: "pedhot",
  menunggu: "ngentèni",
  "pekerjaan hari ini": "pagawéan dina iki",
  berhasil: "kasil",
  gagal: "gagal",
  "CPU / Memori": "CPU / mèmori",
  "Semua keadaan": "Kabèh kahanan",
  "Semua tingkat": "Kabèh tingkat",
  "Semua proses": "Kabèh proses",
};

const KAMUS: Record<Bahasa, Record<string, string>> = { id: {}, en: EN, jv: JV };

const KUNCI = "forgehub.bahasa";

type Isi = { bahasa: Bahasa; setBahasa: (b: Bahasa) => void; t: (teks: string) => string };

const Konteks = createContext<Isi>({ bahasa: "id", setBahasa: () => {}, t: (s) => s });

export function I18nProvider({ children }: { children: ReactNode }) {
  // Dimulai dari "id" di server DAN di render pertama peramban.
  //
  // Membaca localStorage langsung saat useState akan membuat HTML server
  // (selalu "id") berbeda dari render pertama klien, dan React menolaknya
  // sebagai hydration mismatch. Jadi pilihannya dipasang sesudah pemasangan.
  const [bahasa, setBahasaState] = useState<Bahasa>("id");

  useEffect(() => {
    try {
      const tersimpan = window.localStorage.getItem(KUNCI) as Bahasa | null;
      if (tersimpan && tersimpan in KAMUS) setBahasaState(tersimpan);
    } catch {
      // Peramban yang melarang penyimpanan tetap boleh memakai ForgeHub;
      // yang hilang cuma ingatan pilihan bahasanya.
    }
  }, []);

  const setBahasa = useCallback((b: Bahasa) => {
    setBahasaState(b);
    try {
      window.localStorage.setItem(KUNCI, b);
    } catch {
      /* diabaikan, alasannya sama dengan di atas */
    }
  }, []);

  const t = useCallback((teks: string) => KAMUS[bahasa][teks] ?? teks, [bahasa]);

  return <Konteks.Provider value={{ bahasa, setBahasa, t }}>{children}</Konteks.Provider>;
}

export function useT() {
  return useContext(Konteks);
}
