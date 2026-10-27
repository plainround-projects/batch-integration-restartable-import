package plainround.catalogimport.infrastructure.batch;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The job's settings, from the environment or {@code application.yml}, validated at startup.
 *
 * @param chunkSize items read, processed and written in one transaction
 * @param skipLimit skips allowed over the whole step; one more fails it
 * @param retryLimit attempts for a transient write failure
 * @param transientFaultEveryNthChunk 0 for none; otherwise the writer fails the Nth chunk once (the
 *     lesson's step 6)
 */
@Validated
@ConfigurationProperties("import")
public record ImportProperties(
    @DefaultValue("1000") @Min(1) int chunkSize,
    @DefaultValue("500") @Min(0) int skipLimit,
    @DefaultValue("3") @Min(1) int retryLimit,
    @DefaultValue("0") @Min(0) int transientFaultEveryNthChunk) {}
