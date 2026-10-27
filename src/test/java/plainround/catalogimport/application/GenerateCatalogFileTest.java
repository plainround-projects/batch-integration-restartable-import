package plainround.catalogimport.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import plainround.catalogimport.domain.ProductRules;
import plainround.catalogimport.infrastructure.batch.ProductLineMapper;

class GenerateCatalogFileTest {

  @TempDir Path folder;

  private List<String> generate(int rows, int badEvery) throws IOException {
    Path file = folder.resolve("products.csv");
    new GenerateCatalogFile().write(file, rows, badEvery, 7);
    return Files.readAllLines(file);
  }

  @Test
  void the_file_has_a_header_and_one_line_per_row() throws IOException {
    List<String> lines = generate(5000, 2000);
    assertThat(lines).hasSize(5001);
    assertThat(lines.getFirst()).isEqualTo("sku,name,category,price");
  }

  @Test
  void bad_rows_are_planted_at_the_spacing_in_the_lessons_four_flavors() throws IOException {
    List<String> lines = generate(8000, 2000);
    assertThat(lines.get(2000)).startsWith("SKU-002000,Product 2000,weapons,");
    assertThat(lines.get(4000)).startsWith("BAD4000,");
    assertThat(lines.get(6000)).endsWith(",-5");
    assertThat(lines.get(8000)).endsWith(",0");
  }

  @Test
  void exactly_the_planted_rows_fail_validation() throws IOException {
    ProductLineMapper mapper = new ProductLineMapper();
    List<String> lines = generate(8000, 2000);
    long bad =
        java.util.stream.IntStream.range(1, lines.size())
            .mapToObj(i -> mapper.mapLine(lines.get(i), i + 1))
            .filter(row -> ProductRules.validate(row).isError())
            .count();
    assertThat(bad).isEqualTo(4);
  }

  @Test
  void the_same_seed_gives_the_same_file_and_no_bad_rows_when_asked_for_none() throws IOException {
    assertThat(generate(300, 0)).isEqualTo(generate(300, 0));
    assertThat(new GenerateCatalogFile().write(folder.resolve("clean.csv"), 300, 0, 1)).isZero();
  }

  @Test
  void the_return_value_is_the_number_of_bad_rows() {
    assertThat(new GenerateCatalogFile().write(folder.resolve("x.csv"), 200_000, 2000, 7))
        .isEqualTo(100);
  }
}
