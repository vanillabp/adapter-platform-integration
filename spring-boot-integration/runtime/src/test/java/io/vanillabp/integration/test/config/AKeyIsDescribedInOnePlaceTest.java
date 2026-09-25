package io.vanillabp.integration.test.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A configuration key is described once, and this test says where.
 * <p>
 * The field owns the key it declares: the annotation processor turns its javadoc into the
 * description, so the text a developer reads in their editor is the text the next developer
 * reads in the source. The hand-written
 * <code>META-INF/additional-spring-configuration-metadata.json</code> describes what the
 * processor cannot reach, which is everything below a type arriving as a dependency - the
 * whole core model.
 * <p>
 * Where both describe a key, Spring merges them and the hand-written text wins. Nothing
 * says the two texts agree, so they drift apart and the source stops being the answer. This
 * test looks for that: a key the hand-written file describes must not be a field of a class
 * this module compiles.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AKeyIsDescribedInOnePlaceTest {

  /**
   * Reads every key of the hand-written file and asks the generated metadata which class a
   * field of that name was found in.
   *
   * @throws IOException If the classpath cannot be read
   */
  @Test
  @DisplayName("No key is described by hand and by a field of this module")
  public void noKeyIsDescribedTwice() throws IOException {

    final var writtenByHand = new TreeSet<String>();
    SpringConfigurationMetadata
        .propertiesOf(SpringConfigurationMetadata.HAND_WRITTEN)
        .forEach(property -> writtenByHand.add(property.path("name").asText()));
    assertTrue(
        writtenByHand.contains("vanillabp.outbox.retention"),
        """
            The hand-written configuration metadata was not found! It is copied next to the \
            classes while the main sources are built, so run this test through the build.""");

    final var describedTwice = new TreeSet<String>();
    for (final var property : SpringConfigurationMetadata.propertiesOf(SpringConfigurationMetadata.GENERATED)) {
      final var key = property.path("name").asText();
      if (!writtenByHand.contains(key)) {
        continue;
      }
      final var owner = fieldOwnerOf(property.path("sourceType").asText(null), key);
      if (owner != null) {
        describedTwice.add("%s (a field of %s)".formatted(key, owner));
      }
    }

    assertTrue(
        describedTwice.isEmpty(),
        """
            These keys are described twice, once by the javadoc of a field and once by hand: %s
            Spring merges the two and the hand-written text wins, so the javadoc is shown to \
            nobody and the two say something different as soon as one of them is changed. Delete \
            the entry from 'META-INF/additional-spring-configuration-metadata.json' and write \
            the description on the field, or move the field into the core model where the \
            hand-written file is the only way to describe it."""
            .formatted(describedTwice));

  }

  /**
   * The class which DECLARES the field behind a key, or <code>null</code> where no class of
   * this module does.
   * <p>
   * The processor names the class it started from, which for the core model is the thin
   * subclass carrying the annotation. That class inherits those fields rather than
   * declaring them, and an inherited field is exactly the case the hand-written file exists
   * for.
   *
   * @param sourceType What the generated metadata says, or <code>null</code> where it says
   *          nothing
   * @param key The key, written the way an application writes it
   * @return The declaring class, or <code>null</code>
   */
  private static Class<?> fieldOwnerOf(
      final String sourceType,
      final String key) {

    if (sourceType == null) {
      return null;
    }
    final Class<?> boundClass;
    try {
      boundClass = Class.forName(sourceType, false, AKeyIsDescribedInOnePlaceTest.class.getClassLoader());
    } catch (final ClassNotFoundException e) {
      // a key of another artifact of ours, whose class this module does not see
      return null;
    }
    final var fieldName = camelCase(key.substring(key.lastIndexOf('.') + 1));
    for (final var field : boundClass.getDeclaredFields()) {
      if (field.getName().equals(fieldName)) {
        return boundClass;
      }
    }
    return null;

  }

  /**
   * @param relaxedName The last segment of a key, in the dashed spelling
   * @return The name the field carries
   */
  private static String camelCase(
      final String relaxedName) {

    final var name = new StringBuilder();
    var afterDash = false;
    for (final var character : relaxedName.toCharArray()) {
      if (character == '-') {
        afterDash = true;
        continue;
      }
      name.append(afterDash
          ? Character.toUpperCase(character)
          : character);
      afterDash = false;
    }
    return name.toString();

  }

}
