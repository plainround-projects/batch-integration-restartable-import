package plainround.catalogimport.infrastructure.batch;

import org.springframework.batch.item.ItemProcessor;
import plainround.catalogimport.domain.InvalidProductException;
import plainround.catalogimport.domain.ProductRow;
import plainround.catalogimport.domain.ProductRules;

/**
 * Validates and returns the row, or throws the one exception the step is allowed to skip. Writes
 * nothing.
 */
public class ProductItemProcessor implements ItemProcessor<ProductRow, ProductRow> {

  @Override
  public ProductRow process(ProductRow row) {
    return ProductRules.validate(row)
        .orOnErrorThrow(rejection -> new InvalidProductException(row, rejection));
  }
}
