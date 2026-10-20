-- Sumber paket (V17): diterbitkan dari Studio, atau diunggah manual lewat
-- dasbor (Tenant › Paket › Unggah Paket).
--
-- Isinya tetap di packages.content (BYTEA) seperti sejak V1, untuk kedua
-- sumber: satu baris berisi metadata DAN isinya, jadi keduanya tidak pernah
-- berbeda — tidak ada berkas paket yang hilang dari disk sementara barisnya
-- masih ada, atau sebaliknya, dan cadangan basis data sudah mencakup semuanya.
--
-- Semua paket yang sudah ada datang lewat POST /api/packages, jalur Studio.

ALTER TABLE packages ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'STUDIO';
ALTER TABLE packages ADD CONSTRAINT ck_packages_source CHECK (source IN ('STUDIO', 'MANUAL_UPLOAD'));

-- Berkas yang diunggah, apa adanya: namanya dan sidik SHA-256-nya. Paket
-- .nupkg disimpan dalam tata letak yang dibaca robot (isi lib/net45/ di
-- akarnya), jadi packages.sha256 adalah sidik ISI YANG DISIMPAN, bukan sidik
-- berkas aslinya; dua kolom ini menjawab "berkas mana yang diunggah".
ALTER TABLE packages ADD COLUMN source_file_name VARCHAR(255);
ALTER TABLE packages ADD COLUMN source_file_sha256 VARCHAR(64);
