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
   * Creates an empty substitute. Both sides start from it. {@link UriSubstitution} creates one
   * while the application is built. The recorded bytecode of the boot creates one and fills it
   * through the setter. A {@link java.net.URI} cannot travel that way. That is the whole reason
   * why this class exists.
   */
  public UriSubstitute() {
  }

  /**
   * The {@link java.net.URI} as a string.
   */
  private String uri;

}
