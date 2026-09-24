package io.vanillabp.integration.test.utils.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import liquibase.change.Change;
import liquibase.change.ColumnConfig;
import liquibase.change.core.AddColumnChange;
import liquibase.change.core.CreateIndexChange;
import liquibase.change.core.CreateTableChange;
import liquibase.changelog.ChangeLogParameters;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * The tables, columns and indexes the changelog of <code>io.vanillabp:vanillabp-schema</code>
 * describes, read from the changelog itself. A test which types them out instead notices a table gone missing but never a table newly
 * arrived, and that is the miss which let the payload table of the phase-two outbox travel in this
 * artifact for months while nothing here knew about it.
 *
 * <p>
 * Liquibase parses the master changelog, follows the include of every version and fills the
 * properties in, so the names come out as an application gets them. Hand over the properties an
 * application overrides and the names come out renamed, the same way.
 * </p>
 */
public class ChangelogDescription {

  /**
   * The master changelog of the schema artifact, the file an application includes. A module
   * which reads it needs that artifact and Liquibase on its test classpath; this module brings
   * neither, because every other reader of it would then get Liquibase as well.
   */
  public static final String CHANGELOG = "vanillabp/schema/changelog.xml";

  /**
   * One index of the changelog: the table it sits on, its name and the columns it spans, in order.
   *
   * @param table The table the index sits on
   * @param name The name of the index
   * @param columns The columns it spans, in the order the changelog lists them
   */
  public record Index(String table, String name, List<String> columns) {
  }

  private final Map<String, List<String>> columnsPerTable = new LinkedHashMap<>();

  private final List<Index> indexes = new ArrayList<>();

  private ChangelogDescription() {
  }

  /**
   * Parses the changelog from the classpath.
   *
   * @param changelogProperties The properties an application overrides before including the
   *          changelog, empty for the names the artifact ships with
   * @return What the changelog describes
   * @throws Exception If the changelog cannot be read.
   */
  public static ChangelogDescription of(
      final Map<String, String> changelogProperties) throws Exception {

    final var description = new ChangelogDescription();

    try (var classpath = new ClassLoaderResourceAccessor()) {
      final var parameters = new ChangeLogParameters();
      // a value set here wins over the <property> of the changelog, which is how an application
      // renames a table without forking the file
      changelogProperties.forEach(parameters::set);
      ChangeLogParserFactory
          .getInstance()
          .getParser(CHANGELOG, classpath)
          .parse(CHANGELOG, parameters, classpath)
          .getChangeSets()
          .forEach(
              changeSet -> changeSet
                  .getChanges()
                  .forEach(change -> description.read(changeSet.getId(), change)));
    }

    return description;

  }

  /**
   * Every table of the changelog with its columns.
   *
   * @return Every table the changelog creates, each with its columns in the order they are added
   */
  public Map<String, List<String>> tables() {

    return columnsPerTable;

  }

  /**
   * The tables of the changelog, asked for by name alone.
   *
   * @return The names of the tables the changelog creates
   */
  public Set<String> tableNames() {

    return columnsPerTable.keySet();

  }

  /**
   * The indexes of the changelog, each on the table it belongs to.
   *
   * @return Every index the changelog creates
   */
  public List<Index> indexes() {

    return indexes;

  }

  /**
   * Reads one change. A change this method does not know ends the test instead of being walked
   * past: a dropColumn or a renameColumn nobody taught it about would leave it describing columns
   * which are no longer there, and the comparison against the database would be wrong in both
   * directions.
   */
  private void read(
      final String changeSetId,
      final Change change) {

    if (change instanceof final CreateTableChange createTable) {
      columnsPerTable
          .put(upperCase(createTable.getTableName()), namesOf(createTable.getColumns()));
    } else if (change instanceof final AddColumnChange addColumn) {
      final var table = upperCase(addColumn.getTableName());
      final var columns = columnsPerTable.get(table);
      if (columns == null) {
        throw new IllegalStateException(
            "Changeset '%s' adds columns to '%s', a table no changeset before it creates."
                .formatted(changeSetId, table));
      }
      columns.addAll(namesOf(addColumn.getColumns()));
    } else if (change instanceof final CreateIndexChange createIndex) {
      indexes
          .add(
              new Index(
                  upperCase(createIndex.getTableName()), upperCase(createIndex.getIndexName()), namesOf(
                      createIndex.getColumns())));
    } else {
      throw new IllegalStateException(
          """
              Changeset '%s' uses the change '%s', which %s cannot read. Teach it that change, \
              because a change walked past can add or drop a column without any test of this \
              module noticing."""
              .formatted(changeSetId, change.getClass().getSimpleName(),
                  ChangelogDescription.class.getSimpleName()));
    }

  }

  private static List<String> namesOf(
      final List<? extends ColumnConfig> columns) {

    return columns
        .stream()
        .map(column -> upperCase(column.getName()))
        .collect(Collectors.toCollection(ArrayList::new));

  }

  /**
   * The databases this module is tested against fold an unquoted name to upper case, and the
   * changelog spells every name that way already. Doing it here anyway keeps a lower-case name in
   * a future changeset from turning into a test which fails for the wrong reason.
   */
  private static String upperCase(
      final String name) {

    return name == null ? null : name.toUpperCase();

  }

}
