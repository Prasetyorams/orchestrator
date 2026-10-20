import { OpenOrchestratorApi, alamatMasuk, catatKeluar, setToken, KUNCI_SESI_KELUAR, type AlasanKeluar } from "@/lib/api";
import { KUNCI_AKTIVITAS } from "@/lib/pengawasSesi";

/**
 * Keluar dari dasbor (V18): sesi dicabut DI SERVER — bukan hanya token yang
 * dibuang dari peramban, yang salinannya tetap berlaku — lalu tab lain diberi
 * tahu lewat event storage (alasan + token yang hilang) dan layar masuk
 * menyebut alasannya.
 */
export async function keluar(alasan: Extract<AlasanKeluar, "idle" | "keluar">) {
  try {
    await OpenOrchestratorApi.logout();
  } catch {
    // Sudah berakhir di server (mis. menganggur): tidak ada yang perlu dicabut lagi.
  }

  catatKeluar(alasan);
  try {
    window.localStorage.removeItem(KUNCI_AKTIVITAS);
  } catch {
    /* diabaikan */
  }
  setToken(null);
  window.location.href = alasan === "idle" ? alamatMasuk("idle") : "/login";
}

/**
 * Sesudah masuk: jam menganggur mulai dari sekarang. Tanpa ini, stempel
 * aktivitas dari sesi sebelumnya (kemarin) membuat sesi baru langsung habis.
 */
export function mulaiSesiBaru() {
  try {
    window.localStorage.setItem(KUNCI_AKTIVITAS, String(Date.now()));
    window.localStorage.removeItem(KUNCI_SESI_KELUAR);
  } catch {
    /* penyimpanan dilarang: setiap tab mengawasi dirinya sendiri */
  }
}
