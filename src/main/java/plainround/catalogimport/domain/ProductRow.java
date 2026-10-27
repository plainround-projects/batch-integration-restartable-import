package plainround.catalogimport.domain;

import java.math.BigDecimal;

/**
 * One data line of the catalog file. The line number travels with the row so that a reject record
 * can say where the problem is. {@code price} is null when the field was empty or not a number.
 */
public record ProductRow(
    int lineNumber, String sku, String name, String category, BigDecimal price) {}
