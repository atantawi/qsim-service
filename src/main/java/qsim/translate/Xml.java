/*
 * qsim-service — a JMT-backed queueing-network simulation service.
 * Copyright (C) 2026 qsim-service contributors.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */
package qsim.translate;

import java.io.StringWriter;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public final class Xml {
  private Xml() {}

  public static Document newDocument() {
    try {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setNamespaceAware(true);
      return f.newDocumentBuilder().newDocument();
    } catch (Exception e) {
      throw new IllegalStateException("cannot create XML document", e);
    }
  }

  /** Append a child element; attrPairs are alternating name,value. A pair with null value is skipped. */
  public static Element child(Node parent, String tag, String... attrPairs) {
    Document doc = parent instanceof Document d ? d : parent.getOwnerDocument();
    // createElementNS(null, ...) gives the element a proper localName (with a null
    // namespace URI) so JAXP schema validation can match it against the
    // no-target-namespace SIMmodeldefinition.xsd. Plain createElement leaves
    // localName null, which makes the validator report "cannot find declaration".
    Element el = doc.createElementNS(null, tag);
    for (int i = 0; i + 1 < attrPairs.length; i += 2) {
      String name = attrPairs[i];
      String value = attrPairs[i + 1];
      if (value != null) {
        // setAttributeNS(null, ...) gives the attribute a proper localName so schema
        // validation matches it; plain setAttribute leaves localName null.
        el.setAttributeNS(null, name, value);
      }
    }
    parent.appendChild(el);
    return el;
  }

  /** Element containing a single text node (used for <value>x</value>). */
  public static Element textEl(Node parent, String tag, String text) {
    Element el = child(parent, tag);
    el.appendChild(el.getOwnerDocument().createTextNode(text));
    return el;
  }

  public static String serialize(Document doc) {
    try {
      var t = TransformerFactory.newInstance().newTransformer();
      t.setOutputProperty(OutputKeys.INDENT, "yes");
      t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
      StringWriter sw = new StringWriter();
      t.transform(new DOMSource(doc), new StreamResult(sw));
      return sw.toString();
    } catch (Exception e) {
      throw new IllegalStateException("cannot serialize XML document", e);
    }
  }
}
