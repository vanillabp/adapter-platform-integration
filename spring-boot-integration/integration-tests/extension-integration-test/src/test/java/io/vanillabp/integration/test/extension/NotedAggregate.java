package io.vanillabp.integration.test.extension;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The workflow aggregate of this scenario. A handler method of the extension writes its
 * <code>touched</code> attribute. So a test can tell whether the aggregate was saved
 * after the method ran.
 */
@Entity
@Table(name = "NOTED_AGGREGATE")
@Getter
@Setter
public class NotedAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String content;

  private String touched;

}
