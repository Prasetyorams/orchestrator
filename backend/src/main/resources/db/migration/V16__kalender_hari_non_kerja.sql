-- V16: kalender hari non-kerja, seperti Non-Working Days di UiPath Orchestrator.
--
-- Sebuah kalender adalah daftar tanggal (libur nasional, cuti bersama, tutup
-- buku, ...). Pemicu yang memakai kalender TIDAK berjalan pada tanggal-tanggal
-- itu: penjadwal melompatinya, dan tanggalnya dinilai menurut zona waktu
-- pemicu, bukan waktu server. Kalender milik penyewa, dipakai bersama oleh
-- pemicu di folder mana pun.

CREATE TABLE calendars (
    id           UUID PRIMARY KEY,
    tenant_id    UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name         VARCHAR(120)  NOT NULL,
    description  VARCHAR(400),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Nama unik per penyewa tanpa membedakan huruf besar: "Libur Nasional" dan
-- "libur nasional" di daftar pilihan pemicu tidak bisa dibedakan orang.
CREATE UNIQUE INDEX uq_calendars_tenant_name ON calendars(tenant_id, lower(name));

CREATE TABLE calendar_dates (
    calendar_id  UUID          NOT NULL REFERENCES calendars(id) ON DELETE CASCADE,
    day          DATE          NOT NULL,
    -- Mis. "Hari Raya Idulfitri"; hanya untuk dibaca orang.
    note         VARCHAR(200),
    PRIMARY KEY (calendar_id, day)
);

-- Tanpa ON DELETE: kalender yang masih dipakai pemicu tidak boleh hilang diam-
-- diam (pemicunya akan mulai berjalan di hari libur). Layanan menolaknya 409
-- dengan menyebut pemicunya; foreign key ini jaring pengamannya.
ALTER TABLE triggers ADD COLUMN calendar_id UUID REFERENCES calendars(id);

CREATE INDEX ix_triggers_calendar ON triggers(calendar_id) WHERE calendar_id IS NOT NULL;
