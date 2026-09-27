-- =====================================================================
-- Nama sistem berganti dari ForgeHub menjadi OpenOrchestrator.
--
-- V1–V6 sengaja tidak diubah: Flyway menyimpan checksum setiap berkas yang
-- sudah dijalankan, dan berkas yang berubah membuat aplikasi menolak naik.
-- Yang diganti di sini hanya DATA isian awal yang menyebut nama lama.
-- Baris yang sudah disunting pengguna (teksnya lain) dibiarkan.
-- =====================================================================

UPDATE roles
   SET description = 'Akses penuh ke seluruh OpenOrchestrator.'
 WHERE description = 'Akses penuh ke seluruh ForgeHub.';

UPDATE users
   SET display_name = 'OpenOrchestrator Administrator'
 WHERE display_name = 'ForgeHub Administrator';

UPDATE machines
   SET description = 'Mesin tempat OpenOrchestrator berjalan.'
 WHERE description = 'Mesin tempat ForgeHub berjalan.';

-- Akun admin bawaan FH_Admin (singkatan ForgeHub) menjadi OO_Admin, sama
-- dengan openorchestrator.bootstrap.admin-username. Harus di sini, bukan
-- hanya di setelan: penanam awal mencari akun bernama OO_Admin, dan kalau
-- tidak ketemu ia membuat admin KEDUA dengan kata sandi bawaan. Kata sandi
-- akun lama tidak berubah. Tenant yang sudah punya OO_Admin dilewati.
UPDATE users u
   SET username = 'OO_Admin'
 WHERE u.username = 'FH_Admin'
   AND NOT EXISTS (SELECT 1 FROM users o
                    WHERE o.tenant_id = u.tenant_id AND o.username = 'OO_Admin');
