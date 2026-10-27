package plainround.catalogimport.interfaces;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import plainround.catalogimport.application.GenerateCatalogFile;
import plainround.catalogimport.application.ImportCatalog;
import plainround.catalogimport.application.ImportReport;
import plainround.catalogimport.application.ImportRequest;
import plainround.catalogimport.infrastructure.jdbc.StepExecutionTable;

/**
 * The command-line program. {@code --command=import|generate|summary|health}. Results go to
 * standard output, the structured log to standard error; the exit code is the report's.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportRunner implements ApplicationRunner, ExitCodeGenerator {

  private final ImportCatalog importCatalog;
  private final GenerateCatalogFile generator;
  private final StepExecutionTable table;
  private final JdbcTemplate jdbc;
  private volatile int exitCode;

  @Override
  public void run(ApplicationArguments args) {
    Map<String, Function<ApplicationArguments, Integer>> commands =
        Map.of(
            "import",
            this::importCommand,
            "generate",
            this::generateCommand,
            "summary",
            this::summaryCommand,
            "health",
            this::healthCommand);
    String name = option(args, "command", "import");
    exitCode = commands.getOrDefault(name, unknown(name)).apply(args);
  }

  @Override
  public int getExitCode() {
    return exitCode;
  }

  private int importCommand(ApplicationArguments args) {
    ImportRequest request =
        new ImportRequest(
            Path.of(option(args, "import.file", "data/products.csv")),
            option(args, "run.id", "default"),
            Path.of(option(args, "import.reject-file", "rejects.txt")));
    ImportReport report = importCatalog.run(request);
    System.out.println(describe(report));
    return report.exitCode();
  }

  private String describe(ImportReport report) {
    return "%s read=%d written=%d skipped=%d commits=%d resumed=%s %s"
        .formatted(
            report.status(),
            report.read(),
            report.written(),
            report.skipped(),
            report.commits(),
            report.resumed(),
            report.message() == null ? "" : report.message())
        .strip();
  }

  private int generateCommand(ApplicationArguments args) {
    int bad =
        generator.write(
            Path.of(option(args, "out", "data/products.csv")),
            Integer.parseInt(option(args, "rows", "200000")),
            Integer.parseInt(option(args, "bad-every", "2000")),
            Long.parseLong(option(args, "seed", "7")));
    System.out.println(
        "wrote " + option(args, "out", "data/products.csv") + " with " + bad + " bad rows");
    return 0;
  }

  private int summaryCommand(ApplicationArguments args) {
    System.out.println(StepExecutionTable.heading());
    table.lines().forEach(System.out::println);
    System.out.printf("products=%d distinct_skus=%d%n", table.products(), table.distinctSkus());
    return 0;
  }

  private int healthCommand(ApplicationArguments args) {
    Long products = jdbc.queryForObject("SELECT count(*) FROM product", Long.class);
    System.out.println("ok database reachable, " + products + " products");
    return 0;
  }

  private Function<ApplicationArguments, Integer> unknown(String name) {
    return args -> {
      System.err.println("unknown command " + name);
      return 2;
    };
  }

  private static String option(ApplicationArguments args, String name, String fallback) {
    return args.containsOption(name) ? args.getOptionValues(name).getFirst() : fallback;
  }
}
