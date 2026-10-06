-- =====================================================================
-- Open Assistant masuk lewat dasbor (usulan tim robot, PR orchestrator#4).
--
-- Alurnya OAuth 2.0 untuk aplikasi desktop (RFC 8252) dengan PKCE (RFC 7636):
-- orang yang sudah masuk ke dasbor menyetujui "Sambungkan Open Assistant di
-- PC-X", dasbor memberi KODE sekali pakai lewat tautan openassistant://, dan
-- Open Assistant menukarnya — bersama code_verifier yang hanya ia ketahui —
-- dengan token akses dan refresh token.
--
-- Yang disimpan hanya HASH (SHA-256) kode dan refresh token: keduanya acak
-- 256 bit, jadi hash cepat sudah cukup, dan isi tabel yang bocor tidak bisa
-- dipakai masuk.
-- =====================================================================

CREATE TABLE assistant_codes (
    id              UUID PRIMARY KEY,
    tenant_id       UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id         UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash       VARCHAR(64)   NOT NULL,
    client          VARCHAR(40)   NOT NULL,
    redirect_uri    VARCHAR(200)  NOT NULL,
    code_challenge  VARCHAR(128)  NOT NULL,
    -- Nama komputer yang disebut Open Assistant; hanya untuk ditampilkan.
    machine_name    VARCHAR(160),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ   NOT NULL,
    -- Kode yang dipakai kedua kali bukan salah ketik: ada yang menyadapnya.
    -- Sesi yang sudah terbit dari kode itu ikut dicabut.
    used_at         TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_assistant_codes_hash ON assistant_codes(code_hash);

CREATE TABLE assistant_sessions (
    id                     UUID PRIMARY KEY,
    tenant_id              UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id                UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_id                UUID          REFERENCES assistant_codes(id) ON DELETE SET NULL,
    -- Robot attended milik pengguna di komputer ini (satu per pengguna dan mesin).
    robot_name             VARCHAR(160)  NOT NULL,
    machine_name           VARCHAR(160)  NOT NULL,
    client_version         VARCHAR(40),
    refresh_hash           VARCHAR(64)   NOT NULL,
    -- Refresh token SEBELUM rotasi terakhir. Dipakai lagi berarti tokennya
    -- disalin orang lain: seluruh sesi dicabut.
    previous_refresh_hash  VARCHAR(64),
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_used_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at             TIMESTAMPTZ   NOT NULL,
    revoked_at             TIMESTAMPTZ,
    revoke_reason          VARCHAR(40)
);

CREATE UNIQUE INDEX uq_assistant_sessions_refresh ON assistant_sessions(refresh_hash);
CREATE INDEX ix_assistant_sessions_previous ON assistant_sessions(previous_refresh_hash)
    WHERE previous_refresh_hash IS NOT NULL;
CREATE INDEX ix_assistant_sessions_user ON assistant_sessions(tenant_id, user_id);
CREATE INDEX ix_assistant_sessions_code ON assistant_sessions(code_id) WHERE code_id IS NOT NULL;
