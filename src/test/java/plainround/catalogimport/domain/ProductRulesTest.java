package plainround.catalogimport.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;

import java.math.BigDecimal;
import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

class ProductRulesTest {

  private String reasonOf(ProductRow row) {
    return ProductRules.validate(row).map(accepted -> "valid").recoverError(Rejection::reason);
  }

  private ProductRow valid() {
    return Instancio.of(ProductRow.class)
        .set(field(ProductRow::sku), "SKU-000123")
        .set(field(ProductRow::category), "books")
        .set(field(ProductRow::price), new BigDecimal("12.50"))
        .create();
  }

  @Test
  void a_valid_row_comes_back_unchanged() {
    ProductRow row = valid();
    assertThat(ProductRules.validate(row).discardError()).contains(row);
  }

  @ParameterizedTest
  @CsvSource({
    "0,price must be above zero",
    "-5,price must be above zero",
    "0.00,price must be above zero"
  })
  void a_price_that_is_not_above_zero_is_refused(String price, String reason) {
    ProductRow row = withPrice(new BigDecimal(price));
    assertThat(reasonOf(row)).isEqualTo(reason);
  }

  private ProductRow withPrice(BigDecimal price) {
    ProductRow base = valid();
    return new ProductRow(base.lineNumber(), base.sku(), base.name(), base.category(), price);
  }

  @Test
  void a_missing_price_is_refused_like_a_zero_price() {
    assertThat(reasonOf(withPrice(null))).isEqualTo("price must be above zero");
  }

  @Test
  void an_unknown_category_is_refused_and_named() {
    ProductRow base = valid();
    ProductRow row =
        new ProductRow(base.lineNumber(), base.sku(), base.name(), "weapons", base.price());
    assertThat(reasonOf(row)).isEqualTo("unknown category weapons");
  }

  @ParameterizedTest
  @CsvSource({"BAD4000", "SKU-12345", "sku-123456", "SKU-1234567", "SKU-12A456"})
  void a_sku_in_the_wrong_format_is_refused(String sku) {
    ProductRow base = valid();
    ProductRow row =
        new ProductRow(base.lineNumber(), sku, base.name(), base.category(), base.price());
    assertThat(reasonOf(row)).isEqualTo("bad SKU format");
  }

  @Test
  void a_row_with_several_problems_reports_the_first_rule_in_the_lessons_order() {
    ProductRow row = new ProductRow(2, "BAD1", "n", "weapons", BigDecimal.ZERO);
    assertThat(reasonOf(row)).isEqualTo("price must be above zero");
  }

  @ParameterizedTest
  @NullAndEmptySource
  void a_missing_sku_is_refused(String sku) {
    ProductRow base = valid();
    ProductRow row =
        new ProductRow(base.lineNumber(), sku, base.name(), base.category(), base.price());
    assertThat(ProductRules.validate(row).isError()).isTrue();
  }

  @ParameterizedTest
  @CsvSource({"87.12,87.12", "64.5,64.5", "0,0", "-5,-5", "' 3.10 ',3.10"})
  void a_price_field_is_parsed_exactly(String field, String expected) {
    assertThat(ProductRules.parsePrice(field)).isEqualByComparingTo(new BigDecimal(expected));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @CsvSource({"abc", "'1,5'", "1e3", "--2"})
  void anything_else_in_the_price_field_is_no_price(String field) {
    assertThat(ProductRules.parsePrice(field)).isNull();
  }

  @Test
  void the_categories_are_the_lessons_five() {
    assertThat(ProductRules.categories())
        .containsExactlyInAnyOrder("books", "toys", "garden", "kitchen", "sport");
  }
}
