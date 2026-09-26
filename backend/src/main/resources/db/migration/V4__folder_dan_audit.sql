-- =====================================================================
-- Folder dan jejak audit.
--
-- FOLDER, seperti di UiPath Orchestrator: proses, pemicu, antrean, aset,
-- dan ember penyimpanan kini tinggal di sebuah folder, dan dasbor
-- menampilkan isi SATU folder pada satu waktu. Folder boleh bersarang.
--
--   * Setiap penyewa punya satu folder BAWAAN bernama "Shared". Semua yang
--     sudah ada sebelum migrasi ini masuk ke sana, jadi tidak ada yang
--     hilang dari pandangan dan robot tetap menjalankan hal yang sama.
--
--   * Setiap pengguna boleh punya satu folder PRIBADI ("Folder Saya"),
--     hanya terlihat oleh pemiliknya. Dibuat saat pertama kali dibuka.
--
--   * Nama proses, antrean, aset, dan ember TETAP unik per penyewa, bukan
--     per folder. Studio dan JakRunner mencari semuanya lewat NAMA tanpa
--     menyebut folder (Get Asset, Add Queue Item, ...), dan nama yang sama
--     di dua folder akan membuat pencarian itu menebak.
--
--   * Robot dan pengguna DITUGASKAN ke folder. Robot hanya mengambil
--     pekerjaan dari folder tempat ia ditugaskan — kecuali pekerjaan yang
--     menyebut nama robotnya langsung. Pengguna selain Administrator hanya
--     melihat folder tempat ia ditugaskan.
--
-- Baris yang masuk TANPA folder — dari Studio, JakRunner, alat pindahan,
-- atau kode yang belum mengenal folder — tidak ditolak: pemicu basis data
-- di bawah menaruhnya di folder bawaan. Pekerjaan dan pemicu mengikuti
-- folder prosesnya. Robot dan pengguna baru ditugaskan ke folder bawaan.
-- Aturan itu ada DI SINI, satu tempat, bukan disebar ke setiap INSERT.
--
-- AUDIT: siapa mengubah apa dan kapan. Hanya perubahan yang dilakukan
-- orang; lalu lintas robot (denyut, catatan, laporan keadaan) tidak dicatat,
-- karena jumlahnya menenggelamkan satu-dua perubahan yang justru dicari.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Folder
-- ---------------------------------------------------------------------

CREATE TABLE folders (
    id           UUID PRIMARY KEY,
    tenant_id    UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    -- Tanpa ON DELETE: folder yang masih punya anak tidak boleh dihapus,
    -- dan layanan memeriksanya lebih dulu. Kalau pemeriksaan itu terlewat,
    -- basis data yang menolak — bukan diam-diam membuang seluruh cabangnya.
    parent_id    UUID          REFERENCES folders(id),
    name         VARCHAR(120)  NOT NULL,
    description  VARCHAR(400),
    is_default   BOOLEAN       NOT NULL DEFAULT FALSE,
    -- Terisi hanya untuk folder pribadi. Tanpa ON DELETE juga: pengguna yang
    -- folder pribadinya masih berisi tidak boleh terhapus bersama isinya.
    owner_id     UUID          REFERENCES users(id),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_folders_bawaan_di_akar CHECK (NOT is_default OR parent_id IS NULL),
    CONSTRAINT ck_folders_pribadi_di_akar CHECK (owner_id IS NULL OR (parent_id IS NULL AND NOT is_default))
);

-- Satu folder bawaan per penyewa, satu folder pribadi per pengguna.
CREATE UNIQUE INDEX uq_folders_bawaan  ON folders(tenant_id) WHERE is_default;
CREATE UNIQUE INDEX uq_folders_pribadi ON folders(owner_id)  WHERE owner_id IS NOT NULL;

-- Nama unik di antara saudara-saudaranya, tanpa membedakan huruf besar.
-- Folder akar tidak punya induk; tenant_id dipakai sebagai "induk" mereka
-- karena NULL tidak pernah dianggap sama dengan NULL oleh indeks unik.
-- Folder pribadi dikecualikan: semuanya bernama "Folder Saya".
CREATE UNIQUE INDEX uq_folders_nama
    ON folders(tenant_id, COALESCE(parent_id, tenant_id), lower(name))
    WHERE owner_id IS NULL;

CREATE INDEX ix_folders_tenant_parent ON folders(tenant_id, parent_id);

-- UUID tetap untuk folder bawaan penyewa bawaan, sama seperti di V2: mudah
-- dikenali saat membuka basis datanya langsung.
INSERT INTO folders (id, tenant_id, name, description, is_default, created_at)
SELECT CASE WHEN t.id = '11111111-1111-1111-1111-111111111111'
            THEN '88888888-8888-8888-8888-888888888881'::uuid
            ELSE gen_random_uuid() END,
       t.id,
       'Shared',
       'Folder bawaan. Semua yang dibuat tanpa menyebut folder masuk ke sini.',
       TRUE,
       now()
  FROM tenants t;

