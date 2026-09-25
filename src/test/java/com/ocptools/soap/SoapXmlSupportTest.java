package com.ocptools.soap;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoapXmlSupportTest {

    @Test
    void parsesFoundResponse() throws Exception {
        SoapOutput output = SoapXmlSupport.parseOutput(xml("""
                <soap-env:Envelope xmlns:soap-env="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap-env:Body>
                    <OutputParameters xmlns="http://xmlns.oracle.com/pcbpel/adapter/db/sp/selectDB">
                      <OUT_SUBSYSTEM>DIVPMX2</OUT_SUBSYSTEM>
                      <OUT_CODRES>0</OUT_CODRES>
                      <OUT_MSGRES>REGISTROS ENCONTRADOS</OUT_MSGRES>
                    </OutputParameters>
                  </soap-env:Body>
                </soap-env:Envelope>
                """));

        assertEquals("0", output.code());
        assertEquals("REGISTROS ENCONTRADOS", output.message());
        assertEquals("DIVPMX2", output.subsystem());
    }

    @Test
    void parsesNotFoundResponseWithNilSubsystem() throws Exception {
        SoapOutput output = SoapXmlSupport.parseOutput(xml("""
                <soap-env:Envelope xmlns:soap-env="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap-env:Body>
                    <OutputParameters xmlns="http://xmlns.oracle.com/pcbpel/adapter/db/sp/selectDB"
                                      xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                      <OUT_SUBSYSTEM xsi:nil="true"/>
                      <OUT_CODRES>-1</OUT_CODRES>
                      <OUT_MSGRES>ERROR: 100 ORA-01403: no data found</OUT_MSGRES>
                    </OutputParameters>
                  </soap-env:Body>
                </soap-env:Envelope>
                """));

        assertEquals("-1", output.code());
        assertTrue(output.message().contains("ORA-01403"));
        assertNull(output.subsystem());
    }

    @Test
    void preservesMultipleRowsErrorForClassification() throws Exception {
        SoapOutput output = SoapXmlSupport.parseOutput(xml("""
                <soap-env:Envelope xmlns:soap-env="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap-env:Body>
                    <OutputParameters xmlns="http://xmlns.oracle.com/pcbpel/adapter/db/sp/selectDB">
                      <OUT_SUBSYSTEM/>
                      <OUT_CODRES>-1</OUT_CODRES>
                      <OUT_MSGRES>ERROR: -1422 ORA-01422: exact fetch returns more than requested number of rows</OUT_MSGRES>
                    </OutputParameters>
                  </soap-env:Body>
                </soap-env:Envelope>
                """));

        assertEquals("-1", output.code());
        assertTrue(output.message().contains("ORA-01422"));
    }

    @Test
    void createsSafeRegisterEnvelope() throws Exception {
        String request = new String(
                SoapXmlSupport.registerEnvelope("pid-1", "exec-1", "DIVPMX2", "A&B<1>"),
                StandardCharsets.UTF_8);

        assertTrue(request.contains("DIVPMX2"));
        assertTrue(request.contains("A&amp;B&lt;1>"));
        assertTrue(request.contains("IN_APPTNUMBER"));
    }

    private static byte[] xml(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}

