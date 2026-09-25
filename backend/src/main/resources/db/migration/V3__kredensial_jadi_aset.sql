-- =====================================================================
-- Kredensial menjadi aset bertipe Credential.
--
-- Sebelumnya kredensial punya tabel dan halamannya sendiri, terpisah dari
-- aset. Sekarang keduanya satu daftar, seperti di UiPath Orchestrator:
-- kredensial adalah salah satu TIPE aset, dan orang yang mencari "nilai yang
-- dipakai robot" cukup membuka satu halaman.
--
-- Yang berubah:
--
--   1. assets mendapat kolom username. Hanya aset bertipe Credential yang
--      mengisinya. Kata sandinya tetap di value_text, tersandi oleh SecretBox
--      yang SAMA dengan password_enc — tanpa label atau konteks tambahan —
--      jadi nilai lama cukup disalin apa adanya, tanpa pernah dibuka.
--
--   2. Setiap baris credentials dipindah menjadi aset bertipe Credential,
--      dengan id yang sama supaya jejaknya masih bisa diikuti.
--
--   3. Tabel credentials DIGANTI NAMA menjadi credentials_lama, bukan
--      dibuang. Pindahan kata sandi yang keliru baru ketahuan saat robot
--      gagal masuk, dan pada saat itu salinan aslinya harus masih ada.
--      Tabel itu tidak lagi dibaca siapa pun dan boleh dibuang oleh migrasi
--      berikutnya setelah semua pemasangan terbukti aman.
--
-- Endpoint /api/credentials TETAP ada dan kini membaca aset bertipe
-- Credential. Activity Get Credential di Studio memanggilnya, dan workflow
-- yang sudah diterbitkan tidak boleh berhenti bekerja karena pindahan ini.
-- =====================================================================

ALTER TABLE assets ADD COLUMN username VARCHAR(200);

-- ---------------------------------------------------------------------
-- Nama yang bentrok
--
-- Dulu aset dan kredensial boleh bernama sama; sekarang tidak, karena
-- keduanya satu daftar. Kredensial yang namanya sudah dipakai aset lain
-- dipindah dengan akhiran " (kredensial)" — asetnya tidak ditimpa — dan
-- sebuah peringatan dicatat, karena workflow yang memanggil Get Credential
-- dengan nama lama harus diperbarui dan tidak ada yang akan tahu tanpa itu.
--
-- Peringatannya ditulis SEBELUM pemindahan: sesudahnya setiap kredensial
-- sudah punya aset bernama sama, dan semuanya akan terlihat bentrok.
--
-- Dua kalimat berbeda, karena nasibnya berbeda: nama berakhiran pun bisa
-- saja sudah terpakai, dan kredensial seperti itu TIDAK dipindah sama
-- sekali. Menyebutnya "sudah bernama baru" akan mengirim orang mencari aset
-- yang isinya ternyata milik orang lain.
-- ---------------------------------------------------------------------
INSERT INTO alerts (tenant_id, severity, title, message, source, is_read, created_at)
SELECT c.tenant_id,
       'Warning',
       'Kredensial "' || c.name || '" dipindah dengan nama baru',
       'Nama "' || c.name || '" sudah dipakai aset lain, jadi kredensial ini sekarang bernama "'
           || left(c.name, 187) || ' (kredensial)". Perbarui activity Get Credential yang memakainya.',
       'assets',
       FALSE,
       now()
  FROM credentials c
  JOIN assets a ON a.tenant_id = c.tenant_id AND a.name = c.name
 WHERE NOT EXISTS (SELECT 1 FROM assets b
                    WHERE b.tenant_id = c.tenant_id
                      AND b.name = left(c.name, 187) || ' (kredensial)');

INSERT INTO alerts (tenant_id, severity, title, message, source, is_read, created_at)
SELECT c.tenant_id,
       'Error',
       'Kredensial "' || c.name || '" tidak bisa dipindah',
       'Nama "' || c.name || '" dan "' || left(c.name, 187) || ' (kredensial)" sudah dipakai aset lain. '
           || 'Salinannya tetap ada di tabel credentials_lama; buat ulang sebagai aset bertipe Credential.',
       'assets',
       FALSE,
       now()
  FROM credentials c
  JOIN assets a ON a.tenant_id = c.tenant_id AND a.name = c.name
 WHERE EXISTS (SELECT 1 FROM assets b
                WHERE b.tenant_id = c.tenant_id
                  AND b.name = left(c.name, 187) || ' (kredensial)');

-- Satu INSERT ... SELECT membaca keadaan tabel SEBELUM barisnya sendiri
-- masuk, jadi LEFT JOIN di bawah hanya melihat aset yang sudah ada dari
-- awal, bukan kredensial yang baru saja dipindah.
--
-- left(..., 187): nama paling panjang 200 karakter, dan akhirannya 13.
--
-- ON CONFLICT DO NOTHING hanya untuk kasus yang nyaris mustahil — nama
-- berakhiran " (kredensial)" pun sudah dipakai aset lain. Baris seperti itu
-- tetap tersimpan utuh di credentials_lama, dan migrasi ini tidak gagal
-- karenanya: migrasi yang gagal berarti ForgeHub tidak bisa dinyalakan sama
-- sekali.
INSERT INTO assets (id, tenant_id, name, type, username, value_text, description, scope,
                    created_at, updated_at)
SELECT c.id,
       c.tenant_id,
       CASE WHEN a.id IS NULL THEN c.name ELSE left(c.name, 187) || ' (kredensial)' END,
       'Credential',
       c.username,
       c.password_enc,
       c.description,
       'Global',
       c.created_at,
       c.created_at
  FROM credentials c
  LEFT JOIN assets a ON a.tenant_id = c.tenant_id AND a.name = c.name
    ON CONFLICT (tenant_id, name) DO NOTHING;

ALTER TABLE credentials RENAME TO credentials_lama;
