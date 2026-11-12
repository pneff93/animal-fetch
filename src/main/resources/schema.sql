-- Schema for animal-fetch. Run by App on every startup; IF NOT EXISTS makes it safe to repeat.

-- One row per downloaded picture; the image itself is stored in the database (BYTEA).
CREATE TABLE IF NOT EXISTS pictures (
    id           BIGSERIAL PRIMARY KEY,
    animal       VARCHAR(10)  NOT NULL,   -- 'cat', 'dog' or 'bear'
    content_type VARCHAR(100) NOT NULL,   -- MIME type from the upstream API, e.g. image/jpeg
    data         BYTEA        NOT NULL,
    source_url   TEXT         NOT NULL,   -- URL the picture was downloaded from
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Supports "latest picture of an animal" (WHERE animal = ? ORDER BY id DESC LIMIT 1).
CREATE INDEX IF NOT EXISTS idx_pictures_animal_latest ON pictures (animal, id DESC);
