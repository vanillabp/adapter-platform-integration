package io.vanillabp.integration.test.persistence.parallel;

/**
 * A plain object which the codec of MongoDB Panache writes into the document of the
 * aggregate, as a sub-document. So each change in here is a change of the aggregate.
 */
public class Details {

  private String note;

  public String getNote() {
    return note;
  }

  public void setNote(
      final String note) {
    this.note = note;
  }

}
