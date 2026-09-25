import { redirect } from "next/navigation";

/**
 * Halaman Kredensial yang lama.
 *
 * Kredensial sekarang aset bertipe Credential, jadi alamat ini hanya
 * meneruskan ke halaman Aset yang sudah tersaring. Tetap ada supaya tautan
 * dan markah yang tersimpan tidak berakhir di halaman 404.
 */
export default function Kredensial() {
  redirect("/assets?tipe=Credential");
}
