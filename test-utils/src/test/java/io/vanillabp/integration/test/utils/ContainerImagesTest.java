package io.vanillabp.integration.test.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The pins of {@link ContainerImages}. Each of them has to name a version, so a run says what
 * it ran against.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ContainerImagesTest {

  @Test
  @DisplayName("Every image names a version of its own and not 'latest'")
  public void everyImageNamesAVersion() throws IllegalAccessException {

    final var pins = Arrays
        .stream(ContainerImages.class.getDeclaredFields())
        .filter(field -> Modifier.isPublic(field.getModifiers()) && Modifier.isStatic(field.getModifiers()))
        .toList();
    assertFalse(pins.isEmpty(), "the class holds the pins as public constants");
    for (final var pin : pins) {
      final var image = (String) pin.get(null);
      final var tag = image.substring(image.lastIndexOf('/') + 1);
      assertTrue(
          tag.contains(":") && !tag.endsWith(":"),
          () -> pin.getName()
              + " names no version: "
              + image);
      assertFalse(
          tag.endsWith(":latest"),
          () -> pin.getName()
              + " follows whatever is released next: "
              + image);
    }

  }

  @Test
  @DisplayName("The Kafka broker is an image of Apache Kafka")
  public void kafkaIsPinned() {

    assertTrue(
        ContainerImages.KAFKA.contains("kafka:"),
        () -> "the Kafka broker of the tests: "
            + ContainerImages.KAFKA);

  }

}
