package plainround.catalogimport.infrastructure.jdbc;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

/** The job repository as the lesson reads it: one line per step execution with its counters. */
@RequiredArgsConstructor
public class StepExecutionTable {

  private final JdbcTemplate jdbc;

  /** One formatted line per step execution, oldest first. */
  public List<String> lines() {
    return jdbc.query(
        """
        SELECT step_name, status, read_count, write_count, skip_count, commit_count
        FROM step_summary ORDER BY step_execution_id
        """,
        (rs, i) ->
            "%-10s | %-9s | %10d | %11d | %10d | %12d"
                .formatted(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getLong(3),
                    rs.getLong(4),
                    rs.getLong(5),
                    rs.getLong(6)));
  }

  /** The heading that matches {@link #lines()}. */
  public static String heading() {
    return "step_name  | status    | read_count | write_count | skip_count | commit_count";
  }

  /** The number of rows in the product table, for the end-state check. */
  public long products() {
    return jdbc.queryForObject("SELECT count(*) FROM product", Long.class);
  }

  /** The number of distinct SKUs, which must equal {@link #products()}. */
  public long distinctSkus() {
    return jdbc.queryForObject("SELECT count(DISTINCT sku) FROM product", Long.class);
  }
}
