package plainround.catalogimport.domain;

import com.github.sviperll.result4j.Result;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The validation rules of the lesson's step 1: price above zero, a known category, a SKU in the
 * right format.
 */
public final class ProductRules {

  private static final Set<String> CATEGORIES =
      Set.of("books", "toys", "garden", "kitchen", "sport");
  private static final Pattern SKU_FORMAT = Pattern.compile("SKU-\\d{6}");

  private ProductRules() {}

  /** The row itself when every rule holds, otherwise the first rule's {@link Rejection}. */
  public static Result<ProductRow, Rejection> validate(ProductRow row) {
    return Stream.<Function<ProductRow, Optional<String>>>of(
            ProductRules::priceProblem, ProductRules::categoryProblem, ProductRules::skuProblem)
        .flatMap(rule -> rule.apply(row).stream())
        .findFirst()
        .<Result<ProductRow, Rejection>>map(reason -> Result.error(new Rejection(reason)))
        .orElseGet(() -> Result.success(row));
  }

  private static Optional<String> priceProblem(ProductRow row) {
    return Optional.of(row)
        .filter(candidate -> candidate.price() == null || candidate.price().signum() <= 0)
        .map(candidate -> "price must be above zero");
  }

  private static Optional<String> categoryProblem(ProductRow row) {
    return Optional.of(row)
        .filter(candidate -> !CATEGORIES.contains(candidate.category()))
        .map(candidate -> "unknown category " + candidate.category());
  }

  private static Optional<String> skuProblem(ProductRow row) {
    return Optional.of(row)
        .filter(
            candidate -> candidate.sku() == null || !SKU_FORMAT.matcher(candidate.sku()).matches())
        .map(candidate -> "bad SKU format");
  }

  /** The known categories, for the file generator and the documentation. */
  public static Set<String> categories() {
    return CATEGORIES;
  }

  /**
   * Parses a price field; null when it is empty or not a number, which {@link #validate} then
   * refuses.
   */
  public static BigDecimal parsePrice(String field) {
    return Optional.ofNullable(field)
        .map(String::strip)
        .filter(text -> !text.isEmpty())
        .flatMap(ProductRules::toDecimal)
        .orElse(null);
  }

  private static Optional<BigDecimal> toDecimal(String text) {
    return Optional.of(text).filter(ProductRules::looksNumeric).map(BigDecimal::new);
  }

  private static boolean looksNumeric(String text) {
    return text.matches("-?\\d+(\\.\\d+)?");
  }
}
