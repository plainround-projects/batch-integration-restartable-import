-- Outside the numbered run order: forget everything the import wrote and every run it remembers (used by
-- `make reset`, the crash test and the integration tests).
TRUNCATE product, import_reject;
TRUNCATE batch_step_execution_context, batch_job_execution_context, batch_step_execution,
         batch_job_execution_params, batch_job_execution, batch_job_instance;
