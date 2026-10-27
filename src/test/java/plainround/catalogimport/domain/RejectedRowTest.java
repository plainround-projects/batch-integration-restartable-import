package plainround.catalogimport.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RejectedRowTest {

  @Test
  void a_reject_reads_like_the_lessons_reject_file() {
    ProductRow row =
        new ProductRow(2002, "SKU-002000", "Product 2000", "weapons", new BigDecimal("87.12"));
    assertThat(RejectedRow.of(row, "unknown category weapons").asLine())
        .isEqualTo("line 2002: SKU-002000,weapons,87.12 -> unknown category weapons");
  }

  @Test
  void a_row_without_a_price_shows_an_empty_price() {
    ProductRow row = new ProductRow(7, "SKU-000001", "n", "books", null);
    assertThat(RejectedRow.of(row, "price must be above zero").asLine())
        .isEqualTo("line 7: SKU-000001,books, -> price must be above zero");
  }

  @Test
  void the_exception_carries_the_row_and_the_reason() {
    ProductRow row = new ProductRow(9, "BAD9", "n", "books", BigDecimal.ONE);
    InvalidProductException error =
        new InvalidProductException(row, new Rejection("bad SKU format"));
    assertThat(error).hasMessage("bad SKU format");
    assertThat(error.getRow()).isEqualTo(row);
  }
}
