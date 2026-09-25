package com.ocptools.service;

import com.ocptools.api.ToolRequest;
import com.ocptools.api.ToolResponse;
import com.ocptools.cms.CmsRepository;
import com.ocptools.cms.CmsPostRegisterDelay;
import com.ocptools.common.AuditLogger;
import com.ocptools.common.CmsDataException;
import com.ocptools.common.ExternalTimeoutException;
import com.ocptools.common.RequestMetadata;
import com.ocptools.domain.CmsRecord;
import com.ocptools.domain.EnvironmentTarget;
import com.ocptools.domain.SoapQueryResult;
import com.ocptools.domain.SoapRegisterResult;
import com.ocptools.domain.ToolStatus;
import com.ocptools.environment.EnvironmentCatalog;
import com.ocptools.soap.EsbSoapClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Locale;
import java.util.Optional;

@ApplicationScoped
public class CmsToolService {
    private static final Logger LOG = Logger.getLogger(CmsToolService.class);

    @Inject
    CmsRepository cmsRepository;

    @Inject
    CmsPostRegisterDelay postRegisterDelay;

    @Inject
    EsbSoapClient soapClient;

    @Inject
    EnvironmentCatalog environments;

    @Inject
    RequestMetadata metadata;

    @Inject
    AuditLogger auditLogger;

    public ToolResponse validate(ToolRequest request) {
        long started = System.nanoTime();
        String accessId = normalizeAccessId(request.accessId());
        Optional<EnvironmentTarget> requestedEnvironment = environments.byLabel(request.environment());
        if (requestedEnvironment.isEmpty()) {
            return complete("VALIDATE", started, null,
                    response(accessId, ToolStatus.INVALID_REQUEST,
                            "El ambiente seleccionado no está permitido.", false, null,
                            normalizeEnvironment(request.environment()), null, null, false, false));
        }

        CmsLookup cmsLookup = lookupCms(accessId, requestedEnvironment.get().label());
        if (cmsLookup.terminalResponse() != null) {
            return complete("VALIDATE", started, null, cmsLookup.terminalResponse());
        }

        CmsRecord cmsRecord = cmsLookup.record();
        SoapQueryResult queryResult = soapClient.query(cmsRecord);
        ToolResponse result = validationResponse(cmsRecord, requestedEnvironment.get(), queryResult);
        logTechnicalDetail("queryESb", queryResult.technicalDetail());
        return complete("VALIDATE", started, cmsRecord, result);
    }

    /**
     * La beta se despliega con una sola réplica. La sincronización evita dos registros
     * concurrentes desde el mismo pod cuando el origen no posee una restricción única.
     */
    public synchronized ToolResponse register(ToolRequest request) {
        long started = System.nanoTime();
        String accessId = normalizeAccessId(request.accessId());
        Optional<EnvironmentTarget> requestedEnvironment = environments.byLabel(request.environment());
        if (requestedEnvironment.isEmpty()) {
            return complete("REGISTER", started, null,
                    response(accessId, ToolStatus.INVALID_REQUEST,
                            "El ambiente seleccionado no está permitido.", false, null,
                            normalizeEnvironment(request.environment()), null, null, false, false));
        }

        CmsLookup cmsLookup = lookupCms(accessId, requestedEnvironment.get().label());
        if (cmsLookup.terminalResponse() != null) {
            return complete("REGISTER", started, null, cmsLookup.terminalResponse());
        }
        CmsRecord cmsRecord = cmsLookup.record();

        // Revalidación obligatoria para reducir duplicados entre la consulta UI y el registro.
        SoapQueryResult beforeRegister = soapClient.query(cmsRecord);
        logTechnicalDetail("queryESb-before-register", beforeRegister.technicalDetail());
        if (beforeRegister.outcome() != SoapQueryResult.Outcome.NOT_FOUND) {
            ToolResponse blocked = validationResponse(cmsRecord, requestedEnvironment.get(), beforeRegister);
            return complete("REGISTER_BLOCKED", started, cmsRecord, blocked);
        }

        SoapRegisterResult registerResult = soapClient.register(cmsRecord, requestedEnvironment.get());
        logTechnicalDetail("createEsb", registerResult.technicalDetail());
        if (registerResult.outcome() == SoapRegisterResult.Outcome.FAILED) {
            return complete("REGISTER", started, cmsRecord,
                    response(cmsRecord, ToolStatus.REGISTER_FAILED,
                            "No fue posible registrar la transacción. No se realizará un reintento automático.",
                            requestedEnvironment.get().label(), null, null, false, false));
        }

        boolean delayCompleted = postRegisterDelay.await();
        if (!delayCompleted) {
            LOG.warnf("Espera previa al reenvío CMS interrumpida correlationId=%s",
                    metadata.correlationId());
        }

        CmsResendResult resendResult = CmsResendResult.notRequired();
        if (registerResult.outcome() == SoapRegisterResult.Outcome.SUCCESS) {
            resendResult = delayCompleted
                    ? resendFromCms(cmsRecord.accessId())
                    : CmsResendResult.failure();
        }

        // Se verifica tanto el éxito como el timeout: un timeout de createEsb es indeterminado.
        SoapQueryResult verification = soapClient.query(cmsRecord);
        logTechnicalDetail("queryESb-after-register", verification.technicalDetail());

        // Con timeout sólo se marca CMS cuando queryESb confirma el registro solicitado.
        if (registerResult.outcome() == SoapRegisterResult.Outcome.TIMEOUT
                && registrationConfirmed(requestedEnvironment.get(), verification)) {
            resendResult = delayCompleted
                    ? resendFromCms(cmsRecord.accessId())
                    : CmsResendResult.failure();
        }

        ToolResponse verified = verificationResponse(
                cmsRecord, requestedEnvironment.get(), registerResult, verification);
        return complete("REGISTER", started, cmsRecord,
                appendCmsResendWarning(verified, resendResult));
    }

