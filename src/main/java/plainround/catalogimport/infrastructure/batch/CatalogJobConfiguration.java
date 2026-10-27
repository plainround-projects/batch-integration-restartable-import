package plainround.catalogimport.infrastructure.batch;

import javax.sql.DataSource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import plainround.catalogimport.domain.InvalidProductException;
import plainround.catalogimport.domain.ProductRow;
import plainround.catalogimport.infrastructure.jdbc.JdbcRejectLog;

/**
 * The job: one chunk-oriented step. Reader, processor, writer; skip only {@link
 * InvalidProductException} up to the limit; retry only transient database errors; write by upsert
 * so that a chunk written twice leaves one row.
 */
@Configuration
@EnableConfigurationProperties(ImportProperties.class)
public class CatalogJobConfiguration {

  public static final String JOB_NAME = "catalogImport";
  public static final String STEP_NAME = "importStep";

  private static final String UPSERT =
      """
      INSERT INTO product (sku, name, category, price) VALUES (:sku, :name, :category, :price)
      ON CONFLICT (sku) DO UPDATE SET name = EXCLUDED.name, category = EXCLUDED.category, price = EXCLUDED.price
      """;

  @Bean
  Job catalogImport(JobRepository repository, Step importStep) {
    return new JobBuilder(JOB_NAME, repository)
        .start(importStep)
        .listener(new GracefulShutdown())
        .build();
  }

  @Bean
  Step importStep(
      JobRepository repository,
      PlatformTransactionManager transactions,
      ImportProperties properties,
      FlatFileItemReader<ProductRow> reader,
      ItemWriter<ProductRow> writer,
      RejectSkipListener skipListener) {
    return new StepBuilder(STEP_NAME, repository)
        .<ProductRow, ProductRow>chunk(properties.chunkSize(), transactions)
        .reader(reader)
        .processor(new ProductItemProcessor())
        .writer(writer)
        .faultTolerant()
        .skip(InvalidProductException.class)
        .skipLimit(properties.skipLimit())
        .retry(TransientDataAccessException.class)
        .retryLimit(properties.retryLimit())
        .listener(skipListener)
        .build();
  }

  @Bean
  @StepScope
  FlatFileItemReader<ProductRow> reader(@Value("#{jobParameters['file']}") String file) {
    return new FlatFileItemReaderBuilder<ProductRow>()
        .name("productReader")
        .resource(new FileSystemResource(file))
        .linesToSkip(1)
        .lineMapper(new ProductLineMapper())
        .build();
  }

  @Bean
  JdbcBatchItemWriter<ProductRow> upsertWriter(DataSource dataSource) {
    return new JdbcBatchItemWriterBuilder<ProductRow>()
        .dataSource(dataSource)
        .sql(UPSERT)
        .itemSqlParameterSourceProvider(CatalogJobConfiguration::parameters)
        .assertUpdates(false)
        .build();
  }

  @Bean
  ItemWriter<ProductRow> writer(
      JdbcBatchItemWriter<ProductRow> upsertWriter, ImportProperties properties) {
    return properties.transientFaultEveryNthChunk() > 0
        ? new TransientFaultInjector(upsertWriter, properties.transientFaultEveryNthChunk())
        : upsertWriter;
  }

  @Bean
  JdbcRejectLog rejectLog(JdbcTemplate jdbc) {
    return new JdbcRejectLog(jdbc);
  }

  @Bean
  @StepScope
  RejectSkipListener skipListener(
      @Value("#{jobParameters['file']}") String file, JdbcRejectLog rejects) {
    return new RejectSkipListener(file, rejects);
  }

  private static MapSqlParameterSource parameters(ProductRow row) {
    return new MapSqlParameterSource()
        .addValue("sku", row.sku())
        .addValue("name", row.name())
        .addValue("category", row.category())
        .addValue("price", row.price());
  }
}
