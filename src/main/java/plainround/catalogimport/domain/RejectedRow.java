package plainround.catalogimport.domain;

/** A row the import refused, as it is recorded and as it reads in the reject file. */
public record RejectedRow(
    int lineNumber, String sku, String category, String price, String reason) {

  /** {@code line 2002: SKU-002000,weapons,87.12 -> unknown category weapons} */
  public String asLine() {
    return "line %d: %s,%s,%s -> %s".formatted(lineNumber, sku, category, price, reason);
  }

  /** Builds the record from the row that failed validation. */
  public static RejectedRow of(ProductRow row, String reason) {
    return new RejectedRow(
        row.lineNumber(),
        row.sku(),
        row.category(),
        row.price() == null ? "" : row.price().toPlainString(),
        reason);
  }
}
