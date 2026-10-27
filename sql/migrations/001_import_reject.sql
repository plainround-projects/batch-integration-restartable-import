-- Migration 001 (expand only): the record of every row the import refused, added in the lesson's step 3.
--
-- The primary key (source_file, line_number) makes recording idempotent: a chunk that is processed again after a
-- crash writes the same reject rows again and changes nothing. The row is written inside the chunk's transaction, so
-- a chunk that rolls back takes its rejects with it. The file `rejects.txt` is exported from this table at the end
-- of a run, so it is complete after any number of crashes and restarts.

CREATE TABLE IF NOT EXISTS import_reject (
    source_file TEXT          NOT NULL,
    line_number INTEGER       NOT NULL,
    sku         TEXT,
    category    TEXT,
    price       TEXT,
    reason      TEXT          NOT NULL,
    rejected_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (source_file, line_number)
);
COMMENT ON TABLE import_reject IS 'Rows the import skipped, with the line number in the source file and the reason.';
