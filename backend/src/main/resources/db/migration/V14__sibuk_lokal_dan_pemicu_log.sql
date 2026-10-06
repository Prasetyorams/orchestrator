-- =====================================================================
-- Jalan lokal Open Assistant di PC attended (usulan tim robot, PR #7,
-- docs/usulan-jadwal-lokal-attended.md): automasi yang dijalankan orang di
-- depan PC — tombol Play, atau jadwal lokal yang sengaja tidak disinkronkan
-- ke Orchestrator.
--
-- 1. "Sibuk lokal": robot menyebutnya di denyut (v1 dan Robot Agent v2).
--    Selama itu Orchestrator tidak memberinya job — dua robot di satu sesi
--    Windows berebut mouse — dan dasbor menampilkan automasi lokal itu.
-- 2. Pemicu baris catatan: job Orchestrator, manual (Play), atau jadwal
--    lokal — supaya halaman Catatan bisa membedakan jalan lokal dari job.
--    Baris yang menyebut pekerjaan diisi 'job' saat ditulis.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Sibuk lokal
-- ---------------------------------------------------------------------

ALTER TABLE robots
    -- Sejak kapan robot sibuk menjalankan automasi lokal; null = tidak.
    ADD COLUMN busy_local_since   TIMESTAMPTZ,
    -- Nama automasinya, dan pemicunya: 'manual' atau 'local-schedule'.
    ADD COLUMN busy_local_name    VARCHAR(200),
    ADD COLUMN busy_local_trigger VARCHAR(20)
        CONSTRAINT ck_robots_busy_local_trigger CHECK (busy_local_trigger IN ('manual', 'local-schedule'));

-- ---------------------------------------------------------------------
-- Pemicu baris catatan
-- ---------------------------------------------------------------------

ALTER TABLE logs
    ADD COLUMN run_trigger VARCHAR(20)
        CONSTRAINT ck_logs_run_trigger CHECK (run_trigger IN ('job', 'manual', 'local-schedule'));

-- Baris yang menyebut pekerjaan dipicu job — dari jalur tulis mana pun (robot
-- v1, Robot Agent, catatan Orchestrator sendiri). Yang dikirim robot sendiri
-- tidak ditimpa.
CREATE FUNCTION isi_pemicu_log() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.run_trigger IS NULL AND NEW.job_id IS NOT NULL THEN
        NEW.run_trigger := 'job';
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER trg_logs_pemicu
    BEFORE INSERT ON logs
    FOR EACH ROW EXECUTE FUNCTION isi_pemicu_log();

UPDATE logs SET run_trigger = 'job' WHERE job_id IS NOT NULL;

-- Saringan Pemicu: jalan lokal jauh lebih jarang daripada baris job.
CREATE INDEX ix_logs_tenant_run_trigger ON logs(tenant_id, run_trigger) WHERE run_trigger IS NOT NULL;
