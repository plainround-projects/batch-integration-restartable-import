package plainround.catalogimport.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import plainround.catalogimport.domain.ProductRules;

/**
 * Writes a catalog file with bad rows planted at a fixed spacing, in four flavors (price zero,
 * unknown category, wrong SKU format, negative price), like the lesson's {@code make_products.py}.
 */
public class GenerateCatalogFile {

  private static final List<String> CATEGORIES =
      List.copyOf(ProductRules.categories().stream().sorted().toList());

  /**
   * Writes {@code rows} products; every {@code badEvery}th row is bad (0 for none). Returns the bad
   * row count.
   */
  public int write(Path target, int rows, int badEvery, long seed) {
    Random random = new Random(seed);
    try (Stream<String> lines =
        Stream.concat(Stream.of("sku,name,category,price"), body(rows, badEvery, random))) {
      Files.write(target, (Iterable<String>) lines::iterator);
    } catch (IOException problem) {
      throw new UncheckedIOException(problem);
    }
    return badEvery == 0 ? 0 : rows / badEvery;
  }

  private Stream<String> body(int rows, int badEvery, Random random) {
    return IntStream.rangeClosed(1, rows).mapToObj(row -> line(row, badEvery, random));
  }

  private String line(int row, int badEvery, Random random) {
    String category = CATEGORIES.get(random.nextInt(CATEGORIES.size()));
    BigDecimal price =
        BigDecimal.valueOf(1 + random.nextDouble() * 499).setScale(2, RoundingMode.HALF_UP);
    boolean bad = badEvery > 0 && row % badEvery == 0;
    return bad
        ? badLine(row, (row / badEvery) % 4, category, price)
        : format("SKU-%06d".formatted(row), row, category, price.toPlainString());
  }

  private String badLine(int row, int flavor, String category, BigDecimal price) {
    return switch (flavor) {
      case 0 -> format("SKU-%06d".formatted(row), row, category, "0");
      case 1 -> format("SKU-%06d".formatted(row), row, "weapons", price.toPlainString());
      case 2 -> format("BAD" + row, row, category, price.toPlainString());
      default -> format("SKU-%06d".formatted(row), row, category, "-5");
    };
  }

  private String format(String sku, int row, String category, String price) {
    return "%s,Product %d,%s,%s".formatted(sku, row, category, price);
  }
}
