-- =====================================================================
-- Mesin per folder: pendaftaran Machine ke Folder, seperti di UiPath.
--
-- Sebelum ini "mesin sebuah folder" diturunkan dari robot yang ditugaskan
-- ke folder itu. Sejak V12 mesin didaftarkan ke folder SECARA TERPISAH
-- (folder_machines), dan sebuah mesin hanya menjalankan job folder tempat
-- ia terdaftar: Start Job hanya menawarkan mesin folder prosesnya, job yang
-- meminta mesin folder lain ditolak, dan robot hanya mengambil job folder
-- tempat mesinnya terdaftar.
--
-- Supaya tidak ada yang tiba-tiba berhenti bekerja:
--   * setiap mesin didaftarkan ke folder tempat robotnya kini bekerja;
--   * mesin yang belum dipakai robot mana pun masuk folder bawaan;
--   * mesin baru — termasuk yang terdaftar sendiri lewat denyut robot dan
--     Open Assistant — langsung masuk folder bawaan, seperti robot dan
--     pengguna baru (V4). Mengeluarkannya dari sana keputusan admin.
--
-- Kecuali mesin yang ditanam OpenOrchestrator sendiri saat naik
-- (InitialDataSeeder: nama host container, satu baris baru setiap container
-- dibuat ulang — sejak V12 tidak lagi di dalam container) dan tidak pernah
-- dipakai: mesin itu tidak menjalankan job, dan di folder ia hanya menjadi
-- pilihan Start Job yang selalu offline.
--
-- machines.state: Active (bekerja seperti biasa), Maintenance (sementara
-- tidak mengambil job baru), atau Disabled (tidak dipakai sama sekali).
-- Job yang sedang berjalan tidak dihentikan oleh keduanya.
-- =====================================================================

ALTER TABLE machines
    ADD COLUMN state VARCHAR(16) NOT NULL DEFAULT 'Active'
        CONSTRAINT ck_machines_state CHECK (state IN ('Active', 'Maintenance', 'Disabled'));

CREATE TABLE folder_machines (
    folder_id   UUID          NOT NULL REFERENCES folders(id) ON DELETE CASCADE,
    machine_id  UUID          NOT NULL REFERENCES machines(id) ON DELETE CASCADE,
    tenant_id   UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- Siapa yang mendaftarkan; null untuk pendaftaran otomatis.
    created_by  VARCHAR(80),
    PRIMARY KEY (folder_id, machine_id)
);

CREATE INDEX ix_folder_machines_machine ON folder_machines(machine_id);
CREATE INDEX ix_folder_machines_tenant_folder ON folder_machines(tenant_id, folder_id);

-- ---------------------------------------------------------------------
-- Pendaftaran yang sudah berlaku hari ini
-- ---------------------------------------------------------------------

-- Mesin tempat robot folder itu bekerja: mesin Robot Agent yang terikat,
-- atau mesin yang disebut denyut robot v1.
INSERT INTO folder_machines (folder_id, machine_id, tenant_id, created_at)
SELECT DISTINCT fr.folder_id, m.id, m.tenant_id, now()
  FROM folder_robots fr
  JOIN robots r ON r.id = fr.robot_id
  JOIN machines m ON m.tenant_id = r.tenant_id
                 AND (m.id = r.machine_id OR (r.machine_id IS NULL AND m.name = r.machine_name))
ON CONFLICT DO NOTHING;

-- Mesin tanpa robot: folder bawaan — selain mesin tanaman yang tidak pernah
-- dipakai (lihat di atas).
INSERT INTO folder_machines (folder_id, machine_id, tenant_id, created_at)
SELECT folder_bawaan(m.tenant_id), m.id, m.tenant_id, now()
  FROM machines m
 WHERE NOT EXISTS (SELECT 1 FROM folder_machines fm WHERE fm.machine_id = m.id)
   AND NOT (COALESCE(m.description, '') = 'Mesin tempat OpenOrchestrator berjalan.'
            AND m.key_hash IS NULL
            AND m.last_agent_login_at IS NULL
            AND NOT EXISTS (SELECT 1 FROM robots r
                             WHERE r.tenant_id = m.tenant_id
                               AND (r.machine_id = m.id OR r.machine_name = m.name)))
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------
-- Mesin baru: folder bawaan
-- ---------------------------------------------------------------------

CREATE FUNCTION daftarkan_mesin_ke_folder_bawaan() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO folder_machines (folder_id, machine_id, tenant_id, created_at)
    VALUES (folder_bawaan(NEW.tenant_id), NEW.id, NEW.tenant_id, now())
    ON CONFLICT DO NOTHING;

    RETURN NEW;
END
$$;

CREATE TRIGGER trg_machines_folder_bawaan AFTER INSERT ON machines
    FOR EACH ROW EXECUTE FUNCTION daftarkan_mesin_ke_folder_bawaan();
