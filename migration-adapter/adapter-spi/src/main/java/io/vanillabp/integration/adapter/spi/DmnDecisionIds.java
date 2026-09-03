package io.vanillabp.integration.adapter.spi;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.UnaryOperator;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/**
 * Reads and rewrites the decision ids of a DMN file, which is the one thing about a
 * decision table VanillaBP has to touch - and the one thing which is the same for every
 * BPMS, because DMN is a standard: a decision is <code>&lt;decision id="..."&gt;</code>
 * and a decision built on another one names it as
 * <code>&lt;requiredDecision href="#..."/&gt;</code>.
 * <p>
 * Why it is rewritten at all: where a workflow module is kept apart from another one by
 * PREFIXING its identifiers rather than by an isolation of the BPMS
 * ({@link NameClashAvoidance#USE_PREFIX}), two modules bringing
 * a decision of the same id would overwrite each other in the BPMS. A decision id is
 * scoped MODULE-WIDE, not per process
 * ({@link NameClashAvoidanceSupport#scopedIdentifier(String, String, String)}): several
 * processes of a module legitimately call the same decision.
 * <p>
 * What an ADAPTER has to do with the result is the half this class cannot do: the
 * reference from a business rule task to a decision is engine-specific
 * (<code>camunda:decisionRef</code>, <code>zeebe:calledDecision</code>), so the adapter
 * rewrites it with the same function while it prepares the BPMN.
 * <p>
 * Only ids this file DECLARES are rewritten, and only references pointing at one of them.
 * An input data element, a business knowledge model or a reference to a decision of
 * another file keeps its id: what is not deployed here is not renamed here.
 */
public final class DmnDecisionIds {

  private static final String DECISION = "decision";

  private static final String HREF = "href";

  private DmnDecisionIds() {

    throw new IllegalStateException("Not to be instantiated");

  }

  /**
   * The ids of the decisions the given DMN file declares.
   *
   * @param dmn The DMN file
   * @return The decision ids, in the order of the file
   * @throws IllegalArgumentException If the file cannot be read as DMN
   */
  public static Set<String> of(
      final byte[] dmn) {

    final var document = parse(dmn);
    final var decisionIds = new LinkedHashSet<String>();
    forEachDecision(document, decision -> {
      final var id = decision.getAttribute("id");
      if (!id.isBlank()) {
        decisionIds.add(id);
      }
    });
    return decisionIds;

  }

  /**
   * Rewrites every decision id of the given DMN file and every reference pointing at one
   * of them.
   *
   * @param dmn The DMN file
   * @param scoping What a decision id becomes - typically
   *          <code>id -&gt; scoping.scopedIdentifier(workflowModuleId, id, adapterId)</code>
   * @return The rewritten file
   * @throws IllegalArgumentException If the file cannot be read as DMN
   */
  public static byte[] rewrite(
      final byte[] dmn,
      final UnaryOperator<String> scoping) {

    final var document = parse(dmn);

    final var rewritten = new LinkedHashSet<String>();
    forEachDecision(document, decision -> {
      final var id = decision.getAttribute("id");
      if (id.isBlank()) {
        return;
      }
      decision.setAttribute("id", scoping.apply(id));
      rewritten.add(id);
    });
    if (rewritten.isEmpty()) {
      return dmn;
    }

    // a reference is written as a fragment of a URI, so only the local part is a
    // decision id of this file - a href into another file names that file first and is
    // none of this rewrite's business
    forEachElement(document, element -> {
      final var href = element.getAttribute(HREF);
      if (!href.startsWith("#")) {
        return;
      }
      final var referenced = href.substring(1);
      if (rewritten.contains(referenced)) {
        element.setAttribute(HREF, "#"
            + scoping.apply(referenced));
      }
    });

    return write(document);

  }

  private static void forEachDecision(
      final Document document,
      final java.util.function.Consumer<Element> work) {

    forEachElement(document, element -> {
      if (DECISION.equals(element.getLocalName())) {
        work.accept(element);
      }
    });

  }

  private static void forEachElement(
      final Document document,
      final java.util.function.Consumer<Element> work) {

    final var elements = document.getElementsByTagName("*");
    for (var i = 0; i < elements.getLength(); i++) {
      if (elements.item(i) instanceof final Element element) {
        work.accept(element);
      }
    }

  }

  private static Document parse(
      final byte[] dmn) {

    try {
      final var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      // the file comes from the application's own resources, but a parser reading it
      // must not fetch anything a document points at
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      try (var stream = new ByteArrayInputStream(dmn)) {
        return factory
            .newDocumentBuilder()
            .parse(stream);
      }
    } catch (final ParserConfigurationException | SAXException | IOException e) {
      throw new IllegalArgumentException("The DMN file could not be read as XML!", e);
    }

  }

  private static byte[] write(
      final Document document) {

    try {
      final var factory = TransformerFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
      final var result = new ByteArrayOutputStream();
      factory
          .newTransformer()
          .transform(new DOMSource(document), new StreamResult(result));
      return result.toByteArray();
    } catch (final javax.xml.transform.TransformerException e) {
      throw new IllegalArgumentException("The rewritten DMN file could not be written!", e);
    }

  }

  /**
   * Reads a stream into memory - a DMN travels as bytes through the pipeline, because
   * nothing but the ids above has to be understood.
   *
   * @param dmn The stream, NOT closed by this method (the pipeline owns it)
   * @return The bytes
   * @throws IllegalArgumentException If the stream cannot be read
   */
  public static byte[] bytesOf(
      final InputStream dmn) {

    try {
      return dmn.readAllBytes();
    } catch (final IOException e) {
      throw new IllegalArgumentException("The DMN file could not be read!", e);
    }

  }

}
