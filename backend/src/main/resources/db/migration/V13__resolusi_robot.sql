-- =====================================================================
-- Resolusi layar robot unattended, per robot — seperti Resolution Width,
-- Height, dan Depth di UiPath. Usulan tim robot (PR #6,
-- USULAN-RESOLUSI-ROBOT-UNATTENDED.md).
--
-- Robot Agent membuat sesi Windows robot lewat RDP loopback dengan ukuran
-- ini; 0 berarti bawaan agent (1024x768, kedalaman bawaan klien RDP). Lebar
-- dan tinggi selalu berpasangan: dua-duanya 0, atau dua-duanya 200..8192.
-- Robot yang sesinya bukan buatan agent (sandi disimpan di mesin robot, sesi
-- konsol) memakai resolusi layar mesin; agent mencatatnya di log job.
--
-- Agent membacanya dari robots[] di jawaban POST /api/agent/login. Mengubahnya
-- di dasbor menaikkan settings_version mesinnya, supaya agent masuk ulang.
-- =====================================================================

ALTER TABLE robots
    ADD COLUMN resolution_width  INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN resolution_height INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN resolution_depth  INTEGER NOT NULL DEFAULT 0;

ALTER TABLE robots ADD CONSTRAINT ck_robots_resolution CHECK (
    ((resolution_width = 0 AND resolution_height = 0)
      OR (resolution_width BETWEEN 200 AND 8192 AND resolution_height BETWEEN 200 AND 8192))
    AND resolution_depth IN (0, 15, 16, 24, 32));
