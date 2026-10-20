/**
 * Pengawas sesi menganggur di dasbor (V18).
 *
 * Murni — tanpa React, tanpa impor, tanpa window — supaya bisa diuji dengan
 * jam palsu di Node. Komponen PenjagaSesi yang menyambungkannya ke peramban:
 * event mouse/papan ketik/gulir/sentuh, localStorage, dan API.
 *
 * Yang dijaga di sini hanya PENGALAMAN pengguna (peringatan, hitung mundur,
 * keluar tepat waktu). Yang menegakkan tetap server: sesi yang menganggur
 * melewati batas ditolak 401 di setiap endpoint, apa pun yang dilakukan
 * peramban.
 *
 * Aturannya:
 * - Aktivitas = yang dilakukan ORANG (dilaporkan PenjagaSesi dari event DOM dan
 *   pindah halaman). Penarikan data berkala tidak pernah lewat sini.
 * - Aktivitas dicatat di localStorage (hanya stempel waktu, bukan token), jadi
 *   semua tab peramban yang sama berbagi satu jam menganggur.
 * - Server diberi tahu paling sering sekali per SELANG_LAPOR_MS, dengan
 *   laporan susulan untuk aktivitas terakhir — jadi catatan server tidak
 *   pernah lebih tua daripada aktivitas terakhir lebih dari selang itu.
 * - Selama peringatan tampil, gerakan mouse TIDAK memperpanjang sesi; hanya
 *   "Tetap masuk" (yang diperiksa server) atau aktivitas di tab lain.
 */

export type KebijakanSesi = {
  /** Batas waktu menganggur aktif. */
  aktif: boolean;
  menit: number;
  /** 0 = tanpa peringatan sebelum keluar. */
  peringatanMenit: number;
};

export type Lingkungan = {
  sekarang(): number;
  setiap(fn: () => void, ms: number): unknown;
  hentikanSetiap(pegangan: unknown): void;
  nanti(fn: () => void, ms: number): unknown;
  batalkanNanti(pegangan: unknown): void;
  baca(kunci: string): string | null;
  tulis(kunci: string, nilai: string): void;
  /** POST /api/auth/session/activity. false = server menyatakan sesinya sudah berakhir (401). */
  lapor(): Promise<boolean>;
  /** GET /api/auth/session — memeriksa TANPA memperpanjang. false = sudah berakhir (401). */
  periksa(): Promise<boolean>;
  /** Tampilkan/perbarui peringatan dengan sisa detik, atau tutup (null). */
  peringatan(sisaDetik: number | null): void;
  /** Batas waktu terlampaui atau server menolak: keluar. Dipanggil paling banyak sekali. */
  berakhir(): void;
};

/** Stempel waktu aktivitas terakhir (milidetik), dibagi semua tab. Bukan rahasia. */
export const KUNCI_AKTIVITAS = "openorchestrator.sesi.aktivitas";
export const SELANG_LAPOR_MS = 60_000;
export const SELANG_SIMPAN_MS = 5_000;
export const SELANG_DETAK_MS = 1_000;

/**
 * Dipakai selama kebijakan server belum (atau gagal) termuat: lebih ketat
 * daripada bawaan server, supaya kegagalan memuat tidak membuat sesi terbuka
 * lebih lama. Server tetap yang menentukan.
 */
export const KEBIJAKAN_CADANGAN: KebijakanSesi = { aktif: true, menit: 15, peringatanMenit: 2 };

export class PengawasSesi {
  private readonly env: Lingkungan;
  private kebijakan: KebijakanSesi;
  private terakhir: number;
  private terakhirSimpan = 0;
  private terakhirLapor: number;
  private adaBaru = false;
  private laporNanti: unknown = null;
  private detak: unknown = null;
  private memperingatkan = false;
  private selesai = false;

  constructor(env: Lingkungan, kebijakan: KebijakanSesi = KEBIJAKAN_CADANGAN) {
    this.env = env;
    this.kebijakan = kebijakan;
    const sekarang = env.sekarang();
    const tersimpan = this.dariPenyimpanan();
    this.terakhir = tersimpan ?? sekarang;
    this.terakhirLapor = sekarang - SELANG_LAPOR_MS;
  }

  /**
   * Mulai mengawasi. Halaman yang baru dibuka diperiksa ke server LEBIH DULU;
   * baru kalau sesinya masih berlaku, membuka halaman dihitung aktivitas.
   */
  mulai(): void {
    if (this.detak !== null || this.selesai) return;
    this.detak = this.env.setiap(() => this.detakSekali(), SELANG_DETAK_MS);
    this.detakSekali();
    this.periksaServer(true);
  }

  berhenti(): void {
    if (this.detak !== null) this.env.hentikanSetiap(this.detak);
    if (this.laporNanti !== null) this.env.batalkanNanti(this.laporNanti);
    this.detak = null;
    this.laporNanti = null;
  }

