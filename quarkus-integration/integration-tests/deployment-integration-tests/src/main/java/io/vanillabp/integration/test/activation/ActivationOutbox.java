package io.vanillabp.integration.test.activation;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A store of the application's own which never dispatches, so every planned call keeps
 * waiting and its key keeps deduplicating. That is exactly the window multi-instance
 * siblings used to collide in, held open for the length of a test.
 */
@ApplicationScoped
public class ActivationOutbox implements PhaseTwoOutbox {

  private final List<PhaseTwoCall> planned = new CopyOnWriteArrayList<>();

  /**
   * @return The idempotency keys of everything planned so far, in order
   */
  public List<String> plannedKeys() {

    return planned
        .stream()
        .map(call -> call.idempotencyKey().orElse("<none>"))
        .toList();

  }

  public void clear() {

    planned.clear();

  }

  @Override
  public boolean schedule(
      final PhaseTwoCall call) {

    final var key = call.idempotencyKey().orElse(null);
    synchronized (planned) {
      if ((key != null) && planned
          .stream()
          .anyMatch(entry -> key.equals(entry.idempotencyKey().orElse(null)))) {
        return false;
      }
      planned.add(call);
    }
    return true;

  }

}
