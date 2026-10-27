package plainround.catalogimport.infrastructure.jdbc;

import com.github.sviperll.result4j.Result;
import java.sql.Connection;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import plainround.catalogimport.application.RunLock;

/**
 * A PostgreSQL advisory lock on a connection held for the whole run. The server releases it when
 * that connection ends, so a process killed with {@code kill -9} cannot leave the lock behind, and
 * a held lock means a live process.
 */
@RequiredArgsConstructor
public class AdvisoryRunLock implements RunLock {

  static final long KEY = 7_203_001L;
  private static final String LOCK = "SELECT pg_try_advisory_lock(" + KEY + ")";
  private static final String UNLOCK = "SELECT pg_advisory_unlock(" + KEY + ")";

  private final DataSource dataSource;

  @Override
  public Result<Lease, String> tryAcquire() {
    Connection connection = DataSourceUtils.getConnection(dataSource);
    JdbcTemplate session = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    boolean held = Boolean.TRUE.equals(session.queryForObject(LOCK, Boolean.class));
    return held ? Result.success(() -> release(session, connection)) : refuse(connection);
  }

  private Result<Lease, String> refuse(Connection connection) {
    DataSourceUtils.releaseConnection(connection, dataSource);
    return Result.error("another import holds the lock; wait for it or stop it");
  }

  private void release(JdbcTemplate session, Connection connection) {
    session.queryForObject(UNLOCK, Boolean.class);
    DataSourceUtils.releaseConnection(connection, dataSource);
  }
}
