package plainround.catalogimport.infrastructure.jdbc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import plainround.catalogimport.application.RejectLog;
import plainround.catalogimport.domain.RejectedRow;

/**
 * The record of refused rows, in the {@code import_reject} table.
 *
 * <p>{@link #record} runs inside the chunk's transaction and is an upsert on (file, line), so a
 * chunk that is processed again after a crash or a rollback leaves exactly one record per refused
 * line.
 */
@RequiredArgsConstructor
public class JdbcRejectLog implements RejectLog {

  private static final String UPSERT =
      """
      INSERT INTO import_reject (source_file, line_number, sku, category, price, reason)
      VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT (source_file, line_number) DO UPDATE
      SET sku = EXCLUDED.sku, category = EXCLUDED.category, price = EXCLUDED.price, reason = EXCLUDED.reason
      """;

  private final JdbcTemplate jdbc;

  /** Records one refused row of {@code sourceFile}. */
  public void record(String sourceFile, RejectedRow row) {
    jdbc.update(
        UPSERT, sourceFile, row.lineNumber(), row.sku(), row.category(), row.price(), row.reason());
  }

  @Override
  public int export(String sourceFile, Path target) {
    List<String> lines = lines(sourceFile);
    write(target, lines);
    return lines.size();
  }

  private List<String> lines(String sourceFile) {
    return jdbc.query(
        "SELECT line_number, sku, category, price, reason FROM import_reject WHERE source_file = ? ORDER BY line_number",
        (rs, i) ->
            new RejectedRow(
                    rs.getInt("line_number"),
                    rs.getString("sku"),
                    rs.getString("category"),
                    rs.getString("price"),
                    rs.getString("reason"))
                .asLine(),
        sourceFile);
  }

  private void write(Path target, List<String> lines) {
    try {
      Files.write(target, lines);
    } catch (IOException problem) {
      throw new UncheckedIOException(problem);
    }
  }
}
