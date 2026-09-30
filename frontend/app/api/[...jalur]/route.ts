import type { NextRequest } from "next/server";

/**
 * Penerus /api/* dari dasbor ke backend OpenOrchestrator.
 *
 * Peramban hanya bicara dengan alamat dasbor itu sendiri; server dasbor yang
 * menghubungi backend, lewat jaringan internal (API_INTERNAL_URL — di Docker
 * Compose `http://backend:8080`). Dasbor jadi bisa dibuka dari alamat mana pun
 * tanpa build ulang, dan backend tidak perlu mengenal alamat dasbor untuk CORS.
 *
 * Studio, JakRunner, dan Robot Agent tetap memanggil backend langsung (:8080);
 * jalur ini hanya untuk peramban.
 */

export const dynamic = "force-dynamic";

/** Dibaca saat permintaan, bukan saat build: image yang sama bisa dipakai di mana pun. */
function alamatBackend(): string {
  return (process.env.API_INTERNAL_URL ?? "http://localhost:8080").replace(/\/+$/, "");
}

/**
 * Header yang TIDAK diteruskan ke backend.
 *
 * - Hop-by-hop: hanya berlaku untuk sambungan peramban ↔ dasbor.
 * - host: fetch mengisinya sendiri untuk alamat backend.
 * - origin: permintaan dari peramban ke dasbor ini SATU ASAL. Kalau Origin
 *   ikut diteruskan, backend melihat asal (alamat dasbor) yang berbeda dari
 *   alamatnya sendiri, menganggapnya permintaan lintas asal, dan menolaknya
 *   karena alamat dasbor tidak ada di CORS_ORIGINS — padahal justru itu yang
 *   mau dihindari penerus ini.
 * - content-length: badannya dikirim ulang utuh; fetch menghitungnya sendiri.
 */
const HEADER_PERMINTAAN_DIBUANG = new Set([
  "connection",
  "keep-alive",
  "proxy-authorization",
  "proxy-connection",
  "te",
  "trailer",
  "transfer-encoding",
  "upgrade",
  "host",
  "origin",
  "content-length",
]);

/**
 * Header jawaban yang tidak diteruskan ke peramban. fetch sudah membuka
 * kompresinya, jadi content-encoding dan content-length yang lama tidak lagi
 * sesuai dengan isinya.
 */
const HEADER_JAWABAN_DIBUANG = new Set([
  "connection",
  "keep-alive",
  "transfer-encoding",
  "content-encoding",
  "content-length",
]);

async function teruskan(req: NextRequest): Promise<Response> {
  const tujuan = alamatBackend() + req.nextUrl.pathname + req.nextUrl.search;

  const header = new Headers();
  req.headers.forEach((nilai, nama) => {
    if (!HEADER_PERMINTAAN_DIBUANG.has(nama.toLowerCase())) header.set(nama, nilai);
  });
  header.set("x-forwarded-host", req.headers.get("host") ?? "");
  header.set("x-forwarded-proto", req.nextUrl.protocol.replace(":", ""));

  // Badan dibaca utuh lebih dulu: unggahan dari dasbor (berkas ember, paket)
  // berukuran beberapa MB, dan mengirim ulang yang utuh jauh lebih sederhana
  // daripada meneruskan aliran setengah jalan.
  const tanpaBadan = req.method === "GET" || req.method === "HEAD";
  const badan = tanpaBadan ? undefined : await req.arrayBuffer();

  let jawaban: Response;

  try {
    jawaban = await fetch(tujuan, {
      method: req.method,
      headers: header,
      body: badan && badan.byteLength > 0 ? badan : undefined,
      redirect: "manual",
      cache: "no-store",
    });
  } catch {
    // Bentuknya sama dengan galat backend, supaya dasbor menampilkannya
    // seperti galat lain — dan pesannya menyebut di mana putusnya.
    return Response.json({ error: "Dasbor tidak bisa menghubungi backend OpenOrchestrator." }, { status: 502 });
  }

  const headerJawaban = new Headers();
  jawaban.headers.forEach((nilai, nama) => {
    if (!HEADER_JAWABAN_DIBUANG.has(nama.toLowerCase())) headerJawaban.append(nama, nilai);
  });

  // 204/205/304 tidak boleh membawa badan sama sekali.
  const tanpaIsi = jawaban.status === 204 || jawaban.status === 205 || jawaban.status === 304;

  return new Response(tanpaIsi || req.method === "HEAD" ? null : jawaban.body, {
    status: jawaban.status,
    statusText: jawaban.statusText,
    headers: headerJawaban,
  });
}

export const GET = teruskan;
export const POST = teruskan;
export const PUT = teruskan;
export const PATCH = teruskan;
export const DELETE = teruskan;
export const HEAD = teruskan;
export const OPTIONS = teruskan;
