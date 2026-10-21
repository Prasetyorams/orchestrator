-- Feed Library (V20): padanan feed Libraries di UiPath Orchestrator.
--
-- Library OpenStudio adalah proyek yang workflow-nya dikompilasi menjadi
-- activity: .nupkg bertag "OpenStudio Library" berisi tepat satu
-- lib/net45/<Id>.dll. Proses yang memakainya menyebutnya di "dependencies"
-- project.json; robot mengunduh DLL-nya dari sini sebelum job dijalankan.
--
-- Satu feed untuk seluruh penyewa, sama seperti umpan paket proses: paket
-- tidak tinggal di folder, jadi library yang dipakainya juga tidak.

CREATE TABLE libraries (
    id               UUID PRIMARY KEY,
    tenant_id        UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    -- Id NuGet dari .nuspec, dengan huruf besar-kecil seperti yang diunggah.
    name             VARCHAR(200)  NOT NULL,
    version          VARCHAR(64)   NOT NULL,
    description      VARCHAR(400),
    authors          VARCHAR(400),
    -- Jalur DLL di dalam .nupkg, mis. lib/net45/Tagihan.Umum.dll.
    assembly_file    VARCHAR(255)  NOT NULL,
    -- Dependensi dari .nuspec sebagai JSON {"Id": "versi"} — teks, bukan
    -- JSONB, seperti kolom JSON lain di skema ini (lihat V1, jobs).
    dependencies     TEXT          NOT NULL DEFAULT '{}',
    published_by     VARCHAR(160),
    published_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    size_bytes       BIGINT        NOT NULL,
    -- Berkas .nupkg apa adanya; robot mencocokkan sha256 ini sebelum memakai DLL-nya.
    content          BYTEA         NOT NULL,
    sha256           VARCHAR(64)   NOT NULL,
    source_file_name VARCHAR(255)
);

-- Id NuGet tidak peka huruf besar-kecil: Tagihan.Umum dan tagihan.umum satu library.
CREATE UNIQUE INDEX uq_libraries_tenant_name_version ON libraries (tenant_id, lower(name), version);

-- Dependensi project.json paket proses, dibaca sekali saat terbit.
-- NULL = paket dari sebelum V20; dibaca dari isinya saat pertama dibutuhkan.
ALTER TABLE packages ADD COLUMN dependencies TEXT;

-- Library yang benar-benar diberikan ke sebuah job saat diambil robot:
-- robot hanya boleh mengunduh library job yang sedang dipegangnya, dan
-- versi library yang sedang dipakai tidak boleh dihapus.
CREATE TABLE job_libraries (
    job_id    UUID         NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    tenant_id UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name      VARCHAR(200) NOT NULL,
    version   VARCHAR(64)  NOT NULL,
    sha256    VARCHAR(64)  NOT NULL,
    PRIMARY KEY (job_id, name)
);

CREATE INDEX ix_job_libraries_library ON job_libraries (tenant_id, lower(name), version);
