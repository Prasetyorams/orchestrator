-- =====================================================================
-- Nama proses dan pemicu kini unik PER FOLDER, bukan per penyewa.
--
-- Seperti di UiPath Orchestrator: paket yang sama boleh dipasang sebagai
-- proses di beberapa folder sekaligus — "RPA Challenge" di Shared untuk
-- uji coba dan di Keuangan untuk pekerjaan sungguhan — masing-masing
-- dengan versi, pemicu, robot, dan riwayatnya sendiri. Sebelum ini,
-- menambahkannya ke folder kedua ditolak dengan "sudah dipakai di folder
-- lain".
--
-- Pemicu ikut: pemicu tinggal di folder prosesnya, dan nama pemicu yang
-- unik per penyewa berarti "Harian" di satu folder menghalangi "Harian"
-- di folder lain — atau lebih buruk, menyimpannya dari folder kedua
-- diam-diam memindahkan pemicu milik folder pertama.
--
-- Antrean, aset, dan ember TETAP unik per penyewa. Studio dan JakRunner
-- memanggil ketiganya lewat nama tanpa menyebut folder, dan belum ada
-- cara bagi robot untuk mengatakan folder mana yang ia maksud.
--
-- Permintaan yang hanya menyebut NAMA proses (Studio: Start Job) tetap
-- dilayani: nama yang hanya ada di satu folder jelas maksudnya; kalau ada
-- di beberapa folder, yang di folder bawaan yang dipakai; selain itu
-- ditolak dan diminta menyebut foldernya. Keputusan itu ada di
-- CatalogService.pilihFolder, bukan di sini.
-- =====================================================================

ALTER TABLE processes DROP CONSTRAINT uq_processes_tenant_name;
ALTER TABLE processes ADD CONSTRAINT uq_processes_folder_name UNIQUE (tenant_id, folder_id, name);

ALTER TABLE triggers DROP CONSTRAINT uq_triggers_tenant_name;
ALTER TABLE triggers ADD CONSTRAINT uq_triggers_folder_name UNIQUE (tenant_id, folder_id, name);

-- Pencarian lewat nama saja (Studio, penerbitan paket) tidak bisa memakai
-- indeks unik di atas, yang diawali folder.
CREATE INDEX ix_processes_tenant_name ON processes(tenant_id, name);
CREATE INDEX ix_triggers_tenant_name  ON triggers(tenant_id, name);

-- Jumlah pekerjaan dan keadaan terakhir tiap proses di halaman Proses kini
-- dihitung per (folder, nama).
CREATE INDEX ix_jobs_tenant_folder_process ON jobs(tenant_id, folder_id, process_name);

-- Pekerjaan dan pemicu yang masuk TANPA folder (alat pindahan, kode lama)
-- mengikuti proses bernama sama. Kalau ada beberapa, yang di folder bawaan
-- lebih dulu, lalu yang paling lama — aturan yang sama dengan layanannya,
-- supaya keduanya tidak berbeda pendapat tentang folder sebuah pekerjaan.
CREATE OR REPLACE FUNCTION isi_folder_dari_proses() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.folder_id IS NULL THEN
        SELECT p.folder_id INTO NEW.folder_id
          FROM processes p
          JOIN folders f ON f.id = p.folder_id
         WHERE p.tenant_id = NEW.tenant_id AND p.name = NEW.process_name
         ORDER BY f.is_default DESC, p.created_at
         LIMIT 1;

        IF NEW.folder_id IS NULL THEN
            NEW.folder_id := folder_bawaan(NEW.tenant_id);
        END IF;
    END IF;

    RETURN NEW;
END
$$;
