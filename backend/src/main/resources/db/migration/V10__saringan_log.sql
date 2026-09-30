-- Saringan halaman Catatan: Waktu, Tingkat, Mesin, Proses, Host Identity,
-- dan pencarian teks.
--
-- 1. logs.host_identity: akun Windows tempat robot menjalankan pekerjaan
--    ("VM-01\robot") — "Host Identity" di Orchestrator UiPath.
-- 2. Mesin dan host identity DIISI SAAT BARIS DITULIS, dari pekerjaannya atau
--    robotnya, lewat pemicu basis data — satu tempat untuk semua jalur tulis
--    (robot v1, Robot Agent, catatan Orchestrator sendiri). Mengisinya saat
--    dibaca lewat join berarti saringan Mesin tidak bisa memakai indeks, dan
--    tabel catatan adalah tabel yang paling cepat membesar.
-- 3. Baris lama diisi dari sumber yang sama.
-- 4. Indeks untuk saringan waktu dan untuk daftar pilihan saringan.

ALTER TABLE logs ADD COLUMN host_identity VARCHAR(200);

-- ---------------------------------------------------------------------
-- Pengisian saat ditulis
-- ---------------------------------------------------------------------

-- Yang dikirim robot sendiri tidak ditimpa. Yang kosong diisi dari pekerjaan
-- lebih dulu — mesin dan akun Windows tempat pekerjaan ITU berjalan — lalu
-- dari robotnya: mesin Robot Agent kalau terikat, selain itu mesin dari
-- denyutnya, dan akun Windows yang diatur di dasbor.
CREATE OR REPLACE FUNCTION isi_konteks_log() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    mesin     VARCHAR(160);
    identitas VARCHAR(200);
BEGIN
    IF NEW.job_id IS NOT NULL AND (NEW.machine_name IS NULL OR NEW.host_identity IS NULL) THEN
        SELECT j.machine_name, j.windows_user INTO mesin, identitas
          FROM jobs j
         WHERE j.id = NEW.job_id;

        NEW.machine_name  := COALESCE(NEW.machine_name, mesin);
        NEW.host_identity := COALESCE(NEW.host_identity, identitas);
    END IF;

    IF NEW.robot_name IS NOT NULL AND (NEW.machine_name IS NULL OR NEW.host_identity IS NULL) THEN
        mesin := NULL;
        identitas := NULL;

        SELECT COALESCE(m.name, r.machine_name), r.windows_username INTO mesin, identitas
          FROM robots r
          LEFT JOIN machines m ON m.id = r.machine_id
         WHERE r.tenant_id = NEW.tenant_id AND r.name = NEW.robot_name;

        NEW.machine_name  := COALESCE(NEW.machine_name, mesin);
        NEW.host_identity := COALESCE(NEW.host_identity, identitas);
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_logs_konteks
    BEFORE INSERT ON logs
    FOR EACH ROW EXECUTE FUNCTION isi_konteks_log();

-- ---------------------------------------------------------------------
-- Baris lama
-- ---------------------------------------------------------------------

UPDATE logs l
   SET machine_name  = COALESCE(l.machine_name, j.machine_name),
       host_identity = COALESCE(l.host_identity, j.windows_user)
  FROM jobs j
 WHERE j.id = l.job_id
   AND ((l.machine_name IS NULL AND j.machine_name IS NOT NULL)
        OR (l.host_identity IS NULL AND j.windows_user IS NOT NULL));

UPDATE logs l
   SET machine_name  = COALESCE(l.machine_name, m.name, r.machine_name),
       host_identity = COALESCE(l.host_identity, r.windows_username)
  FROM robots r
  LEFT JOIN machines m ON m.id = r.machine_id
 WHERE r.tenant_id = l.tenant_id
   AND r.name = l.robot_name
   AND ((l.machine_name IS NULL AND COALESCE(m.name, r.machine_name) IS NOT NULL)
        OR (l.host_identity IS NULL AND r.windows_username IS NOT NULL));

-- ---------------------------------------------------------------------
-- Indeks
-- ---------------------------------------------------------------------

-- "15 menit terakhir", "kemarin": tanpa ini setiap saringan waktu membaca
-- tabel dari baris terbaru sampai melewati batas bawahnya.
CREATE INDEX ix_logs_tenant_logged_at ON logs(tenant_id, logged_at DESC);

-- Saringan satu nilai, dan daftar pilihannya.
CREATE INDEX ix_logs_tenant_machine ON logs(tenant_id, machine_name) WHERE machine_name IS NOT NULL;
CREATE INDEX ix_logs_tenant_host    ON logs(tenant_id, host_identity) WHERE host_identity IS NOT NULL;
CREATE INDEX ix_logs_tenant_process ON logs(tenant_id, process_name) WHERE process_name IS NOT NULL;
