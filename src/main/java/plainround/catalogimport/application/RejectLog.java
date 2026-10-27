package plainround.catalogimport.application;

import java.nio.file.Path;

/** The port to the record of refused rows. */
public interface RejectLog {

  /**
   * Writes the refused rows of a source file, one line each, in file order, to {@code target};
   * returns the count.
   */
  int export(String sourceFile, Path target);
}
