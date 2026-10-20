"use client";

import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { OpenOrchestratorApi, errorText, type SessionTimeoutSettings } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { useIzin } from "@/lib/izin";
import { cn, dateTimeOf } from "@/lib/utils";
import { Button, Card, CardHeader, Galat } from "@/components/ui/primitives";
import { Isian, kelasIsian } from "@/components/Dialog";
import { useNotifikasi } from "@/components/Notifikasi";

const LAINNYA = "lainnya";

type Isi = { aktif: boolean; durasi: string; menitLain: string; peringatan: number };

function dariServer(s: SessionTimeoutSettings): Isi {
  const pilihan = s.durationOptions.includes(s.idleTimeoutMinutes);
  return {
    aktif: s.idleTimeoutEnabled,
    durasi: pilihan ? String(s.idleTimeoutMinutes) : LAINNYA,
    menitLain: pilihan ? "" : String(s.idleTimeoutMinutes),
    peringatan: s.warningBeforeLogoutMinutes,
  };
}

/**
 * Tenant › Setelan › Sesi & Keamanan (V18): batas waktu sesi menganggur
 * dasbor. Server sumber kebenarannya dan yang menegakkannya; halaman ini
 * hanya menampilkan dan mengirim — semua isian diperiksa ulang di server.
 */
