-- Masuk dengan akun Windows Active Directory (V19).
--
-- Hanya pengguna yang DIDAFTARKAN admin yang bisa masuk dengan AD: sandi AD
-- yang benar saja tidak cukup, dan tidak ada akun yang dibuat otomatis saat
-- orang pertama kali masuk. Peran, izin, dan folder tetap diatur di sini.
--
-- Sandi AD TIDAK PERNAH disimpan: pengguna AD tidak punya password_hash sama
-- sekali, dan masuk lokal untuk akun AD ditolak.

ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

ALTER TABLE users ADD COLUMN auth_provider VARCHAR(20) NOT NULL DEFAULT 'LOCAL';
ALTER TABLE users ADD CONSTRAINT ck_users_auth_provider CHECK (auth_provider IN ('LOCAL', 'AD'));

-- Identitas direktori:
-- - ad_object_guid: objectGUID — identitas STABIL. Akun yang dihapus lalu dibuat
--   ulang dengan nama yang sama punya GUID lain, jadi tidak dianggap orang yang sama.
--   Null = didaftarkan tanpa akun layanan; ditautkan saat masuk pertama, sekali.
-- - ad_username: sAMAccountName — nama masuk, BISA berubah; diperbarui saat masuk
--   kalau GUID-nya sama.
-- - ad_directory: base DN domain yang menerbitkan identitas itu.
ALTER TABLE users ADD COLUMN ad_username VARCHAR(64);
ALTER TABLE users ADD COLUMN ad_object_guid UUID;
ALTER TABLE users ADD COLUMN ad_directory VARCHAR(255);
ALTER TABLE users ADD COLUMN ad_linked_at TIMESTAMPTZ;

-- Akun lokal selalu punya sandi dan tidak punya identitas AD; akun AD sebaliknya.
ALTER TABLE users ADD CONSTRAINT ck_users_identity_by_provider CHECK (
    (auth_provider = 'LOCAL' AND password_hash IS NOT NULL AND ad_username IS NULL AND ad_object_guid IS NULL)
    OR (auth_provider = 'AD' AND password_hash IS NULL AND ad_username IS NOT NULL));

-- Masuk AD tidak membawa penyewa: satu identitas AD = tepat satu pengguna.
CREATE UNIQUE INDEX uq_users_ad_guid ON users(ad_object_guid) WHERE ad_object_guid IS NOT NULL;
CREATE UNIQUE INDEX uq_users_ad_username ON users(lower(ad_username)) WHERE auth_provider = 'AD';

-- Siapa membuat dan terakhir mengubah (created_at dan last_login_at sudah ada sejak V1).
ALTER TABLE users ADD COLUMN created_by VARCHAR(160);
ALTER TABLE users ADD COLUMN updated_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN updated_by VARCHAR(160);
