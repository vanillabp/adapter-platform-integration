package io.vanillabp.integration.test.persistence.parallel;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bson.BsonValue;

import com.mongodb.MongoClientSettings;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;

import io.quarkus.mongodb.runtime.MongoClientCustomizer;
import jakarta.inject.Singleton;

/**
 * Records every command which the application's MongoDB client sends. So a test can claim
 * that a collection was not touched.
 * <p>
 * The driver reports a command before it goes out. That is the number an application pays
 * for. The driver's own topology monitoring does not come through here, because those
 * commands travel on connections of their own. So this counts what the application asked
 * for.
 * <p>
 * The commands are held in a static field. This is the client's customizer, which is built
 * while the application starts. But the test which reads the commands lives outside the
 * application.
 * <p>
 * Quarkus may register the listener a second time, because it is a CDI bean as well. Then
 * the driver reports each command twice. The id of the request tells the two reports
 * apart, so each command is counted once.
 */
@Singleton
public class CountingCommandListener implements MongoClientCustomizer, CommandListener {

  /**
   * One entry per report, as "request id|command name on collection".
   */
  private static final List<String> COMMANDS = new CopyOnWriteArrayList<>();

  @Override
  public MongoClientSettings.Builder customize(
      final MongoClientSettings.Builder builder) {

    return builder.addCommandListener(this);

  }

  @Override
  public void commandStarted(
      final CommandStartedEvent event) {

    COMMANDS.add("%d|%s on %s".formatted(event.getRequestId(), event.getCommandName(), collectionOf(event)));

  }

  /**
   * The collection which a command names. A command document starts with its own name,
   * mapped to the collection it is about. This method reads it there. A command about the
   * database and not about a collection answers an empty name.
   *
   * @param event The command which started
   * @return The collection or an empty string
   */
  private static String collectionOf(
      final CommandStartedEvent event) {

    final var command = event.getCommand();
    final BsonValue target = command.get(command.getFirstKey());
    return (target != null) && target.isString()
        ? target.asString().getValue()
        : "";

  }

  /**
   * The commands sent since the last {@link #forgetWhatWasSent()} which are about the given
   * collection.
   *
   * @param collection The collection to look for
   * @return The commands, empty where the collection was not touched
   */
  public static List<String> commandsOn(
      final String collection) {

    return COMMANDS
        .stream()
        .filter(command -> command.endsWith(" on "
            + collection))
        .distinct()
        .map(command -> command.substring(command.indexOf('|') + 1))
        .toList();

  }

  public static void forgetWhatWasSent() {

    COMMANDS.clear();

  }

}
