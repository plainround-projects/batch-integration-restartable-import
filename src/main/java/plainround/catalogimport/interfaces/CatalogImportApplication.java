package plainround.catalogimport.interfaces;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point. The job does not start by itself; {@link ImportRunner} runs the command named on the
 * command line.
 */
@SpringBootApplication(scanBasePackages = "plainround.catalogimport")
public class CatalogImportApplication {

  public static void main(String[] args) {
    System.exit(
        SpringApplication.exit(SpringApplication.run(CatalogImportApplication.class, args)));
  }
}
