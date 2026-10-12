-- V15: butir antrean tahu job mana yang memegangnya.
--
-- Usulan tim robot (catatan 9 Okt 2026): butir yang diambil sebuah job lalu
-- ditinggal karena job-nya berakhir FAULTED/STOPPED tertahan IN_PROGRESS
-- selamanya — tidak diproses ulang, tidak terlihat sebagai masalah, dan
-- referensinya menolak butir pengganti.
--
-- job_id diisi saat butir diambil dengan token Executor (job Robot Agent).
-- Pengambilan oleh pengguna atau robot v1 membiarkannya NULL; butir seperti
-- itu hanya ditolong penyapu (IN_PROGRESS lebih lama dari batasnya →
-- ABANDONED). Tanpa foreign key: job lama boleh dibersihkan tanpa menyentuh
-- riwayat butir.

ALTER TABLE queue_items ADD COLUMN job_id UUID;

-- Saat job berakhir: butir yang masih dipegangnya.
CREATE INDEX ix_queue_items_held_by_job ON queue_items(tenant_id, job_id)
    WHERE status = 'IN_PROGRESS' AND job_id IS NOT NULL;

-- Penyapu: butir yang sudah terlalu lama diproses.
CREATE INDEX ix_queue_items_in_progress_since ON queue_items(started_at)
    WHERE status = 'IN_PROGRESS';
