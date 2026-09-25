package com.ocptools.soap;

import org.w3c.dom.Document;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

final class SoapXmlSupport {
    private static final String SOAP_NAMESPACE = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String QUERY_NAMESPACE = "http://xmlns.oracle.com/pcbpel/adapter/db/sp/selectDB";
    private static final String REGISTER_NAMESPACE = "http://xmlns.oracle.com/pcbpel/adapter/db/sp/ESBPRD";

    private SoapXmlSupport() {
    }

    static byte[] queryEnvelope(String pid, String execId) throws SoapProtocolException {
        return envelope("sel", QUERY_NAMESPACE, writer -> {
            element(writer, "sel", QUERY_NAMESPACE, "IN_PID", pid);
            element(writer, "sel", QUERY_NAMESPACE, "IN_EXECID", execId);
        });
    }

    static byte[] registerEnvelope(String pid, String execId, String subsystem,
                                   String externalId) throws SoapProtocolException {
        return envelope("esb", REGISTER_NAMESPACE, writer -> {
            element(writer, "esb", REGISTER_NAMESPACE, "IN_PID", pid);
            element(writer, "esb", REGISTER_NAMESPACE, "IN_EXECID", execId);
            element(writer, "esb", REGISTER_NAMESPACE, "IN_SUBSYSTEM", subsystem);
            element(writer, "esb", REGISTER_NAMESPACE, "IN_EXTERNALID", externalId);
            element(writer, "esb", REGISTER_NAMESPACE, "IN_APPTNUMBER", "");
        });
    }

    static SoapOutput parseOutput(byte[] xml) throws SoapProtocolException {
        if (xml == null || xml.length == 0) {
            throw new SoapProtocolException("El servicio SOAP devolvió una respuesta vacía");
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            Document document = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml));
            XPath xpath = XPathFactory.newInstance().newXPath();

            Node fault = node(xpath, document, "//*[local-name()='Fault']");
            if (fault != null) {
                String faultText = text(xpath, document, "//*[local-name()='Fault']/*[local-name()='faultstring']");
                throw new SoapProtocolException("SOAP Fault: " + safeDetail(faultText));
            }

            String code = text(xpath, document, "//*[local-name()='OutputParameters']/*[local-name()='OUT_CODRES']");
            String message = text(xpath, document, "//*[local-name()='OutputParameters']/*[local-name()='OUT_MSGRES']");
            String subsystem = text(xpath, document, "//*[local-name()='OutputParameters']/*[local-name()='OUT_SUBSYSTEM']");

            if (code == null || message == null) {
                throw new SoapProtocolException("La respuesta SOAP no contiene OUT_CODRES u OUT_MSGRES");
            }
            return new SoapOutput(code, message, subsystem);
        } catch (SoapProtocolException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SoapProtocolException("La respuesta SOAP no es XML válido", exception);
        }
    }

    private static byte[] envelope(String operationPrefix, String operationNamespace,
                                   XmlBodyWriter bodyWriter) throws SoapProtocolException {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            XMLStreamWriter writer = XMLOutputFactory.newFactory()
                    .createXMLStreamWriter(output, StandardCharsets.UTF_8.name());

            writer.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
            writer.writeStartElement("soapenv", "Envelope", SOAP_NAMESPACE);
            writer.writeNamespace("soapenv", SOAP_NAMESPACE);
            writer.writeNamespace(operationPrefix, operationNamespace);
            writer.writeEmptyElement("soapenv", "Header", SOAP_NAMESPACE);
            writer.writeStartElement("soapenv", "Body", SOAP_NAMESPACE);
            writer.writeStartElement(operationPrefix, "InputParameters", operationNamespace);
            bodyWriter.write(writer);
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeEndDocument();
            writer.close();
            return output.toByteArray();
        } catch (Exception exception) {
            throw new SoapProtocolException("No fue posible construir el request SOAP", exception);
        }
    }

    private static void element(XMLStreamWriter writer, String prefix, String namespace,
                                String name, String value) throws Exception {
        writer.writeStartElement(prefix, name, namespace);
        if (value != null) {
            writer.writeCharacters(value);
        }
        writer.writeEndElement();
    }

    private static Node node(XPath xpath, Document document, String expression) throws Exception {
        return (Node) xpath.evaluate(expression, document, XPathConstants.NODE);
    }

    private static String text(XPath xpath, Document document, String expression) throws Exception {
        Node value = node(xpath, document, expression);
        if (value == null) {
            return null;
        }
        String text = value.getTextContent();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static String safeDetail(String value) {
        if (value == null || value.isBlank()) {
            return "sin detalle";
        }
        String cleaned = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return cleaned.length() <= 300 ? cleaned : cleaned.substring(0, 300);
    }

    @FunctionalInterface
    private interface XmlBodyWriter {
        void write(XMLStreamWriter writer) throws Exception;
    }
}

