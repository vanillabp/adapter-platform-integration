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
   * The empty substitute both sides start from: {@link UriSubstitution} builds one while the
   * application is built, and the bytecode of the boot builds one and fills it through the
   * setter. A {@link java.net.URI} cannot travel that way, which is the whole reason this
   * class exists.
   */
  public UriSubstitute() {
  }

  /**
   * The {@link java.net.URI} as a string.
   */
  private String uri;

}
