-- =====================================================================
-- Peran kustom: izin peran kini DITEGAKKAN di setiap endpoint.
--
-- Sebelum ini kolom roles.permissions hanya dibaca layar Peran; ForgeHub
-- sendiri hanya membedakan "Administrator" dan "bukan". Sejak V6 setiap
-- endpoint /api memeriksa izin peran pemanggilnya (IzinInterceptor), dan
-- peran bisa dibuat, diubah, dan dihapus dari layar Tenant › Pengguna ›
-- Peran.
--
-- Karena izin kini berarti sesuatu, peran bawaan di bawah disesuaikan
-- supaya tidak ada yang tiba-tiba terkunci:
--
--   * Akun robot (JakRunner) memanggil denyut, mengambil pekerjaan,
--     melaporkan keadaan, mengirim catatan, membaca aset, dan memproses
--     antrean. Automation Developer dan Automation User — dua peran yang
--     mungkin dipakai akun robot hari ini — mendapat izin itu. Peran baru
--     "Robot" berisi PERSIS izin itu, untuk akun robot berikutnya.
--
--   * Automation Developer mendapat seluruh isi folder (seperti di
--     Orchestrator): proses, pekerjaan, pemicu, paket, antrean, aset, ember.
--
--   * Administrator tetap "*", dan tidak bisa diubah atau dihapus. Auditor
--     tetap "*.read".
--
-- Peran yang izinnya sudah pernah diubah orang TIDAK disentuh: pembaruan
-- hanya berlaku kalau isinya masih persis isi bawaan dari V2.
-- =====================================================================

UPDATE roles
   SET permissions = 'processes.*,jobs.*,triggers.*,packages.*,queues.*,assets.*,buckets.*,'
                  || 'robots.read,robots.update,logs.read,logs.create,folders.read,'
                  || 'machines.read,environments.read,alerts.read',
       description = 'Menerbitkan paket, mengelola isi folder, menjalankan pekerjaan.'
 WHERE name = 'Automation Developer'
   AND permissions = 'packages.*,processes.*,jobs.create,jobs.read,logs.read,assets.read,queues.*';

UPDATE roles
   SET permissions = 'processes.read,jobs.read,jobs.create,jobs.update,triggers.read,packages.read,'
                  || 'queues.read,queues.update,assets.read,buckets.read,'
                  || 'robots.read,robots.update,logs.read,logs.create,alerts.read'
 WHERE name = 'Automation User'
   AND permissions = 'jobs.create,jobs.read,processes.read,logs.read,queues.read';

-- Setiap penyewa punya kelima peran bawaan. Penyewa yang datang lewat alat
-- pindahan belum punya satu pun, dan tanpa baris peran penggunanya tidak
-- punya izin apa pun — kecuali Administrator, yang selalu berarti semuanya.
INSERT INTO roles (id, tenant_id, name, description, permissions, created_at)
SELECT gen_random_uuid(), t.id, b.name, b.description, b.permissions, now()
  FROM tenants t
 CROSS JOIN (VALUES
       ('Administrator', 'Akses penuh ke seluruh ForgeHub.', '*'),
       ('Automation Developer', 'Menerbitkan paket, mengelola isi folder, menjalankan pekerjaan.',
        'processes.*,jobs.*,triggers.*,packages.*,queues.*,assets.*,buckets.*,'
        || 'robots.read,robots.update,logs.read,logs.create,folders.read,'
        || 'machines.read,environments.read,alerts.read'),
       ('Automation User', 'Menjalankan proses yang sudah ada dan membaca hasilnya.',
        'processes.read,jobs.read,jobs.create,jobs.update,triggers.read,packages.read,'
        || 'queues.read,queues.update,assets.read,buckets.read,'
        || 'robots.read,robots.update,logs.read,logs.create,alerts.read'),
       ('Auditor', 'Hanya membaca — untuk pemeriksaan dan pelaporan.', '*.read'),
       ('Robot', 'Akun JakRunner: denyut, mengambil dan melaporkan pekerjaan, catatan, aset, antrean.',
        'robots.update,jobs.read,jobs.create,jobs.update,logs.create,assets.read,'
        || 'queues.read,queues.update,buckets.read,buckets.update')
       ) AS b(name, description, permissions)
ON CONFLICT (tenant_id, name) DO NOTHING;
