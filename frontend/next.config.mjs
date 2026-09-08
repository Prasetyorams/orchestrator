/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,

  // Berjalan sebagai berkas mandiri di dalam container: node_modules yang
  // dibutuhkan ikut disalin, jadi image akhirnya tidak perlu memuat seluruh
  // isi node_modules.
  output: "standalone",
};

export default nextConfig;
