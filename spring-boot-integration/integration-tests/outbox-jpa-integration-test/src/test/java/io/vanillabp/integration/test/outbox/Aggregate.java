package io.vanillabp.integration.test.outbox;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A JPA workflow aggregate having a generated (non-string) ID: the outbox has to
 * serialize the ID as a string and the phase-two bean has to convert it back.
 */
@Entity
@Table(name = "OUTBOX_TEST_AGGREGATE")
@Getter
@Setter
public class Aggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String content;

}
