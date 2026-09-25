package com.ocptools.soap;

import com.ocptools.config.ToolsConfig;
import com.ocptools.domain.CmsRecord;
import com.ocptools.domain.EnvironmentTarget;
import com.ocptools.domain.SoapQueryResult;
import com.ocptools.domain.SoapRegisterResult;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

@ApplicationScoped
public class EsbSoapClient {
    private static final String CONTENT_TYPE = "text/xml; charset=utf-8";

    @Inject
    ToolsConfig config;

    private HttpClient httpClient;

    @PostConstruct
    void initialize() {
        Duration connectTimeout = shortest(config.timeouts().soapQuery(), config.timeouts().soapRegister());
        httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public SoapQueryResult query(CmsRecord cmsRecord) {
        try {
            byte[] body = SoapXmlSupport.queryEnvelope(cmsRecord.pid(), cmsRecord.execId());
            HttpResponse<byte[]> response = send(
                    config.soap().queryUrl(), "\"selectDB\"", body, config.timeouts().soapQuery());

            if (!isSuccess(response.statusCode())) {
                return SoapQueryResult.error("HTTP " + response.statusCode());
            }

            SoapOutput output = SoapXmlSupport.parseOutput(response.body());
            String code = output.code().trim();
            String message = normalize(output.message());

            if ("0".equals(code) && "REGISTROS ENCONTRADOS".equals(message)) {
                if (output.subsystem() == null || output.subsystem().isBlank()) {
                    return SoapQueryResult.error("Respuesta encontrada sin OUT_SUBSYSTEM");
                }
                return SoapQueryResult.found(output.subsystem().trim());
            }

            if ("-1".equals(code)
                    && message.contains("ORA-01403")
                    && message.contains("NO DATA FOUND")) {
                return SoapQueryResult.notFound();
            }

            return SoapQueryResult.error("OUT_CODRES=" + code + " OUT_MSGRES=" + safeDetail(output.message()));
        } catch (HttpTimeoutException exception) {
            return SoapQueryResult.timeout("queryESb superó el timeout");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return SoapQueryResult.error("queryESb fue interrumpido");
        } catch (IOException | SoapProtocolException exception) {
            return SoapQueryResult.error(safeDetail(exception.getMessage()));
        }
    }

    public SoapRegisterResult register(CmsRecord cmsRecord, EnvironmentTarget environment) {
        try {
            byte[] body = SoapXmlSupport.registerEnvelope(
                    cmsRecord.pid(), cmsRecord.execId(), environment.subsystem(), cmsRecord.externalId());
            HttpResponse<byte[]> response = send(
                    config.soap().registerUrl(), "\"ESBPRD\"", body, config.timeouts().soapRegister());

            if (!isSuccess(response.statusCode())) {
                return SoapRegisterResult.failed("HTTP " + response.statusCode());
            }

            SoapOutput output = SoapXmlSupport.parseOutput(response.body());
            if ("0".equals(output.code().trim())
                    && "TRANSACCION REALIZADA CON EXITO".equals(normalize(output.message()))) {
                return SoapRegisterResult.success();
            }
            return SoapRegisterResult.failed(
                    "OUT_CODRES=" + output.code() + " OUT_MSGRES=" + safeDetail(output.message()));
        } catch (HttpTimeoutException exception) {
            return SoapRegisterResult.timeout("createEsb superó el timeout");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return SoapRegisterResult.failed("createEsb fue interrumpido");
        } catch (IOException | SoapProtocolException exception) {
            return SoapRegisterResult.failed(safeDetail(exception.getMessage()));
        }
    }

    private HttpResponse<byte[]> send(java.net.URI uri, String soapAction, byte[] body,
                                      Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", CONTENT_TYPE)
                .header("SOAPAction", soapAction)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String safeDetail(String value) {
        if (value == null || value.isBlank()) {
            return "sin detalle";
        }
        String cleaned = new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)
                .replaceAll("[\\r\\n\\t]", " ")
                .trim();
        return cleaned.length() <= 300 ? cleaned : cleaned.substring(0, 300);
    }

    private static Duration shortest(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }
}

