-- =====================================================================
-- Data awal.
--
-- Yang ditanam di sini hanya KERANGKA: penyewa, peran, lingkungan, satu
-- antrean, satu bucket, dan baris lisensi. Robot, proses, pekerjaan, dan
-- log TIDAK dipalsukan — semuanya masuk dari Studio dan JakRunner yang
-- sungguhan. Dasbor berisi data karangan terlihat meyakinkan pada menit
-- pertama, lalu menyesatkan pada menit kedua, saat orang mengira angka di
-- layar menggambarkan robot yang benar-benar berjalan.
--
-- Dua hal sengaja TIDAK ada di sini, karena keduanya butuh nilai yang baru
-- diketahui saat program berjalan, dan Bootstrap.java yang mengurusnya:
--
--   1. Pengguna pertama. Hash kata sandinya memakai garam acak; garam yang
--      dituliskan tetap di dalam berkas migrasi berarti setiap pemasangan
--      ForgeHub di dunia memakai garam yang sama.
--
--   2. Mesin tempat ForgeHub berjalan. Namanya baru diketahui saat
--      dijalankan, dan tanpa barisnya denyut pertama dari JakRunner tiba
--      untuk mesin yang belum dikenal.
--
-- UUID di bawah ini tetap dan bisa dibaca supaya mudah dikenali saat
-- membuka basis datanya langsung.
-- =====================================================================

INSERT INTO tenants (id, name, display_name, active) VALUES
    ('11111111-1111-1111-1111-111111111111', 'default', 'Default Tenant', TRUE);

-- ---------------------------------------------------------------------
-- Peran
--
-- Izin ditulis sebagai pola, bukan daftar penuh: "packages.*" tetap benar
-- setelah ada izin paket yang baru, sedangkan daftar penuh akan diam-diam
-- ketinggalan.
-- ---------------------------------------------------------------------
INSERT INTO roles (id, tenant_id, name, description, permissions) VALUES
    ('44444444-4444-4444-4444-444444444441', '11111111-1111-1111-1111-111111111111',
     'Administrator', 'Akses penuh ke seluruh ForgeHub.', '*'),
    ('44444444-4444-4444-4444-444444444442', '11111111-1111-1111-1111-111111111111',
     'Automation Developer', 'Menerbitkan paket dan proses, menjalankan pekerjaan.',
     'packages.*,processes.*,jobs.create,jobs.read,logs.read,assets.read,queues.*'),
    ('44444444-4444-4444-4444-444444444443', '11111111-1111-1111-1111-111111111111',
     'Automation User', 'Menjalankan proses yang sudah ada dan membaca hasilnya.',
     'jobs.create,jobs.read,processes.read,logs.read,queues.read'),
    ('44444444-4444-4444-4444-444444444444', '11111111-1111-1111-1111-111111111111',
     'Auditor', 'Hanya membaca — untuk pemeriksaan dan pelaporan.', '*.read');

-- ---------------------------------------------------------------------
-- Lingkungan
-- ---------------------------------------------------------------------
INSERT INTO environments (id, tenant_id, name, description) VALUES
    ('33333333-3333-3333-3333-333333333331', '11111111-1111-1111-1111-111111111111',
     'Development', 'Tempat proses diuji sebelum dipakai sungguhan.'),
    ('33333333-3333-3333-3333-333333333332', '11111111-1111-1111-1111-111111111111',
     'Staging', 'Salinan mendekati produksi untuk uji terima.'),
    ('33333333-3333-3333-3333-333333333333', '11111111-1111-1111-1111-111111111111',
     'Production', 'Proses yang berjalan untuk pekerjaan sebenarnya.');

-- ---------------------------------------------------------------------
-- Antrean dan bucket bawaan
-- ---------------------------------------------------------------------
INSERT INTO queues (id, tenant_id, name, description, max_retries, accept_duplicates) VALUES
    ('55555555-5555-5555-5555-555555555551', '11111111-1111-1111-1111-111111111111',
     'TransactionQueue', 'Antrean bawaan yang dipakai template ReFramework.', 3, FALSE);

INSERT INTO buckets (id, tenant_id, name, description) VALUES
    ('66666666-6666-6666-6666-666666666661', '11111111-1111-1111-1111-111111111111',
     'Shared', 'Berkas yang dipakai bersama oleh proses — masukan, keluaran, lampiran.');

-- ---------------------------------------------------------------------
-- Lisensi
--
-- Pemasangan mandiri: tanpa batas dan tanpa masa berlaku. Barisnya tetap
-- ada supaya halaman Licensing punya sesuatu untuk ditampilkan dan supaya
-- bentuk datanya sudah siap kalau nanti ada pembatasan.
-- ---------------------------------------------------------------------
INSERT INTO licenses (id, tenant_id, product, total, used, expires_at) VALUES
    ('77777777-7777-7777-7777-777777777771', '11111111-1111-1111-1111-111111111111',
     'JakForge Unattended', 0, 0, NULL),
    ('77777777-7777-7777-7777-777777777772', '11111111-1111-1111-1111-111111111111',
     'JakForge Attended', 0, 0, NULL);
