package io.vanillabp.integration.test.outbox.parallel;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.DocumentReference;

import lombok.Getter;
import lombok.Setter;

/**
 * What both aggregates of the parallel multi-instance test hold. Besides plain values, these
 * are the three ways a MongoDB document holds other objects: references to documents of
 * another collection, eager and lazy, and an object mapped as a sub-document.
 */
@Getter
@Setter
public abstract class ParallelAggregateBase {

  @Id
  private String id;

  private String content;

  /**
   * One record per iteration. The aggregate stores only the references.
   */
  @DBRef
  private List<IterationRecord> iterations = new ArrayList<>();

  @DBRef(lazy = true)
  private LazyRecord lazilyReferenced;

  @DocumentReference
  private IterationRecord documentReferenced;

  private Details details;

}
