-- Penanda library di project.json (V21).
--
-- Studio yang lebih baru menulis "libraries": ["Id", ...] di project.json —
-- dependensi yang berupa library OpenStudio, versinya tetap di "dependencies".
-- Id yang ditandai begitu pasti library: kalau tidak ada di feed, job-nya
-- gagal saat diambil robot (LibraryNotFound), bukan dianggap activity.
--
-- Teks JSON (larik Id), seperti packages.dependencies. NULL bersama
-- dependencies = belum dibaca dari isi paket.
ALTER TABLE packages ADD COLUMN libraries TEXT;

-- Paket yang terbit sesudah V20 sudah menyimpan dependensinya, tetapi belum
-- penanda ini. Dikosongkan supaya semuanya dibaca ulang dari isinya saat
-- pertama dibutuhkan — sekali per versi paket.
UPDATE packages SET dependencies = NULL;