    private CmsResendResult resendFromCms(String accessId) {
        try {
            int updatedRows = cmsRepository.markForResend(accessId);
            if (updatedRows <= 0) {
                LOG.warnf("Reenvío CMS sin filas actualizadas correlationId=%s",
                        metadata.correlationId());
                return CmsResendResult.failure();
            }
            LOG.infof("Reenvío CMS solicitado updatedRows=%d correlationId=%s",
                    updatedRows, metadata.correlationId());
            return CmsResendResult.success();
        } catch (ExternalTimeoutException exception) {
            LOG.warnf("Timeout al solicitar reenvío CMS correlationId=%s",
                    metadata.correlationId());
            return CmsResendResult.failure();
        } catch (CmsDataException exception) {
            LOG.errorf(exception, "Error al solicitar reenvío CMS correlationId=%s",
                    metadata.correlationId());
            return CmsResendResult.failure();
        }
    }

    private static boolean registrationConfirmed(EnvironmentTarget requested,
                                                 SoapQueryResult verification) {
        return verification.outcome() == SoapQueryResult.Outcome.FOUND
                && requested.subsystem().equalsIgnoreCase(verification.subsystem());
    }

    private static ToolResponse appendCmsResendWarning(ToolResponse response,
                                                        CmsResendResult resendResult) {
        if (!resendResult.hasFailed()) {
            return response;
        }
        return response.withMessage(response.message() + " No se pudo reenviar desde CMS.");
    }

    private CmsLookup lookupCms(String accessId, String requestedEnvironment) {
        try {
            Optional<CmsRecord> cmsRecord = cmsRepository.findLatest(accessId);
            if (cmsRecord.isEmpty()) {
                return CmsLookup.terminal(response(accessId, ToolStatus.CMS_NOT_FOUND,
                        "La AccessID no está en CMS.", false, null, requestedEnvironment,
                        null, null, false, false));
            }
            if (isBlank(cmsRecord.get().pid()) || isBlank(cmsRecord.get().execId())) {
                LOG.warnf("CMS devolvió PID o EXECID vacío correlationId=%s", metadata.correlationId());
                return CmsLookup.terminal(response(cmsRecord.get(), ToolStatus.DATABASE_ERROR,
                        "CMS devolvió un registro incompleto. No se continuará con la operación.",
                        requestedEnvironment, null, null, false, false));
            }
            return CmsLookup.found(cmsRecord.get());
        } catch (ExternalTimeoutException exception) {
            LOG.warnf("Timeout CMS correlationId=%s", metadata.correlationId());
            return CmsLookup.terminal(response(accessId, ToolStatus.TIMEOUT,
                    "La consulta CMS superó el límite de 5 segundos.", false, null,
                    requestedEnvironment, null, null, false, false));
        } catch (CmsDataException exception) {
            LOG.errorf(exception, "Error CMS correlationId=%s", metadata.correlationId());
            return CmsLookup.terminal(response(accessId, ToolStatus.DATABASE_ERROR,
                    "Ocurrió un error técnico al consultar CMS.", false, null,
                    requestedEnvironment, null, null, false, false));
        }
    }

    private ToolResponse validationResponse(CmsRecord cmsRecord, EnvironmentTarget requested,
                                            SoapQueryResult queryResult) {
        return switch (queryResult.outcome()) {
            case FOUND -> {
                String foundEnvironment = environments.displayNameForSubsystem(queryResult.subsystem());
                yield response(cmsRecord, ToolStatus.ENVIRONMENT_FOUND,
                        "La transacción ya existe en " + foundEnvironment + ".",
                        requested.label(), foundEnvironment, queryResult.subsystem(), false, false);
            }
            case NOT_FOUND -> response(cmsRecord, ToolStatus.ENVIRONMENT_NOT_FOUND,
                    "La transacción no está registrada. Puede registrarse en " + requested.label() + ".",
                    requested.label(), null, null, true, false);
            case TIMEOUT -> response(cmsRecord, ToolStatus.TIMEOUT,
                    "queryESb superó el límite de 5 segundos. El registro está bloqueado.",
                    requested.label(), null, null, false, false);
            case ERROR -> response(cmsRecord, ToolStatus.QUERY_ERROR,
                    "queryESb devolvió un error técnico. El registro está bloqueado.",
                    requested.label(), null, null, false, false);
        };
    }

