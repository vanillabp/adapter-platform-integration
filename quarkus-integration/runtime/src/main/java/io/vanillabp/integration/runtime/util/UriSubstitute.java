package io.vanillabp.integration.runtime.util;

import lombok.Getter;
import lombok.Setter;

/**
 * A serializable substitute for {@link java.net.URI}.
 */
@Getter
@Setter
public class UriSubstitute {

  /**
   * The {@link java.net.URI} as a string.
   */
  private String uri;

}