export default function SesiKeamanan() {
  const { t } = useT();
  const { boleh } = useIzin();
  const klien = useQueryClient();
  const beritahu = useNotifikasi();
  const bolehUbah = boleh("settings.update");

  const setelan = useQuery({ queryKey: ["sesi-timeout"], queryFn: OpenOrchestratorApi.sessionTimeout });
  const [isi, setIsi] = useState<Isi | null>(null);
  const [galat, setGalat] = useState("");

  // Isian mengikuti yang tersimpan setiap kali server menjawab (termasuk sesudah simpan).
  useEffect(() => {
    if (setelan.data) setIsi(dariServer(setelan.data));
  }, [setelan.data]);

  const s = setelan.data;
  const menit = isi ? (isi.durasi === LAINNYA ? Number(isi.menitLain) : Number(isi.durasi)) : NaN;

  // Pemeriksaan yang sama dengan server, supaya kesalahan terlihat sebelum dikirim.
  const salah = useMemo(() => {
    if (!s || !isi) return "";
    if (!Number.isInteger(menit) || menit <= 0) return t("Batas waktu harus bilangan bulat menit.");
    if (menit < s.minMinutes || menit > s.maxMinutes) return t("Batas waktu harus {0}–{1} menit.", s.minMinutes, s.maxMinutes);
    if (isi.peringatan >= menit) return t("Peringatan harus lebih singkat daripada batas waktunya.");
    return "";
  }, [s, isi, menit, t]);

  const berubah =
    !!s && !!isi && (isi.aktif !== s.idleTimeoutEnabled || menit !== s.idleTimeoutMinutes || isi.peringatan !== s.warningBeforeLogoutMinutes);

  const simpan = useMutation({
    mutationFn: () =>
      OpenOrchestratorApi.saveSessionTimeout({
        idleTimeoutEnabled: isi!.aktif,
        idleTimeoutMinutes: menit,
        warningBeforeLogoutMinutes: isi!.peringatan,
      }),
    onSuccess: (baru) => {
      setGalat("");
      klien.setQueryData(["sesi-timeout"], baru);
      // Pengawas sesi tab ini memakai kebijakan baru sekarang; tab dan sesi lain
      // mendapatkannya dari server (paling lambat saat menyegarkan kebijakannya).
      klien.invalidateQueries({ queryKey: ["sesi"] });
      beritahu(t("Batas waktu sesi disimpan. Berlaku untuk semua sesi dasbor penyewa ini."));
    },
    onError: (e) => setGalat(errorText(e)),
  });

  if (setelan.isError) return <Galat pesan={errorText(setelan.error)} />;
  if (!s || !isi) return <p className="text-sm text-muted">{t("Memuat...")}</p>;

  const terkunci = !bolehUbah || simpan.isPending;

  return (
    <div className="space-y-5">
      <Card>
        <CardHeader
          title={t("Batas Waktu Sesi")}
          subtitle={t("Keluar otomatis dari dasbor kalau tidak ada aktivitas selama waktu yang ditentukan.")}
        />
        <div className="grid gap-x-8 gap-y-2 p-5 lg:grid-cols-[minmax(0,26rem)_1fr]">
          <div>
            <div className="mb-4 flex items-start justify-between gap-4">
              <div>
                <p className="text-sm font-medium text-ink">{t("Aktifkan batas waktu menganggur")}</p>
                <p className="mt-0.5 text-xs text-muted">
                  {s.required
                    ? t("Diwajibkan kebijakan keamanan server; tidak bisa dimatikan.")
                    : t("Dimatikan: sesi hanya berakhir saat umur tokennya habis ({0} jam).", Math.round(s.tokenLifetimeMinutes / 60))}
                </p>
              </div>
              <Saklar
                nyala={isi.aktif}
                label={t("Aktifkan batas waktu menganggur")}
                terkunci={terkunci || s.required}
                onUbah={(aktif) => setIsi({ ...isi, aktif })}
              />
            </div>

            <Isian label={t("Batas waktu menganggur")}>
              <select
                value={isi.durasi}
                disabled={terkunci || !isi.aktif}
                onChange={(e) => setIsi({ ...isi, durasi: e.target.value, menitLain: e.target.value === LAINNYA ? String(menit || "") : "" })}
                className={kelasIsian}
              >
                {s.durationOptions.map((m) => (
                  <option key={m} value={m}>
                    {t("{0} menit", m)}
                  </option>
                ))}
                <option value={LAINNYA}>{t("Lainnya...")}</option>
              </select>
            </Isian>

            {isi.durasi === LAINNYA ? (
              <Isian label={t("Menit ({0}–{1})", s.minMinutes, s.maxMinutes)}>
                <input
                  type="number"
                  inputMode="numeric"
                  min={s.minMinutes}
                  max={s.maxMinutes}
                  step={1}
                  value={isi.menitLain}
                  disabled={terkunci || !isi.aktif}
                  onChange={(e) => setIsi({ ...isi, menitLain: e.target.value })}
                  className={kelasIsian}
                />
              </Isian>
            ) : null}

            <Isian
              label={t("Peringatan sebelum keluar")}
              petunjuk={t("Hitung mundur dengan pilihan Tetap masuk atau Keluar.")}
            >
              <select
                value={isi.peringatan}
                disabled={terkunci || !isi.aktif}
                onChange={(e) => setIsi({ ...isi, peringatan: Number(e.target.value) })}
                className={kelasIsian}
              >
                {s.warningOptions.map((m) => (
                  <option key={m} value={m} disabled={Number.isFinite(menit) && m >= menit}>
                    {m === 0 ? t("Tanpa peringatan") : t("{0} menit sebelumnya", m)}
                  </option>
                ))}
              </select>
            </Isian>

            <Galat pesan={salah || galat} className="mb-3" />

            {bolehUbah ? (
              <div className="flex flex-wrap gap-2">
                <Button variant="primary" onClick={() => simpan.mutate()} disabled={!berubah || !!salah || simpan.isPending}>
                  {simpan.isPending ? t("Menyimpan...") : t("Simpan Perubahan")}
                </Button>
                <Button
                  onClick={() => {
                    setIsi(dariServer(s));
                    setGalat("");
                  }}
                  disabled={!berubah || simpan.isPending}
                >
                  {t("Batalkan Perubahan")}
                </Button>
              </div>
            ) : (
              <p className="text-xs text-muted">{t("Hanya peran dengan izin Setelan · Ubah yang bisa mengubahnya.")}</p>
            )}
          </div>

          <dl className="space-y-3 rounded-lg border border-line bg-slate-50 p-4 text-sm lg:self-start">
            <Medan label={t("Status")} nilai={s.idleTimeoutEnabled ? t("Aktif") : t("Mati")} />
            <Medan
              label={t("Batas yang berlaku")}
              nilai={s.idleTimeoutEnabled ? t("{0} menit menganggur", s.idleTimeoutMinutes) : "-"}
            />
            <Medan
              label={t("Peringatan")}
              nilai={s.warningBeforeLogoutMinutes === 0 ? t("Tanpa peringatan") : t("{0} menit sebelumnya", s.warningBeforeLogoutMinutes)}
            />
            <Medan
              label={t("Terakhir diubah")}
              nilai={s.updatedAt ? t("{0} oleh {1}", dateTimeOf(s.updatedAt), s.updatedBy ?? "-") : t("Belum pernah — nilai bawaan")}
            />
            <Medan label={t("Batas mutlak (umur token)")} nilai={t("{0} jam sejak masuk", Math.round(s.tokenLifetimeMinutes / 60))} />
            <p className="border-t border-line pt-3 text-xs text-muted">
              {t("Berlaku untuk sesi dasbor (peramban) semua pengguna penyewa ini, dan ditegakkan server. OpenStudio, Open Assistant, dan robot tidak terkena.")}
            </p>
          </dl>
        </div>
      </Card>
    </div>
  );
}

function Medan({ label, nilai }: { label: string; nilai: string }) {
  return (
    <div>
      <dt className="text-xs text-muted">{label}</dt>
      <dd className="font-medium text-ink">{nilai}</dd>
    </div>
  );
}

function Saklar({ nyala, label, terkunci, onUbah }: { nyala: boolean; label: string; terkunci: boolean; onUbah: (nyala: boolean) => void }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={nyala}
      aria-label={label}
      disabled={terkunci}
      onClick={() => onUbah(!nyala)}
      className={cn(
        "relative inline-flex h-6 w-11 shrink-0 items-center rounded-full transition disabled:cursor-not-allowed disabled:opacity-50",
        nyala ? "bg-brand" : "bg-line",
      )}
    >
      <span className={cn("inline-block h-5 w-5 rounded-full bg-white shadow transition", nyala ? "translate-x-5" : "translate-x-0.5")} />
    </button>
  );
}
