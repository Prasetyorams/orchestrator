import { useId } from "react";

/**
 * Ikon OpenOrchestrator — sama dengan app/icon.svg (favicon), yang juga menjadi
 * dasar favicon.ico dan apple-icon.png (tools/buat-ikon.mjs).
 *
 * Warnanya tetap, tidak ikut tema terang/gelap: ikon adalah tanda pengenal,
 * dan tanda pengenal yang berganti warna terlihat seperti aplikasi lain.
 */
export function Logo({ size = 32, className }: { size?: number; className?: string }) {
  // Id gradien unik per ikon. Dua <svg> di satu halaman dengan id yang sama
  // saling meminjam gradien, dan yang satu ikut kehilangan warnanya begitu
  // yang lain dilepas dari halaman.
  const gradien = `oo-grad-${useId().replace(/[^A-Za-z0-9_-]/g, "")}`;

  return (
    <svg
      width={size}
      height={size}
      viewBox="8 8 104 104"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
      className={className}
    >
      <defs>
        <linearGradient id={gradien} x1="8" y1="8" x2="112" y2="112" gradientUnits="userSpaceOnUse">
          <stop offset="0%" stopColor="#FF6B3D" />
          <stop offset="52%" stopColor="#EA4317" />
          <stop offset="100%" stopColor="#B82905" />
        </linearGradient>
      </defs>

      <rect x="8" y="8" width="104" height="104" rx="28" fill={`url(#${gradien})`} />
      <circle
        cx="60"
        cy="60"
        r="34"
        stroke="#FFFFFF"
        strokeWidth="4.5"
        strokeOpacity="0.45"
        strokeDasharray="14 10"
        strokeLinecap="round"
      />
      <path d="M60 24V42M60 78V96M24 60H42M78 60H96" stroke="#FFFFFF" strokeWidth="6" strokeLinecap="round" />
      <rect x="43" y="43" width="34" height="34" rx="8" transform="rotate(45 60 60)" fill="#FFFFFF" />
      <circle cx="60" cy="60" r="6.5" fill="#EA4317" />
      <circle cx="60" cy="24" r="6.5" fill="#FFFFFF" />
      <circle cx="96" cy="60" r="6.5" fill="#FFFFFF" />
      <circle cx="60" cy="96" r="6.5" fill="#FFFFFF" />
      <circle cx="24" cy="60" r="6.5" fill="#FFFFFF" />
    </svg>
  );
}
