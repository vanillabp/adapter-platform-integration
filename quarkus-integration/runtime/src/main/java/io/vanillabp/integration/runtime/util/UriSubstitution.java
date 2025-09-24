package io.vanillabp.integration.runtime.util;

import java.net.URI;
import java.net.URISyntaxException;

import io.quarkus.runtime.ObjectSubstitution;

/**
 * Object substitution for {@link URI}.
 */
public class UriSubstitution implements ObjectSubstitution<URI, UriSubstitute> {

  /**
   * Serialize {@link URI} to {@link UriSubstitute}.
   *
   * @param uri The {@link URI} to serialize
   * @return The serialized {@link URI}
   */
  @Override
  public UriSubstitute serialize(
      final URI uri) {

    final var substitute = new UriSubstitute();
    if (uri != null) {
      substitute.setUri(uri.toString());
    }
    return substitute;

  }

  /**
   * Deserialize {@link UriSubstitute} to {@link URI}.
   *
   * @param substitute The serialized {@link URI}
   * @return The deserialized {@link URI}
   */
  @Override
  public URI deserialize(
      final UriSubstitute substitute) {

    if ((substitute == null) || (substitute.getUri() == null)) {
      return null;
    }

    try {
      return new URI(substitute.getUri());
    } catch (URISyntaxException e) {
      throw new RuntimeException("Invalid URI in substitution", e);
    }

  }

}
