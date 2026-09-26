"use client";

import { useEffect, useRef, useState, type ComponentType } from "react";
import { cn } from "@/lib/utils";

/**
 * Grafik donat: bagian dari keseluruhan, sekilas.
 *
 * Dipakai hanya untuk paling banyak enam irisan, dan SELALU bersama
 * keterangan berangka di bawahnya — keterangan itu adalah tampilan tabelnya:
 * setiap nilai terbaca tanpa harus mengarahkan tetikus, dan warna tidak pernah
 * menjadi satu-satunya penanda.
 *
 * Tanpa pustaka grafik: satu cincin dan beberapa garis penunjuk muat dalam
 * SVG biasa, dan pustaka bagan menambah ratusan kilobita ke setiap pemuatan
 * halaman.
 */

export type Irisan = {
  kunci: string;
  /** Sudah diterjemahkan. */
  label: string;
  nilai: number;
  warna: string;
  ikon?: ComponentType<{ className?: string }>;
};

const W = 520;
const H = 280;
const CX = W / 2;
const CY = H / 2;
const RO = 94;
const RI = 64;
const LEBAR_RINGKAS = 420;

/** Irisan sekecil apa pun tetap terlihat: minimal dua derajat. */
const SUDUT_MIN = (2 * Math.PI) / 180;

// Celah antar-irisan digambar dengan warna PERMUKAAN kartu (stroke-card),
// lintasan cincin kosong dengan slate-100, dan garis penunjuk dengan
// slate-300 — semuanya kelas tema, jadi ikut berganti di tema gelap.

type Busur = Irisan & { a0: number; a1: number; tengah: number; persen: number };

function titik(r: number, a: number) {
  return [CX + r * Math.cos(a), CY + r * Math.sin(a)] as const;
}

function jalurBusur(a0: number, a1: number) {
  const besar = a1 - a0 > Math.PI ? 1 : 0;
  const [x0, y0] = titik(RO, a0);
  const [x1, y1] = titik(RO, a1);
  const [x2, y2] = titik(RI, a1);
  const [x3, y3] = titik(RI, a0);

  return `M${x0},${y0} A${RO},${RO} 0 ${besar} 1 ${x1},${y1} L${x2},${y2} A${RI},${RI} 0 ${besar} 0 ${x3},${y3} Z`;
}

/** Cincin utuh: satu irisan yang memenuhi seluruh lingkaran. */
const CINCIN = `M${CX + RO},${CY} A${RO},${RO} 0 1 1 ${CX - RO},${CY} A${RO},${RO} 0 1 1 ${CX + RO},${CY} Z
M${CX + RI},${CY} A${RI},${RI} 0 1 0 ${CX - RI},${CY} A${RI},${RI} 0 1 0 ${CX + RI},${CY} Z`;

function hitungBusur(irisan: Irisan[]): Busur[] {
  const ada = irisan.filter((x) => x.nilai > 0);
  const total = ada.reduce((s, x) => s + x.nilai, 0);
  if (total === 0) return [];

  // Sudut mentah, lalu yang terlalu kecil dinaikkan ke SUDUT_MIN dan sisanya
  // dikecilkan sebanding supaya jumlahnya tetap satu lingkaran.
  const mentah = ada.map((x) => (x.nilai / total) * 2 * Math.PI);
  const kecil = mentah.filter((s) => s < SUDUT_MIN).length;
  const sisaBesar = mentah.filter((s) => s >= SUDUT_MIN).reduce((s, x) => s + x, 0);
  const skala = kecil && sisaBesar ? (2 * Math.PI - kecil * SUDUT_MIN) / sisaBesar : 1;

  let a = -Math.PI / 2;

  return ada.map((x, i) => {
    const sapuan = mentah[i] < SUDUT_MIN ? SUDUT_MIN : mentah[i] * skala;
    const busur = { ...x, a0: a, a1: a + sapuan, tengah: a + sapuan / 2, persen: (x.nilai / total) * 100 };
    a += sapuan;
    return busur;
  });
}

/**
 * Posisi label penunjuk. Label di satu sisi yang terlalu berdekatan didorong
 * menjauh, lalu yang terdorong keluar bidang ditarik kembali — label yang
 * saling menimpa lebih buruk daripada garis penunjuk yang sedikit menekuk.
 */
