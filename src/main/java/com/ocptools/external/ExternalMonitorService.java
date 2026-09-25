package com.ocptools.external;

import com.ocptools.config.ToolsConfig;
import com.ocptools.external.ExternalModels.RunView;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ApplicationScoped
public class ExternalMonitorService {
    private static final Logger LOG = Logger.getLogger(ExternalMonitorService.class);

    @Inject
    ToolsConfig config;

    @Inject
    ExternalRepository repository;

    @Inject
    ExternalProbeExecutor executor;

    private volatile ExecutorService pool;

    @PostConstruct
    void reportConfiguration() {
        LOG.infof("tool=external-monitor action=configure enabled=%s tlsVerify=%s intervalSeconds=%d initialDelaySeconds=%d parallelism=%d",
                config.externalMonitor().enabled(), config.externalMonitor().tlsVerify(), config.externalMonitor().interval().toSeconds(),
                config.externalMonitor().initialDelay().toSeconds(), config.externalMonitor().parallelism());
    }

    @Scheduled(every = "${ocp-tools.external-monitor.interval:10m}",
            delayed = "${ocp-tools.external-monitor.initial-delay:30s}",
            concurrentExecution = ConcurrentExecution.SKIP)
    void scheduledRun() {
        if (!config.externalMonitor().enabled()) return;
        long started = System.nanoTime();
        try {
            List<Callable<RunView>> tasks = repository.activeServiceDefinitions().stream()
                    .<Callable<RunView>>map(service -> () -> execute(service, false, "SCHEDULER"))
                    .toList();
            LOG.infof("tool=external-monitor action=scheduled result=started services=%d parallelism=%d",
                    tasks.size(), config.externalMonitor().parallelism());
            executorPool().invokeAll(tasks);
            LOG.infof("tool=external-monitor action=scheduled result=completed services=%d durationMs=%d",
                    tasks.size(), (System.nanoTime() - started) / 1_000_000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOG.warn("tool=external-monitor action=scheduled result=interrupted");
        } catch (ExternalUnavailableException exception) {
            LOG.warn("tool=external-monitor action=scheduled result=database-unavailable");
        }
    }

    public RunView manualRun(long serviceId) {
        return execute(repository.serviceDefinition(serviceId), true, "MANUAL");
    }

    private RunView execute(ExternalRepository.ServiceDefinition service, boolean manual, String source) {
        Instant started = Instant.now();
        long runId = repository.startRun(service.id(), manual, started, source);
        try {
            ExternalProbeExecutor.Execution execution = executor.execute(service);
            RunView run = repository.finishRun(runId, service.id(), manual, started,
                    execution.status(), execution.results());
            LOG.infof("tool=external-monitor action=probe serviceId=%d manual=%s result=%s durationMs=%d",
                    service.id(), manual, run.status(), run.durationMs());
            return run;
        } catch (RuntimeException exception) {
            var failure = new ExternalModels.ProbeResultView(null, "Ejecución general", "INTERNAL", true,
                    "DOWN", "INTERNAL", safe(exception), 0, null, Instant.now());
            RunView run = repository.finishRun(runId, service.id(), manual, started, "ERROR", List.of(failure));
            LOG.warnf("tool=external-monitor action=probe serviceId=%d manual=%s result=ERROR durationMs=%d type=%s",
                    service.id(), manual, run.durationMs(), exception.getClass().getSimpleName());
            return run;
        }
    }

    private ExecutorService executorPool() {
        ExecutorService current = pool;
        if (current == null) {
            synchronized (this) {
                if (pool == null) pool = Executors.newFixedThreadPool(Math.max(1, config.externalMonitor().parallelism()));
                current = pool;
            }
        }
        return current;
    }

    @PreDestroy
    void close() {
        if (pool != null) pool.shutdownNow();
    }

    private static String safe(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName()
                : message.replaceAll("[\\r\\n\\t]", " ");
    }
}
