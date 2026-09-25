package com.ocptools.service;

import com.ocptools.api.ToolRequest;
import com.ocptools.api.ToolResponse;
import com.ocptools.cms.CmsPostRegisterDelay;
import com.ocptools.cms.CmsRepository;
import com.ocptools.common.AuditLogger;
import com.ocptools.common.CmsDataException;
import com.ocptools.common.RequestMetadata;
import com.ocptools.config.ToolsConfig;
import com.ocptools.domain.CmsRecord;
import com.ocptools.domain.EnvironmentTarget;
import com.ocptools.domain.SoapQueryResult;
import com.ocptools.domain.SoapRegisterResult;
import com.ocptools.domain.ToolStatus;
import com.ocptools.environment.EnvironmentCatalog;
import com.ocptools.soap.EsbSoapClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CmsToolServiceRegisterTest {
    private static final CmsRecord CMS_RECORD = new CmsRecord(
            "2026-08-26 10:00:00", "1400068628", "external", "pid", "exec");
    private static final ToolRequest REQUEST = new ToolRequest("1400068628", "PMX3");

    @Test
    void successfulCreateWaitsUpdatesEveryMatchingCmsRowAndThenVerifies() {
        Fixture fixture = fixture(SoapRegisterResult.success(), 3, false,
                SoapQueryResult.notFound(), SoapQueryResult.found("DIVPMX2"));

        ToolResponse response = fixture.service.register(REQUEST);

        assertEquals(ToolStatus.REGISTER_SUCCESS, response.status());
        assertTrue(response.registrationConfirmed());
        assertFalse(response.message().contains("No se pudo reenviar desde CMS"));
        assertEquals(1, fixture.repository.updateCalls);
        assertEquals("1400068628", fixture.repository.updatedAccessId);
        assertEquals(List.of("lookup", "query", "create", "delay", "update", "query"),
                fixture.events);
    }

    @Test
    void reportsTheAgreedWarningWithoutHidingAConfirmedRegistration() {
        Fixture fixture = fixture(SoapRegisterResult.success(), 0, false,
                SoapQueryResult.notFound(), SoapQueryResult.found("DIVPMX2"));

        ToolResponse response = fixture.service.register(REQUEST);

        assertEquals(ToolStatus.REGISTER_SUCCESS, response.status());
        assertTrue(response.registrationConfirmed());
        assertTrue(response.message().contains("No se pudo reenviar desde CMS"));
        assertEquals(1, fixture.repository.updateCalls);
    }

    @Test
    void reportsTheAgreedWarningWhenTheCmsUpdateFails() {
        Fixture fixture = fixture(SoapRegisterResult.success(), 0, true,
                SoapQueryResult.notFound(), SoapQueryResult.found("DIVPMX2"));

        ToolResponse response = fixture.service.register(REQUEST);

        assertEquals(ToolStatus.REGISTER_SUCCESS, response.status());
        assertTrue(response.registrationConfirmed());
        assertTrue(response.message().contains("No se pudo reenviar desde CMS"));
    }

    @Test
    void timeoutUpdatesCmsOnlyAfterQueryConfirmsTheRequestedEnvironment() {
        Fixture fixture = fixture(SoapRegisterResult.timeout("timeout"), 2, false,
                SoapQueryResult.notFound(), SoapQueryResult.found("DIVPMX2"));

        ToolResponse response = fixture.service.register(REQUEST);

        assertEquals(ToolStatus.REGISTER_SUCCESS, response.status());
        assertTrue(response.registrationConfirmed());
        assertEquals(1, fixture.repository.updateCalls);
        assertEquals(List.of("lookup", "query", "create", "delay", "query", "update"),
                fixture.events);
    }

    @Test
    void timeoutDoesNotUpdateCmsWhenRegistrationCannotBeConfirmed() {
        Fixture fixture = fixture(SoapRegisterResult.timeout("timeout"), 2, false,
                SoapQueryResult.notFound(), SoapQueryResult.timeout("timeout"));

        ToolResponse response = fixture.service.register(REQUEST);

        assertEquals(ToolStatus.REGISTER_UNVERIFIED, response.status());
        assertFalse(response.registrationConfirmed());
        assertEquals(0, fixture.repository.updateCalls);
        assertFalse(response.message().contains("No se pudo reenviar desde CMS"));
    }

    @Test
    void failedCreateDoesNotWaitUpdateOrRunTheFinalVerification() {
        Fixture fixture = fixture(SoapRegisterResult.failed("failed"), 2, false,
                SoapQueryResult.notFound());

        ToolResponse response = fixture.service.register(REQUEST);

        assertEquals(ToolStatus.REGISTER_FAILED, response.status());
        assertEquals(0, fixture.repository.updateCalls);
        assertEquals(0, fixture.delay.calls);
        assertEquals(List.of("lookup", "query", "create"), fixture.events);
    }

    private static Fixture fixture(SoapRegisterResult registerResult, int updatedRows,
                                   boolean updateFails, SoapQueryResult... queryResults) {
        List<String> events = new ArrayList<>();
        StubCmsRepository repository = new StubCmsRepository(events, updatedRows, updateFails);
        StubSoapClient soapClient = new StubSoapClient(events, registerResult, queryResults);
        StubDelay delay = new StubDelay(events);

        CmsToolService service = new CmsToolService();
        service.cmsRepository = repository;
        service.postRegisterDelay = delay;
        service.soapClient = soapClient;
        service.environments = new EnvironmentCatalog(environmentConfig());
        service.metadata = new RequestMetadata();
        service.metadata.correlationId("test-correlation");
        service.metadata.user("test-user");
        service.auditLogger = new AuditLogger();
        return new Fixture(service, repository, delay, events);
    }

    private static ToolsConfig environmentConfig() {
        return new ToolsConfig() {
            @Override
            public Soap soap() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Timeouts timeouts() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Cms cms() {
                return () -> Duration.ZERO;
            }

            @Override
            public TcpCheck tcpCheck() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Environments environments() {
                return new Environments() {
                    @Override
                    public String pmx1() {
                        return "DIVPMX1";
                    }

                    @Override
                    public String pmx3() {
                        return "DIVPMX2";
                    }

                    @Override
                    public String pmx4() {
                        return "DIV01";
                    }
                };
            }

            @Override
            public OcpMap ocpMap() {
                throw new UnsupportedOperationException();
            }

            @Override
            public ExternalMonitor externalMonitor() {
                throw new UnsupportedOperationException();
            }
        };
    }

    private record Fixture(CmsToolService service, StubCmsRepository repository,
                           StubDelay delay, List<String> events) {
    }

    private static final class StubCmsRepository extends CmsRepository {
        private final List<String> events;
        private final int updatedRows;
        private final boolean updateFails;
        private int updateCalls;
        private String updatedAccessId;

        private StubCmsRepository(List<String> events, int updatedRows, boolean updateFails) {
            this.events = events;
            this.updatedRows = updatedRows;
            this.updateFails = updateFails;
        }

        @Override
        public Optional<CmsRecord> findLatest(String accessId) {
            events.add("lookup");
            return Optional.of(CMS_RECORD);
        }

        @Override
        public int markForResend(String accessId) {
            events.add("update");
            updateCalls++;
            updatedAccessId = accessId;
            if (updateFails) {
                throw new CmsDataException("test failure", new IllegalStateException("test"));
            }
            return updatedRows;
        }
    }

    private static final class StubSoapClient extends EsbSoapClient {
        private final List<String> events;
        private final SoapRegisterResult registerResult;
        private final Deque<SoapQueryResult> queryResults;

        private StubSoapClient(List<String> events, SoapRegisterResult registerResult,
                               SoapQueryResult... queryResults) {
            this.events = events;
            this.registerResult = registerResult;
            this.queryResults = new ArrayDeque<>(List.of(queryResults));
        }

        @Override
        public SoapQueryResult query(CmsRecord cmsRecord) {
            events.add("query");
            return queryResults.removeFirst();
        }

        @Override
        public SoapRegisterResult register(CmsRecord cmsRecord, EnvironmentTarget environment) {
            events.add("create");
            return registerResult;
        }
    }

    private static final class StubDelay extends CmsPostRegisterDelay {
        private final List<String> events;
        private int calls;

        private StubDelay(List<String> events) {
            this.events = events;
        }

        @Override
        public boolean await() {
            events.add("delay");
            calls++;
            return true;
        }
    }
}