function aturLabel(busur: Busur[]) {
  const JARAK = 36;
  const ATAS = 18;
  const BAWAH = H - 22;

  const hasil = new Map<string, { y: number; kanan: boolean }>();

  for (const kanan of [true, false]) {
    const sisi = busur
      .filter((b) => Math.cos(b.tengah) >= 0 === kanan)
      .map((b) => ({ kunci: b.kunci, y: titik(RO + 16, b.tengah)[1] }))
      .sort((p, q) => p.y - q.y);

    for (let i = 0; i < sisi.length; i++) {
      sisi[i].y = Math.max(sisi[i].y, ATAS, i > 0 ? sisi[i - 1].y + JARAK : ATAS);
    }

    for (let i = sisi.length - 1; i >= 0; i--) {
      sisi[i].y = Math.min(sisi[i].y, BAWAH, i < sisi.length - 1 ? sisi[i + 1].y - JARAK : BAWAH);
    }

    for (const s of sisi) hasil.set(s.kunci, { y: s.y, kanan });
  }

  return hasil;
}

const bulat = (n: number) => (n >= 10 || Number.isInteger(n) ? Math.round(n) : Math.round(n * 10) / 10);

export function Donat({
  irisan,
  tengah,
  keteranganTengah,
  judul,
  kosong,
  format = (n) => n.toLocaleString(),
}: {
  irisan: Irisan[];
  /** Angka besar di tengah cincin. */
  tengah: string | number;
  keteranganTengah: string;
  /** Nama grafiknya, untuk pembaca layar. */
  judul: string;
  /** Kalimat di bawah cincin saat semua nilainya nol. */
  kosong?: string;
  format?: (n: number) => string;
}) {
  const wadah = useRef<HTMLDivElement>(null);
  const [lebar, setLebar] = useState(W);
  const [aktif, setAktif] = useState<string | null>(null);

  // Di kartu sempit, label penunjuk mengecil sampai tidak terbaca; yang
  // digambar tinggal cincinnya, dan keterangan di bawah membawa angkanya.
  useEffect(() => {
    const el = wadah.current;
    if (!el) return;

    const amati = new ResizeObserver(([e]) => setLebar(e.contentRect.width));
    amati.observe(el);

    return () => amati.disconnect();
  }, []);

  const ringkas = lebar < LEBAR_RINGKAS;
  const busur = hitungBusur(irisan);
  const label = ringkas ? new Map() : aturLabel(busur);
  const total = irisan.reduce((s, x) => s + Math.max(0, x.nilai), 0);
  const dipilih = busur.find((b) => b.kunci === aktif);

  // Kotak pandang ringkas memotong bidangnya ke cincin saja.
  const kotak = ringkas ? `${CX - RO - 8} ${CY - RO - 8} ${2 * RO + 16} ${2 * RO + 16}` : `0 0 ${W} ${H}`;
  const skala = lebar / (ringkas ? 2 * RO + 16 : W);

  function posisiTip(b: Busur) {
    const [x, y] = titik(RO + 6, b.tengah);
    const asalX = ringkas ? CX - RO - 8 : 0;
    const asalY = ringkas ? CY - RO - 8 : 0;
    return { left: (x - asalX) * skala, top: (y - asalY) * skala };
  }

  return (
    <div>
      <div ref={wadah} className={cn("relative mx-auto", ringkas ? "max-w-[260px]" : "max-w-[560px]")}>
        <svg viewBox={kotak} className="block h-auto w-full" role="img" aria-label={judul}>
          {busur.length === 0 ? <path d={CINCIN} className="fill-slate-100" fillRule="evenodd" /> : null}

          {busur.length === 1 ? (
            <path
              d={CINCIN}
              fillRule="evenodd"
              fill={busur[0].warna}
              strokeWidth={2}
              tabIndex={0}
              aria-label={`${busur[0].label}: ${format(busur[0].nilai)} (100%)`}
              onMouseEnter={() => setAktif(busur[0].kunci)}
              onMouseLeave={() => setAktif(null)}
              onFocus={() => setAktif(busur[0].kunci)}
              onBlur={() => setAktif(null)}
              className="stroke-card outline-none"
            />
          ) : (
            busur.map((b) => {
              const geser = aktif === b.kunci ? 5 : 0;

              return (
                <path
                  key={b.kunci}
                  d={jalurBusur(b.a0, b.a1)}
                  fill={b.warna}
                  // Celah dua piksel berwarna permukaan memisahkan irisan
                  // yang bersebelahan — bukan garis tepi berwarna.
                  strokeWidth={2}
                  strokeLinejoin="round"
                  tabIndex={0}
                  aria-label={`${b.label}: ${format(b.nilai)} (${bulat(b.persen)}%)`}
                  onMouseEnter={() => setAktif(b.kunci)}
                  onMouseLeave={() => setAktif(null)}
                  onFocus={() => setAktif(b.kunci)}
                  onBlur={() => setAktif(null)}
                  transform={`translate(${Math.cos(b.tengah) * geser},${Math.sin(b.tengah) * geser})`}
                  opacity={aktif && aktif !== b.kunci ? 0.4 : 1}
                  className="cursor-pointer stroke-card outline-none transition-[opacity,transform] duration-150"
                />
              );
            })
          )}

          {/* Angka di tengah: jumlah seluruhnya, atau irisan yang ditunjuk. */}
          <text
            x={CX}
            y={CY + 4}
            textAnchor="middle"
            className="fill-ink"
            style={{ fontSize: 32, fontWeight: 600 }}
          >
            {dipilih ? format(dipilih.nilai) : tengah}
          </text>
          <text x={CX} y={CY + 26} textAnchor="middle" className="fill-muted" style={{ fontSize: 13 }}>
            {dipilih ? dipilih.label : keteranganTengah}
          </text>

          {busur.map((b) => {
            const posisi = label.get(b.kunci);
            if (!posisi) return null;

            const [x1, y1] = titik(RO + 3, b.tengah);
            const [x2] = titik(RO + 16, b.tengah);
            const ujung = posisi.kanan ? CX + RO + 40 : CX - RO - 40;
            const teksX = posisi.kanan ? ujung + 6 : ujung - 6;
            const anchor = posisi.kanan ? "start" : "end";
            const redup = aktif && aktif !== b.kunci;

            return (
              <g key={b.kunci} opacity={redup ? 0.4 : 1} className="transition-opacity duration-150" aria-hidden="true">
                <polyline
                  points={`${x1},${y1} ${x2},${posisi.y} ${ujung},${posisi.y}`}
                  fill="none"
                  className="stroke-slate-300 dark:stroke-slate-400"
                  strokeWidth={1}
                />
                <text x={teksX} y={posisi.y - 3} textAnchor={anchor} className="fill-ink" style={{ fontSize: 13 }}>
                  {b.label}
                </text>
                <text
                  x={teksX}
                  y={posisi.y + 14}
                  textAnchor={anchor}
                  className="fill-muted"
                  style={{ fontSize: 13, fontWeight: 600 }}
                >
                  {format(b.nilai)}
                </text>
              </g>
            );
          })}
        </svg>

        {dipilih ? (
          <div
            role="tooltip"
            className="pointer-events-none absolute z-10 -translate-x-1/2 -translate-y-[calc(100%+8px)] whitespace-nowrap rounded-md border border-line bg-card px-3 py-2 text-xs shadow-lg"
            style={posisiTip(dipilih)}
          >
            <p className="flex items-center gap-1.5 font-medium text-ink">
              <span className="h-2.5 w-2.5 rounded-sm" style={{ background: dipilih.warna }} />
              {dipilih.label}
            </p>
            <p className="mt-0.5 tabular-nums text-muted">
              {format(dipilih.nilai)} · {bulat(dipilih.persen)}%
            </p>
          </div>
        ) : null}
      </div>

      {total === 0 && kosong ? <p className="-mt-1 text-center text-xs text-muted">{kosong}</p> : null}

      {/* Keterangan berangka: identitas setiap warna, dan tampilan tabelnya. */}
      <ul className="mt-3 grid gap-x-5 gap-y-1 sm:grid-cols-2">
        {irisan.map((x) => {
          const Ikon = x.ikon;
          const persen = total > 0 ? (x.nilai / total) * 100 : 0;

          return (
            <li
              key={x.kunci}
              onMouseEnter={() => x.nilai > 0 && setAktif(x.kunci)}
              onMouseLeave={() => setAktif(null)}
              className={cn(
                "flex items-center gap-2 rounded px-1.5 py-1 text-sm",
                aktif === x.kunci && "bg-slate-50",
                x.nilai === 0 && "text-muted",
              )}
            >
              <span className="h-2.5 w-2.5 shrink-0 rounded-sm" style={{ background: x.warna }} aria-hidden="true" />
              {Ikon ? <Ikon className="h-3.5 w-3.5 shrink-0 text-muted" /> : null}
              <span className={cn("min-w-0 flex-1 truncate", x.nilai > 0 && "text-ink")} title={x.label}>
                {x.label}
              </span>
              <span className={cn("tabular-nums", x.nilai > 0 && "font-medium text-ink")}>{format(x.nilai)}</span>
              <span className="w-10 text-right text-xs tabular-nums text-muted">{bulat(persen)}%</span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
