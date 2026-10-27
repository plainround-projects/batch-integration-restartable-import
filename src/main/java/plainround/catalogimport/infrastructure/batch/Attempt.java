package plainround.catalogimport.infrastructure.batch;

import com.github.sviperll.result4j.Result;
import java.util.concurrent.Callable;

/** The one place a checked exception from the batch API becomes a {@link Result}. */
final class Attempt {

  private Attempt() {}

  static <T> Result<T, Exception> of(Callable<T> action) {
    try {
      return Result.success(action.call());
    } catch (Exception problem) {
      return Result.error(problem);
    }
  }
}