-- ---------------------------------------------------------------------
-- Penugasan ke folder
-- ---------------------------------------------------------------------

CREATE TABLE folder_users (
    folder_id   UUID          NOT NULL REFERENCES folders(id) ON DELETE CASCADE,
    user_id     UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tenant_id   UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (folder_id, user_id)
);

CREATE TABLE folder_robots (
    folder_id   UUID          NOT NULL REFERENCES folders(id) ON DELETE CASCADE,
    robot_id    UUID          NOT NULL REFERENCES robots(id) ON DELETE CASCADE,
    tenant_id   UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (folder_id, robot_id)
);

CREATE INDEX ix_folder_users_tenant_user   ON folder_users(tenant_id, user_id);
CREATE INDEX ix_folder_robots_tenant_robot ON folder_robots(tenant_id, robot_id);

-- Semua yang sudah ada ditugaskan ke folder bawaan: sebelum migrasi ini
-- setiap robot menjalankan pekerjaan apa pun dan setiap pengguna melihat
-- segalanya, dan sesudahnya harus tetap begitu sampai ada yang mengubahnya.
INSERT INTO folder_users (folder_id, user_id, tenant_id, created_at)
SELECT f.id, u.id, u.tenant_id, now()
  FROM users u
  JOIN folders f ON f.tenant_id = u.tenant_id AND f.is_default;

INSERT INTO folder_robots (folder_id, robot_id, tenant_id, created_at)
SELECT f.id, r.id, r.tenant_id, now()
  FROM robots r
  JOIN folders f ON f.tenant_id = r.tenant_id AND f.is_default;

-- ---------------------------------------------------------------------
-- Isi folder
-- ---------------------------------------------------------------------

ALTER TABLE processes ADD COLUMN folder_id UUID REFERENCES folders(id);
ALTER TABLE triggers  ADD COLUMN folder_id UUID REFERENCES folders(id);
ALTER TABLE jobs      ADD COLUMN folder_id UUID REFERENCES folders(id);
ALTER TABLE queues    ADD COLUMN folder_id UUID REFERENCES folders(id);
ALTER TABLE assets    ADD COLUMN folder_id UUID REFERENCES folders(id);
ALTER TABLE buckets   ADD COLUMN folder_id UUID REFERENCES folders(id);

UPDATE processes x SET folder_id = f.id FROM folders f WHERE f.tenant_id = x.tenant_id AND f.is_default;
UPDATE triggers  x SET folder_id = f.id FROM folders f WHERE f.tenant_id = x.tenant_id AND f.is_default;
UPDATE jobs      x SET folder_id = f.id FROM folders f WHERE f.tenant_id = x.tenant_id AND f.is_default;
UPDATE queues    x SET folder_id = f.id FROM folders f WHERE f.tenant_id = x.tenant_id AND f.is_default;
UPDATE assets    x SET folder_id = f.id FROM folders f WHERE f.tenant_id = x.tenant_id AND f.is_default;
UPDATE buckets   x SET folder_id = f.id FROM folders f WHERE f.tenant_id = x.tenant_id AND f.is_default;

ALTER TABLE processes ALTER COLUMN folder_id SET NOT NULL;
ALTER TABLE triggers  ALTER COLUMN folder_id SET NOT NULL;
ALTER TABLE jobs      ALTER COLUMN folder_id SET NOT NULL;
ALTER TABLE queues    ALTER COLUMN folder_id SET NOT NULL;
ALTER TABLE assets    ALTER COLUMN folder_id SET NOT NULL;
ALTER TABLE buckets   ALTER COLUMN folder_id SET NOT NULL;

CREATE INDEX ix_processes_tenant_folder     ON processes(tenant_id, folder_id);
CREATE INDEX ix_triggers_tenant_folder      ON triggers(tenant_id, folder_id);
CREATE INDEX ix_jobs_tenant_folder_created  ON jobs(tenant_id, folder_id, created_at DESC);
CREATE INDEX ix_jobs_tenant_folder_state    ON jobs(tenant_id, folder_id, state);
CREATE INDEX ix_queues_tenant_folder        ON queues(tenant_id, folder_id);
CREATE INDEX ix_assets_tenant_folder        ON assets(tenant_id, folder_id);
CREATE INDEX ix_buckets_tenant_folder       ON buckets(tenant_id, folder_id);

-- ---------------------------------------------------------------------
-- Folder bawaan untuk baris yang datang tanpa folder
-- ---------------------------------------------------------------------

-- Id folder bawaan sebuah penyewa, DIBUAT kalau belum ada. Penyewa yang
-- ditambahkan sesudah migrasi ini — lewat alat pindahan, misalnya — belum
-- punya folder bawaan, dan tanpa ini baris pertamanya akan ditolak karena
-- folder_id kosong.
CREATE FUNCTION folder_bawaan(penyewa UUID) RETURNS UUID
LANGUAGE plpgsql AS $$
DECLARE
    hasil UUID;
