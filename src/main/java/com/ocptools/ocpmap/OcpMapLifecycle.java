package com.ocptools.ocpmap;

import com.ocptools.config.ToolsConfig;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.flyway.FlywayDataSource;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.flywaydb.core.Flyway;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class OcpMapLifecycle {
    private static final Logger LOG = Logger.getLogger(OcpMapLifecycle.class);

    @Inject
    @FlywayDataSource("inventory")
    Flyway flyway;

    @Inject
    OcpMapRepository repository;

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    ToolsConfig config;

    @Inject
    OcpNamespacePolicy namespacePolicy;

    private final AtomicInteger namespaceIndex = new AtomicInteger();
    private volatile boolean databaseReady;
    private volatile Instant nextDatabaseAttempt = Instant.EPOCH;

    @Scheduled(
            every = "${ocp-tools.ocp-map.scan-interval:5s}",
            delayed = "${ocp-tools.ocp-map.scan-initial-delay:3s}",
            concurrentExecution = ConcurrentExecution.SKIP
    )
    void tick() {
        if (!ensureDatabase()) {
            return;
        }

        List<String> namespaces = namespacePolicy.scanned();
        if (namespaces.isEmpty()) {
            LOG.warn("tool=ocp-map action=scan result=no-namespaces");
            return;
        }

        int index = Math.floorMod(namespaceIndex.getAndIncrement(), namespaces.size());
        scanNamespace(namespaces.get(index));
    }

    public boolean isDatabaseReady() {
        return databaseReady;
    }

    private boolean ensureDatabase() {
        if (databaseReady) {
            return true;
        }
        Instant now = Instant.now();
        if (now.isBefore(nextDatabaseAttempt)) {
            return false;
        }
        try {
            flyway.migrate();
            repository.registerNamespaces(namespacePolicy.scanned());
            databaseReady = true;
            LOG.info("tool=ocp-map action=database-init result=success");
            return true;
        } catch (RuntimeException exception) {
            databaseReady = false;
            nextDatabaseAttempt = now.plus(config.ocpMap().databaseRetry());
            LOG.warnf("tool=ocp-map action=database-init result=unavailable retryAt=%s error=%s",
                    nextDatabaseAttempt, safe(exception.getMessage()));
            return false;
        }
    }

    private void scanNamespace(String namespace) {
        String correlationId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now();
        long startedNanos = System.nanoTime();
        try {
            List<ObservedDeployment> deployments = kubernetesClient.apps().deployments()
                    .inNamespace(namespace)
                    .list()
                    .getItems()
                    .stream()
                    .map(OcpStatePolicy::from)
                    .toList();
            repository.applySuccessfulScan(namespace, deployments, startedAt);
            LOG.infof("tool=ocp-map action=LIST_DEPLOYMENTS correlationId=%s namespace=%s result=success deploymentsFound=%d durationMs=%d startedAt=%s finishedAt=%s",
                    correlationId, namespace, deployments.size(), elapsedMillis(startedNanos), startedAt, Instant.now());
        } catch (OcpMapUnavailableException exception) {
            databaseReady = false;
            nextDatabaseAttempt = Instant.now().plus(config.ocpMap().databaseRetry());
            LOG.warnf("tool=ocp-map action=LIST_DEPLOYMENTS correlationId=%s namespace=%s result=database-unavailable durationMs=%d startedAt=%s finishedAt=%s",
                    correlationId, namespace, elapsedMillis(startedNanos), startedAt, Instant.now());
        } catch (RuntimeException exception) {
            String error = safe(exception.getMessage());
            try {
                repository.recordScanFailure(namespace, startedAt, error);
            } catch (OcpMapUnavailableException databaseException) {
                databaseReady = false;
                nextDatabaseAttempt = Instant.now().plus(config.ocpMap().databaseRetry());
            }
            LOG.warnf("tool=ocp-map action=LIST_DEPLOYMENTS correlationId=%s namespace=%s result=error durationMs=%d startedAt=%s finishedAt=%s error=%s",
                    correlationId, namespace, elapsedMillis(startedNanos), startedAt, Instant.now(), error);
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[\\r\\n\\t]", "_");
    }
}
