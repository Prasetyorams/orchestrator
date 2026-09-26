/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,

  // Berjalan sebagai berkas mandiri di dalam container: node_modules yang
  // dibutuhkan ikut disalin, jadi image akhirnya tidak perlu memuat seluruh
  // isi node_modules.
  output: "standalone",

  // Alamat halaman sebelum menu dipindah ke tab Tenant dan Folders. Tetap
  // diteruskan, supaya markah dan tautan yang tersimpan tidak berakhir di 404.
  // Sementara (307), bukan permanen: peramban mengingat pengalihan permanen
  // selamanya, dan alamat lama itu mungkin dipakai lagi kelak.
  async redirects() {
    return [
      { source: "/monitoring/jobs", destination: "/automation/jobs", permanent: false },
      { source: "/monitoring/triggers", destination: "/automation/triggers", permanent: false },
      { source: "/automation/libraries", destination: "/buckets", permanent: false },
      { source: "/robots", destination: "/tenant/robots", permanent: false },
      { source: "/robots/machines", destination: "/tenant/robots", permanent: false },
      { source: "/robots/environments", destination: "/tenant/robots/environments", permanent: false },
      { source: "/robots/credentials", destination: "/assets?tipe=Credential", permanent: false },
      { source: "/tenants", destination: "/tenant/users", permanent: false },
      { source: "/settings", destination: "/tenant/settings", permanent: false },
    ];
  },
};

export default nextConfig;
