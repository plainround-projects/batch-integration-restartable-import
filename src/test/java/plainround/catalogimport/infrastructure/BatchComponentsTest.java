package plainround.catalogimport.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.Chunk;
import org.springframework.dao.TransientDataAccessException;
import plainround.catalogimport.domain.InvalidProductException;
import plainround.catalogimport.domain.ProductRow;
import plainround.catalogimport.infrastructure.batch.ImportProperties;
import plainround.catalogimport.infrastructure.batch.ProductItemProcessor;
import plainround.catalogimport.infrastructure.batch.ProductLineMapper;
import plainround.catalogimport.infrastructure.batch.TransientFaultInjector;

class BatchComponentsTest {

  private final ProductLineMapper mapper = new ProductLineMapper();

  @Test
  void a_line_becomes_a_row_that_keeps_its_line_number() {
    ProductRow row = mapper.mapLine("SKU-002000,Product 2000,weapons,87.12", 2002);
    assertThat(row)
        .isEqualTo(
            new ProductRow(2002, "SKU-002000", "Product 2000", "weapons", new BigDecimal("87.12")));
  }

  @Test
  void a_short_or_garbled_line_becomes_a_row_the_rules_refuse() {
    ProductRow row = mapper.mapLine("SKU-000001,only a name", 5);
    assertThat(row.category()).isEmpty();
    assertThat(row.price()).isNull();
  }

  @Test
  void the_processor_returns_a_valid_row_and_throws_the_one_skippable_exception_for_a_bad_one() {
    ProductItemProcessor processor = new ProductItemProcessor();
    ProductRow good = mapper.mapLine("SKU-000001,n,books,10.00", 2);
    ProductRow bad = mapper.mapLine("SKU-000002,n,books,0", 3);
    assertThat(processor.process(good)).isSameAs(good);
    assertThatThrownBy(() -> processor.process(bad))
        .isInstanceOf(InvalidProductException.class)
        .hasMessage("price must be above zero");
  }

  private final List<Integer> written = new ArrayList<>();

  private Chunk<ProductRow> chunk(int firstLine) {
    return new Chunk<>(new ProductRow(firstLine, "SKU-000001", "n", "books", BigDecimal.ONE));
  }

  @Test
  void the_injector_fails_every_nth_chunk_once_and_lets_the_retry_through() throws Exception {
    TransientFaultInjector injector = new TransientFaultInjector(c -> written.add(c.size()), 2);
    injector.write(chunk(10));
    assertThatThrownBy(() -> injector.write(chunk(20)))
        .isInstanceOf(TransientDataAccessException.class);
    injector.write(chunk(20));
    injector.write(chunk(30));
    assertThat(written).hasSize(3);
    assertThat(injector.injected()).isEqualTo(1);
  }

  @Test
  void the_injector_with_zero_never_fails() throws Exception {
    TransientFaultInjector injector = new TransientFaultInjector(c -> written.add(c.size()), 0);
    injector.write(chunk(1));
    injector.write(chunk(2));
    assertThat(injector.injected()).isZero();
  }

  @Test
  void the_properties_have_the_lessons_defaults_and_reject_nonsense() {
    Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    assertThat(validator.validate(new ImportProperties(1000, 500, 3, 0))).isEmpty();
    assertThat(validator.validate(new ImportProperties(0, -1, 0, -2))).hasSize(4);
  }
}
