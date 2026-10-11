package io.vanillabp.extension.sample;

/**
 * The note which the extension prefills from what it knows. The application's method adds
 * to it or replaces it. The Business Cockpit's prefilled details have the same shape.
 * <p>
 * It reaches the method as the payload of the call. A parameter binder which this
 * extension contributes binds it.
 */
public class SampleNoteDetails {

  /**
   * What happened to the element the note is about.
   */
  public enum Kind {

    /**
     * The element was reached.
     */
    CREATED,

    /**
     * The element was left.
     */
    COMPLETED

  }

  private final String elementId;

  private final Kind kind;

  private String title;

  public SampleNoteDetails(
      final String elementId,
      final Kind kind,
      final String title) {

    this.elementId = elementId;
    this.kind = kind;
    this.title = title;

  }

  /**
   * @return The BPMN element the note is about
   */
  public String getElementId() {

    return elementId;

  }

  /**
   * @return What happened to it
   */
  public Kind getKind() {

    return kind;

  }

  /**
   * @return The title, prefilled by the extension
   */
  public String getTitle() {

    return title;

  }

  /**
   * @param title The title the application's method wants instead
   */
  public void setTitle(
      final String title) {

    this.title = title;

  }

}
