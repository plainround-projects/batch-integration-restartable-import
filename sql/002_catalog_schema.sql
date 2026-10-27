-- DDL: the target of the import. One row per SKU; the primary key is what makes a repeated write harmless
-- (the writer upserts on it) and the CHECK is the database's last word on the price rule.

CREATE TABLE IF NOT EXISTS product (
    sku      VARCHAR(20)   PRIMARY KEY,
    name     VARCHAR(100)  NOT NULL,
    category VARCHAR(20)   NOT NULL,
    price    NUMERIC(10,2) NOT NULL CHECK (price > 0)
);
COMMENT ON TABLE product IS 'The product catalog. One row per SKU, loaded by the restartable import.';

-- Spring Batch splits the skip counter in three (read, process, write). The lesson's queries and this project's reports
-- want one number, so this view adds it: skip_count is the sum of the three.
CREATE OR REPLACE VIEW step_summary AS
SELECT step_execution_id,
       job_execution_id,
       step_name,
       status,
       read_count,
       write_count,
       read_skip_count + process_skip_count + write_skip_count AS skip_count,
       commit_count,
       rollback_count,
       start_time,
       end_time
FROM batch_step_execution;