    private ToolResponse verificationResponse(CmsRecord cmsRecord, EnvironmentTarget requested,
                                              SoapRegisterResult registerResult,
                                              SoapQueryResult verification) {
        if (verification.outcome() == SoapQueryResult.Outcome.FOUND
                && requested.subsystem().equalsIgnoreCase(verification.subsystem())) {
            String message = registerResult.outcome() == SoapRegisterResult.Outcome.TIMEOUT
                    ? "createEsb no respondió a tiempo, pero el registro fue confirmado en " + requested.label() + "."
                    : "La transacción fue registrada y confirmada en " + requested.label() + ".";
            return response(cmsRecord, ToolStatus.REGISTER_SUCCESS, message,
                    requested.label(), requested.label(), verification.subsystem(), false, true);
        }

        if (verification.outcome() == SoapQueryResult.Outcome.FOUND) {
            String foundEnvironment = environments.displayNameForSubsystem(verification.subsystem());
            return response(cmsRecord, ToolStatus.REGISTER_UNVERIFIED,
                    "Después del registro la transacción fue encontrada en " + foundEnvironment
                            + ". Se requiere revisión operativa.",
                    requested.label(), foundEnvironment, verification.subsystem(), false, false);
        }

        return response(cmsRecord, ToolStatus.REGISTER_UNVERIFIED,
                "No fue posible confirmar el resultado del registro. No lo reintente sin revisión operativa.",
                requested.label(), null, null, false, false);
    }

    private ToolResponse complete(String action, long started, CmsRecord cmsRecord, ToolResponse result) {
        long durationMs = (System.nanoTime() - started) / 1_000_000;
        auditLogger.operation(action, result.accessId(),
                cmsRecord == null ? result.pid() : cmsRecord.pid(),
                cmsRecord == null ? result.execId() : cmsRecord.execId(),
                result.requestedEnvironment(), result.status(), durationMs, metadata);
        return result;
    }

    private ToolResponse response(CmsRecord cmsRecord, ToolStatus status, String message,
                                  String requestedEnvironment, String foundEnvironment,
                                  String foundSubsystem, boolean canRegister,
                                  boolean registrationConfirmed) {
        return new ToolResponse(cmsRecord.accessId(), status, message, true,
                cmsRecord.interactionDate(), cmsRecord.externalId(), cmsRecord.pid(), cmsRecord.execId(),
                requestedEnvironment, foundEnvironment, foundSubsystem, canRegister,
                registrationConfirmed, metadata.correlationId());
    }

    private ToolResponse response(String accessId, ToolStatus status, String message,
                                  boolean cmsFound, CmsRecord cmsRecord,
                                  String requestedEnvironment, String foundEnvironment,
                                  String foundSubsystem, boolean canRegister,
                                  boolean registrationConfirmed) {
        return new ToolResponse(accessId, status, message, cmsFound,
                cmsRecord == null ? null : cmsRecord.interactionDate(),
                cmsRecord == null ? null : cmsRecord.externalId(),
                cmsRecord == null ? null : cmsRecord.pid(),
                cmsRecord == null ? null : cmsRecord.execId(),
                requestedEnvironment, foundEnvironment, foundSubsystem, canRegister,
                registrationConfirmed, metadata.correlationId());
    }

    private void logTechnicalDetail(String operation, String detail) {
        if (detail != null && !detail.isBlank()) {
            LOG.warnf("operation=%s detail=%s correlationId=%s",
                    operation, detail.replaceAll("[\\r\\n\\t]", " "), metadata.correlationId());
        }
    }

    private static String normalizeAccessId(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeEnvironment(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record CmsLookup(CmsRecord record, ToolResponse terminalResponse) {
        static CmsLookup found(CmsRecord record) {
            return new CmsLookup(record, null);
        }

        static CmsLookup terminal(ToolResponse response) {
            return new CmsLookup(null, response);
        }
    }

    private record CmsResendResult(boolean required, boolean successful) {
        static CmsResendResult notRequired() {
            return new CmsResendResult(false, true);
        }

        static CmsResendResult success() {
            return new CmsResendResult(true, true);
        }

        static CmsResendResult failure() {
            return new CmsResendResult(true, false);
        }

        boolean hasFailed() {
            return required && !successful;
        }
    }
}
