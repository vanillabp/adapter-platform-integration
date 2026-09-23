package io.vanillabp.integration.adapter.migration.handler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import io.vanillabp.integration.adapter.spi.workflowtask.MultiInstanceValue;
import io.vanillabp.integration.extension.spi.handler.HandlerContext;
import io.vanillabp.integration.extension.spi.handler.HandlerMultiInstance;

/**
 * Turns what a caller has into the {@link HandlerContext} the parameter binders read.
 * The three callers bring three different things - a task invocation of an adapter, the
 * notification about a workflow the BPMS started, an event of an extension - and the
 * binders are the same for all of them.
 */
public final class HandlerContexts {

  private HandlerContexts() {
  }

  /**
   * The full context, whose multi-instance scopes are asked for only where one is needed.
   * <p>
   * Both suppliers are called when a binder reads them, not now: looking up a process
   * variable costs the adapter a question, and a handler which binds none should cost none.
   *
   * @param workflowAggregate The aggregate the handler works on
   * @param payload What the binders which are not the core's read - the adapter's
   *          invocation context, or the extension's own event object
   * @param variables Looks up a process variable by name
   * @param multiInstances Supplies the multi-instance scopes, asked only where a
   *          parameter needs them
   * @return The context
   */
  public static HandlerContext of(
      final Object workflowAggregate,
      final Object payload,
      final Function<String, Object> variables,
      final Supplier<Map<String, HandlerMultiInstance>> multiInstances) {

    return new HandlerContext() {

      @Override
      public Object getWorkflowAggregate() {

        return workflowAggregate;

      }

      @Override
      public Object getPayload() {

        return payload;

      }

      @Override
      public Object getVariable(
          final String name) {

        return variables.apply(name);

      }

      @Override
      public Map<String, HandlerMultiInstance> getMultiInstances() {

        return multiInstances.get();

      }

    };

  }

  /**
   * The context for a caller which has its variables in hand and no iteration to speak of.
   * <p>
   * A handler binding a multi-instance parameter gets an empty scope here, which is the
   * honest answer: the caller is not inside an iteration.
   *
   * @param workflowAggregate The aggregate the handler works on
   * @param payload What the binders which are not the core's read
   * @param variables The process variables, may be <code>null</code>
   * @return A context without a multi-instance scope
   */
  public static HandlerContext of(
      final Object workflowAggregate,
      final Object payload,
      final Map<String, Object> variables) {

    return of(
        workflowAggregate,
        payload,
        name -> variables == null
            ? null
            : variables.get(name),
        Map::of);

  }

  /**
   * Adapts the multi-instance values an adapter supplies, keeping their outermost-first
   * order.
   *
   * @param multiInstances What the adapter supplied
   * @return The same scopes in the shape the binders read
   */
  public static Map<String, HandlerMultiInstance> adapt(
      final Map<String, MultiInstanceValue> multiInstances) {

    if ((multiInstances == null) || multiInstances.isEmpty()) {
      return Map.of();
    }
    final var adapted = new LinkedHashMap<String, HandlerMultiInstance>();
    multiInstances
        .forEach((
            name,
            value) -> adapted.put(name, new HandlerMultiInstance(value.element(), value.index(), value.total())));
    return adapted;

  }

}
