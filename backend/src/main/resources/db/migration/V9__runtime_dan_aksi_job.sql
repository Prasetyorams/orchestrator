-- Start Job bergaya UiPath dan aksi job dari dasbor.
--
-- 1. Runtime mesin. Setiap mesin menyatakan tipe runtime apa yang ia layani
--    (Production, Testing, Development) dan berapa banyak job tipe itu yang
--    boleh berjalan bersamaan. Start Job hanya menawarkan runtime yang ada di
--    mesin folder itu, dan robot hanya mengambil job yang runtime-nya dimiliki
--    mesinnya.
-- 2. Job menyimpan tipe runtime dan mesin sasarannya, serta permintaan
--    dimatikan paksa (Kill), permintaan jeda (Pause/Resume, usulan sisi robot
--    di Studio docs/usulan-pause-job.md), dan asal job yang dijalankan ulang.
-- 3. Proses punya prioritas bawaan — yang dipakai job berprioritas "Inherited".

-- ---------------------------------------------------------------------
-- 1. Runtime mesin
-- ---------------------------------------------------------------------

-- Baris hanya ada untuk jumlah di atas nol: "tidak punya runtime Testing"
-- berarti tidak ada barisnya, bukan baris bernilai 0 yang harus diingat untuk
-- disaring di setiap kueri.
CREATE TABLE machine_runtimes (
    machine_id    UUID          NOT NULL REFERENCES machines(id) ON DELETE CASCADE,
    runtime_type  VARCHAR(32)   NOT NULL,
    slots         INTEGER       NOT NULL CHECK (slots BETWEEN 1 AND 50),
    PRIMARY KEY (machine_id, runtime_type)
);

-- Mesin yang sudah ada mendapat runtime Production sebanyak slotnya. Sebelum
-- migrasi ini job tidak punya tipe runtime dan berjalan di mesin mana pun;
-- sesudahnya mesin yang sama harus tetap bisa menjalankan semuanya.
INSERT INTO machine_runtimes (machine_id, runtime_type, slots)
SELECT id, 'Production', LEAST(GREATEST(slots, 1), 50) FROM machines;

-- machines.slots tetap ada dan sekarang SELALU jumlah runtime mesinnya
-- (dijaga MachineService). Robot Agent membaca kolom itu sebagai batas job
-- bersamaan, dan maknanya tidak berubah.
UPDATE machines m
   SET slots = (SELECT sum(r.slots) FROM machine_runtimes r WHERE r.machine_id = m.id);

-- ---------------------------------------------------------------------
-- 2. Job
-- ---------------------------------------------------------------------

ALTER TABLE jobs
    -- Tipe runtime yang diminta. NULL = runtime mana pun (Studio, pemicu, API
    -- lama); saat diambil robot, NULL diisi tipe yang benar-benar dipakai.
    ADD COLUMN runtime_type         VARCHAR(32),
    -- Mesin yang diminta. Berbeda dari machine_name, yang berisi mesin tempat
    -- job akhirnya berjalan.
    ADD COLUMN target_machine_name  VARCHAR(160),
    -- Job lama yang dijalankan ulang menjadi job ini (aksi Jalankan Ulang).
    ADD COLUMN restarted_from       UUID,
    -- Diminta dimatikan paksa: robot menerima KillJob, tanpa menunggu jeda berhenti rapi.
    ADD COLUMN kill_requested_at    TIMESTAMPTZ,
    -- Dasbor meminta jeda. Bukan keadaan baru: job tetap RUNNING, karena
    -- keadaan baru menyentuh penjagaan 409, pemantau, lease v2, dan setiap
    -- tempat yang bertanya "apakah job ini berjalan".
    ADD COLUMN pause_requested      BOOLEAN       NOT NULL DEFAULT FALSE,
    -- Menurut ROBOT, job sedang ditahan sejak kapan, dan siapa yang menjedanya:
    -- 'dashboard' (PauseJob) atau 'local' (tombol Jeda di PC robot).
    ADD COLUMN paused_at            TIMESTAMPTZ,
    ADD COLUMN pause_source         VARCHAR(16),
    -- Lama dijeda yang sudah selesai, dalam detik — jaring pengaman batas
    -- waktu tidak menghitungnya.
    ADD COLUMN paused_seconds       INTEGER       NOT NULL DEFAULT 0;

-- Prioritas yang ditulis dengan huruf lain ("high") diseragamkan. Pengurutan
-- klaim membandingkan persis 'High' dan 'Normal', jadi "high" selama ini
-- diam-diam diperlakukan sebagai Low.
UPDATE jobs
   SET priority = initcap(lower(priority))
 WHERE lower(priority) IN ('low', 'normal', 'high')
   AND priority NOT IN ('Low', 'Normal', 'High');

UPDATE triggers
   SET priority = initcap(lower(priority))
 WHERE lower(priority) IN ('low', 'normal', 'high')
   AND priority NOT IN ('Low', 'Normal', 'High');

-- ---------------------------------------------------------------------
-- 3. Prioritas bawaan proses
-- ---------------------------------------------------------------------

ALTER TABLE processes ADD COLUMN priority VARCHAR(8) NOT NULL DEFAULT 'Normal';
