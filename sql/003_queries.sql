-- The queries the lesson runs, each with a one-line comment on what it shows.

-- The end state: 199,900 rows, one per valid SKU.
SELECT count(*) AS products, count(DISTINCT sku) AS distinct_skus FROM product;

-- The job repository after a clean run: one COMPLETED step with the counts (200000 read, 199900 written, 100 skipped).
-- step_summary is a view over batch_step_execution that adds up Spring Batch's three skip counters (see 002).
SELECT step_name, status, read_count, write_count, skip_count, commit_count
FROM step_summary
ORDER BY step_execution_id;

-- After a kill -9 and a restart: the first execution stays STARTED (or FAILED once recovered), with the counts of
-- its last commit; the second reads only what was left. The two add up to the file.
SELECT s.job_execution_id, s.step_name, s.status, s.read_count, s.write_count, s.skip_count, s.commit_count
FROM step_summary s
ORDER BY s.step_execution_id;

-- Where the reader will resume: the saved read count of the latest step execution of each job instance.
SELECT s.step_execution_id, s.status, c.short_context
FROM batch_step_execution s
JOIN batch_step_execution_context c USING (step_execution_id)
ORDER BY s.step_execution_id DESC
LIMIT 5;

-- The skipped rows with their reasons, in file order.
SELECT line_number, sku, category, price, reason FROM import_reject ORDER BY line_number LIMIT 10;

-- Reject reasons, counted: the four flavors the sample file plants, 25 each.
SELECT reason, count(*) FROM import_reject GROUP BY reason ORDER BY reason;

-- Products by category.
SELECT category, count(*) AS products FROM product GROUP BY category ORDER BY category;