  ubahKebijakan(kebijakan: KebijakanSesi): void {
    this.kebijakan = kebijakan;
    this.detakSekali();
  }

  /** Mouse, papan ketik, gulir, sentuh, pindah halaman — dipanggil sesering apa pun. */
  aktivitas(): void {
    if (this.selesai || this.memperingatkan) return;

    const sekarang = this.env.sekarang();
    this.terakhir = sekarang;
    this.adaBaru = true;

    if (sekarang - this.terakhirSimpan >= SELANG_SIMPAN_MS) this.simpan(sekarang);
    this.jadwalkanLapor(sekarang);
  }

  /** Tab lain mencatat aktivitas (event storage). */
  saatAktivitasTabLain(pada: number): void {
    if (Number.isFinite(pada) && pada > this.terakhir) this.terakhir = pada;
    this.detakSekali();
  }

  /** Tab kembali terlihat: hitung ulang SEKARANG (pewaktu tab latar diperlambat peramban), lalu tanya server. */
  saatTerlihat(): void {
    this.detakSekali();
    if (!this.selesai) this.periksaServer(false);
  }

  /**
   * "Tetap masuk": server yang memutuskan. Sesi yang sudah lewat batas di
   * server tidak bisa diperpanjang dari sini.
   *
   * @returns true kalau diperpanjang; false kalau jaringan gagal (peringatan tetap tampil)
   */
  async tetapMasuk(): Promise<boolean> {
    if (this.selesai) return false;

    let berlaku: boolean;
    try {
      berlaku = await this.env.lapor();
    } catch {
      return false;
    }

    if (!berlaku) {
      this.akhiri();
      return false;
    }

    const sekarang = this.env.sekarang();
    this.terakhir = sekarang;
    this.terakhirLapor = sekarang;
    this.adaBaru = false;
    this.simpan(sekarang);
    this.memperingatkan = false;
    this.env.peringatan(null);
    return true;
  }

  /** Untuk diuji dan untuk hitung mundur. */
  sisaMs(): number {
    return this.terakhir + this.kebijakan.menit * 60_000 - this.env.sekarang();
  }

  // ------------------------------------------------------------------

  private detakSekali(): void {
    if (this.selesai) return;

    const tersimpan = this.dariPenyimpanan();
    if (tersimpan !== null && tersimpan > this.terakhir) this.terakhir = tersimpan;

    if (!this.kebijakan.aktif) {
      if (this.memperingatkan) this.tutupPeringatan();
      return;
    }

    const sisa = this.sisaMs();

    if (sisa <= 0) {
      this.akhiri();
    } else if (this.kebijakan.peringatanMenit > 0 && sisa <= this.kebijakan.peringatanMenit * 60_000) {
      this.memperingatkan = true;
      this.env.peringatan(Math.ceil(sisa / 1000));
    } else if (this.memperingatkan) {
      this.tutupPeringatan();
    }
  }

  private tutupPeringatan(): void {
    this.memperingatkan = false;
    this.env.peringatan(null);
  }

  private jadwalkanLapor(sekarang: number): void {
    if (this.laporNanti !== null) return;

    const tunggu = this.terakhirLapor + SELANG_LAPOR_MS - sekarang;
    if (tunggu <= 0) {
      this.kirim();
      return;
    }

    this.laporNanti = this.env.nanti(() => {
      this.laporNanti = null;
      this.kirim();
    }, tunggu);
  }

  /** Laporan susulan: aktivitas di dalam selang tetap sampai ke server di akhir selang itu. */
  private kirim(): void {
    if (!this.adaBaru || this.selesai || this.memperingatkan) return;

    this.adaBaru = false;
    this.terakhirLapor = this.env.sekarang();
    this.simpan(this.terakhir);

    this.env.lapor().then(
      (berlaku) => {
        if (!berlaku) this.akhiri();
      },
      () => {
        // Jaringan gagal: dicoba lagi bersama aktivitas berikutnya.
        this.adaBaru = true;
      },
    );
  }

  private periksaServer(hitungSebagaiAktivitas: boolean): void {
    this.env.periksa().then(
      (berlaku) => {
        if (!berlaku) this.akhiri();
        else if (hitungSebagaiAktivitas) this.aktivitas();
      },
      () => {
        // Server tidak terjangkau: pengawasan di peramban tetap berjalan.
      },
    );
  }

  private simpan(pada: number): void {
    this.terakhirSimpan = this.env.sekarang();
    this.env.tulis(KUNCI_AKTIVITAS, String(pada));
  }

  private dariPenyimpanan(): number | null {
    const nilai = Number(this.env.baca(KUNCI_AKTIVITAS));
    return Number.isFinite(nilai) && nilai > 0 ? nilai : null;
  }

  private akhiri(): void {
    if (this.selesai) return;
    this.selesai = true;
    this.berhenti();
    this.memperingatkan = false;
    this.env.peringatan(null);
    this.env.berakhir();
  }
}
