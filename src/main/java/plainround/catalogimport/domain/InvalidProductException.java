package plainround.catalogimport.domain;

import lombok.Getter;

/**
 * Thrown for a row that can never succeed. The step may skip this exception and no other: an
 * unexpected error must still stop the job.
 */
@Getter
public class InvalidProductException extends RuntimeException {

  private final transient ProductRow row;

  public InvalidProductException(ProductRow row, Rejection rejection) {
    super(rejection.reason());
    this.row = row;
  }
}