BEGIN
    SELECT id INTO hasil FROM folders WHERE tenant_id = penyewa AND is_default;

    IF hasil IS NULL THEN
        INSERT INTO folders (id, tenant_id, name, description, is_default, created_at)
        VALUES (gen_random_uuid(), penyewa, 'Shared',
                'Folder bawaan. Semua yang dibuat tanpa menyebut folder masuk ke sini.', TRUE, now())
        ON CONFLICT DO NOTHING
        RETURNING id INTO hasil;

        -- ON CONFLICT: penyimpan lain baru saja membuatnya lebih dulu.
        IF hasil IS NULL THEN
            SELECT id INTO hasil FROM folders WHERE tenant_id = penyewa AND is_default;
        END IF;
    END IF;

    RETURN hasil;
END
$$;

CREATE FUNCTION isi_folder_bawaan() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.folder_id IS NULL THEN
        NEW.folder_id := folder_bawaan(NEW.tenant_id);
    END IF;

    RETURN NEW;
END
$$;

-- Pekerjaan dan pemicu tinggal di folder PROSESNYA. Proses yang tidak ada
-- (pekerjaan lama dari alat pindahan) jatuh ke folder bawaan.
CREATE FUNCTION isi_folder_dari_proses() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.folder_id IS NULL THEN
        SELECT folder_id INTO NEW.folder_id
          FROM processes
         WHERE tenant_id = NEW.tenant_id AND name = NEW.process_name;

        IF NEW.folder_id IS NULL THEN
            NEW.folder_id := folder_bawaan(NEW.tenant_id);
        END IF;
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER trg_processes_folder BEFORE INSERT ON processes
    FOR EACH ROW EXECUTE FUNCTION isi_folder_bawaan();
CREATE TRIGGER trg_queues_folder BEFORE INSERT ON queues
    FOR EACH ROW EXECUTE FUNCTION isi_folder_bawaan();
CREATE TRIGGER trg_assets_folder BEFORE INSERT ON assets
    FOR EACH ROW EXECUTE FUNCTION isi_folder_bawaan();
CREATE TRIGGER trg_buckets_folder BEFORE INSERT ON buckets
    FOR EACH ROW EXECUTE FUNCTION isi_folder_bawaan();
CREATE TRIGGER trg_jobs_folder BEFORE INSERT ON jobs
    FOR EACH ROW EXECUTE FUNCTION isi_folder_dari_proses();
CREATE TRIGGER trg_triggers_folder BEFORE INSERT ON triggers
    FOR EACH ROW EXECUTE FUNCTION isi_folder_dari_proses();

-- Robot yang mendaftar sendiri lewat denyut, dan pengguna baru, langsung
-- ditugaskan ke folder bawaan. Robot yang tidak ditugaskan ke mana pun
-- tidak akan pernah mengambil pekerjaan, dan gejalanya — robot menyala,
-- pekerjaan menunggu selamanya — tidak mengarahkan siapa pun ke sebabnya.
CREATE FUNCTION tugaskan_ke_folder_bawaan() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'robots' THEN
        INSERT INTO folder_robots (folder_id, robot_id, tenant_id, created_at)
        VALUES (folder_bawaan(NEW.tenant_id), NEW.id, NEW.tenant_id, now())
        ON CONFLICT DO NOTHING;
    ELSE
        INSERT INTO folder_users (folder_id, user_id, tenant_id, created_at)
        VALUES (folder_bawaan(NEW.tenant_id), NEW.id, NEW.tenant_id, now())
        ON CONFLICT DO NOTHING;
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER trg_robots_folder_bawaan AFTER INSERT ON robots
    FOR EACH ROW EXECUTE FUNCTION tugaskan_ke_folder_bawaan();
CREATE TRIGGER trg_users_folder_bawaan AFTER INSERT ON users
    FOR EACH ROW EXECUTE FUNCTION tugaskan_ke_folder_bawaan();

-- ---------------------------------------------------------------------
-- Jejak audit
-- ---------------------------------------------------------------------

CREATE TABLE audit_logs (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    -- Nama, bukan id: jejaknya harus tetap terbaca sesudah penggunanya dihapus.
    username    VARCHAR(80),
    -- Proses, Aset, Folder, Pengguna, ...
    component   VARCHAR(60)   NOT NULL,
    -- Buat, Simpan, Hapus, Jalankan, Masuk, ...
    action      VARCHAR(60)   NOT NULL,
    -- Nama barang yang diubah, kalau diketahui.
    target      VARCHAR(400),
    -- Metode dan jalur permintaannya, untuk yang ingin tahu persisnya.
    detail      VARCHAR(600),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX ix_audit_logs_tenant_id_desc ON audit_logs(tenant_id, id DESC);
