package io.vanillabp.bpmsdouble;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * The beans of one hook type a test declared, as the double sees them. The two
 * platforms answer "give me every bean of this type, there may be none" with different
 * types. Spring Boot hands out an {@code ObjectProvider}, CDI an {@code Instance}. Both
 * offer a {@code stream()} of exactly this shape. So each platform module passes a
 * method reference, and the behaviour of the double stays in one place.
 *
 * @param <H> The hook interface
 */
@FunctionalInterface
public interface HookBeans<H> {

  /**
   * Every hook bean of this type a test declared. The double asks for all of them where
   * each one may add something. An empty stream is the normal case: a test which
   * declares no hook gets the double's own behaviour.
   *
   * @return Every bean of the hook type, in the order the platform resolves them
   */
  Stream<H> all();

  /**
   * The hook a test declared, for a question where the double can use only one answer.
   * Several beans are not an error here. The first one wins, in the order the platform
   * gives them.
   *
   * @return The first bean of the hook type, or empty where a test declared none
   */
  default Optional<H> first() {

    return all().findFirst();

  }

}
