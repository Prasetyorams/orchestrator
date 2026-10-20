-- Batas waktu sesi menganggur untuk dasbor (V18).
--
-- Token pengguna adalah JWT tanpa status: berlaku sampai umurnya habis (8 jam
-- bawaan), apa pun yang terjadi di peramban. Menghapusnya dari peramban tidak
-- menghentikan salinannya. Supaya batas waktu menganggur DITEGAKKAN server,
-- setiap masuk lewat dasbor membuat satu baris di dashboard_sessions dan
-- tokennya menyebut baris itu; setiap permintaan memeriksa barisnya.
--
-- Hanya sesi DASBOR (peramban). Studio, Open Assistant v1, robot, dan alat
-- lain yang masuk lewat POST /api/auth/login tanpa client=dashboard tetap
-- memakai token lama — mereka tidak punya orang yang bisa "menganggur", dan
-- memutus mereka berarti memutus robot yang sedang menunggu job.

-- Kebijakan per penyewa. Satu baris per penyewa; yang belum punya memakai
-- nilai bawaan yang sama dengan DEFAULT di bawah.
CREATE TABLE session_settings (
    tenant_id                      UUID         PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    idle_timeout_enabled           BOOLEAN      NOT NULL DEFAULT true,
    idle_timeout_minutes           INTEGER      NOT NULL DEFAULT 30,
    -- 0 = tanpa peringatan sebelum keluar.
    warning_before_logout_minutes  INTEGER      NOT NULL DEFAULT 2,
    updated_at                     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by                     VARCHAR(160),
    CONSTRAINT ck_session_settings_idle CHECK (idle_timeout_minutes BETWEEN 1 AND 1440),
    CONSTRAINT ck_session_settings_warning
        CHECK (warning_before_logout_minutes >= 0 AND warning_before_logout_minutes < idle_timeout_minutes)
);

INSERT INTO session_settings (tenant_id) SELECT id FROM tenants;

-- Satu baris per masuk lewat dasbor. Tidak menyimpan token atau sandi apa
-- pun: tokennya hanya menyebut id baris ini.
CREATE TABLE dashboard_sessions (
    id                UUID          PRIMARY KEY,
    tenant_id         UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id           UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    username          VARCHAR(160)  NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- Aktivitas ORANG terakhir yang dilaporkan dasbor (POST /api/auth/session/activity),
    -- bukan permintaan terakhir: penarikan data berkala tidak memperpanjang sesi.
    last_activity_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- Umur tokennya — batas mutlak, terpisah dari batas menganggur.
    expires_at        TIMESTAMPTZ   NOT NULL,
    revoked_at        TIMESTAMPTZ,
    -- logout, idle_timeout, password_changed, password_reset, user_deactivated, user_deleted
    revoke_reason     VARCHAR(40),
    user_agent        VARCHAR(300)
);

CREATE INDEX ix_dashboard_sessions_user ON dashboard_sessions(tenant_id, user_id) WHERE revoked_at IS NULL;
