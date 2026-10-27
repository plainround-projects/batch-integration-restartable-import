package plainround.catalogimport.infrastructure.batch;

import java.util.Arrays;
import java.util.List;
import org.springframework.batch.item.file.LineMapper;
import plainround.catalogimport.domain.ProductRow;
import plainround.catalogimport.domain.ProductRules;

/**
 * Maps a CSV line to a row that keeps its line number. A short or garbled line becomes a row the
 * rules refuse.
 */
public class ProductLineMapper implements LineMapper<ProductRow> {

  @Override
  public ProductRow mapLine(String line, int lineNumber) {
    List<String> fields = Arrays.asList(line.split(",", -1));
    return new ProductRow(
        lineNumber,
        field(fields, 0),
        field(fields, 1),
        field(fields, 2),
        ProductRules.parsePrice(field(fields, 3)));
  }

  private String field(List<String> fields, int index) {
    return index < fields.size() ? fields.get(index).strip() : "";
  }
}
