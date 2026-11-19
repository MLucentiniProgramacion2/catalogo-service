package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.CatalogRepository;
import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogUpdateNotice;
import ar.edu.um.programacion2.catalogo.sync.domain.model.ChangedEntities;
import ar.edu.um.programacion2.catalogo.sync.domain.model.IncrementalSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.model.VersionChanges;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.ApplyIncrementalSyncUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.CatalogChangesProvider;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.ChangesAccessDeniedException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.ChangesSourceUnavailableException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.InvalidChangesException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.ProcessedEventRepository;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CU-04: aplica en orden las versiones pendientes, cada una en su transacción.
 * <p>
 * Por cada versión {@code v}:
 * <ol>
 * <li>verifica que {@code changes:{v}} diga {@code v} (si no, el estado no se puede verificar);</li>
 * <li>lee de Redis el estado vigente de los IDs, <strong>afuera</strong> de la transacción, para que sea corta;</li>
 * <li>si falta alguno (flujo 3a), corresponde un snapshot;</li>
 * <li>abre la transacción: vuelve a leer {@code sync_status} y verifica que la local siga siendo {@code v - 1} (paso 5);
 * verifica las referencias contra la copia local (flujo 4a: la base nunca es la que corta por la FK); guarda
 * categorías, profesionales y horarios; avanza la versión, y en la última del lote registra el {@code eventId}.</li>
 * </ol>
 * <strong>Reintentos (DP-08).</strong> Un solo presupuesto para todo el lote ({@link RetryBackoff}: 3 reintentos con
 * 1, 2 y 4 s), para que no se multipliquen por versión. Un reintento repite <strong>solo el paso que falló</strong>: si
 * falla la transacción, no se vuelve a leer Redis. Se reintenta Redis no disponible y la base con una falla de
 * {@link DatabaseFailures}; una credencial rechazada o una falla de la base que no está ahí, no.
 * <p>
 * <strong>El commit dudoso.</strong> Si falla el commit de {@code v} pero la base sí lo confirmó, el reintento encuentra
 * la local en {@code v} y no en {@code v - 1}. Solo en un reintento después de una falla, eso cuenta como aplicada y se
 * sigue: reaplicar es idempotente. Cualquier otra diferencia es otro proceso que avanzó, y se termina sin cambios.
 * <p>
 * <strong>Plazo del lote ({@link IncrementalTimeBudget}).</strong> Se mide con el {@code Clock} desde que empieza. Después
 * del plazo no empieza otra versión ni otro reintento: se conservan las aplicadas y CU-03 sigue con un snapshot. Es lo que
 * le da una cota al peor caso.
 * <p>
 * Las transacciones son programáticas por lo mismo que en CU-02, y {@code Propagation.NEVER} también: Redis no puede
 * leerse dentro de una transacción ajena. Las fallas vuelven como resultado; el error se registra con
 * {@link SyncErrorRecorder}, con textos fijos (el detalle va al log).
 */
@Component
@Transactional(propagation = Propagation.NEVER)
@RequiredArgsConstructor
public class ApplyIncrementalSyncUseCaseImpl implements ApplyIncrementalSyncUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(ApplyIncrementalSyncUseCaseImpl.class);

    private final CatalogChangesProvider catalogChangesProvider;
    private final CatalogRepository catalogRepository;
    private final SyncStatusRepository syncStatusRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final RetryBackoff retryBackoff;
    private final SyncErrorRecorder syncErrorRecorder;
    private final IncrementalTimeBudget timeBudget;

    @Override
    public IncrementalSyncResult applyIncremental(SyncTrigger trigger, long localVersion, SortedMap<Long, VersionChanges> pending) {
        return new Batch(trigger, localVersion, pending, null).run();
    }

    @Override
    public IncrementalSyncResult applyIncremental(
        SyncTrigger trigger,
        long localVersion,
        SortedMap<Long, VersionChanges> pending,
        CatalogUpdateNotice notice
    ) {
        Objects.requireNonNull(notice, "Falta el aviso de catálogo");
        return new Batch(trigger, localVersion, pending, notice).run();
    }

    /** Lo que pasó adentro de la transacción de una versión. */
    private sealed interface Outcome {
        record Applied() implements Outcome {}

        record AlreadyApplied() implements Outcome {}

        record Preempted(Long found) implements Outcome {}

        record BrokenReferences(List<String> references) implements Outcome {}
    }

    /** Una falla ya clasificada de un paso. {@code description} se informa a los usuarios. */
    private record StepFailure(SyncErrorType type, String description, boolean retryable, boolean unverifiable, RuntimeException cause) {}

    /** El fin del lote, cuando un paso no se puede completar: sale del bucle con el resultado ya armado. */
    private static final class Stop extends RuntimeException {

        private final transient IncrementalSyncResult result;

        Stop(IncrementalSyncResult result) {
            super(null, null, false, false);
            this.result = result;
        }
    }

    /** El estado de un lote: la versión local, los reintentos usados y el plazo. */
    private final class Batch {

        private final SyncTrigger trigger;
        private final SortedMap<Long, VersionChanges> pending;
        private final CatalogUpdateNotice notice;
        private final long firstVersion;
        private final Instant deadline;
        private long localVersion;
        private int retries;

        Batch(SyncTrigger trigger, long localVersion, SortedMap<Long, VersionChanges> pending, CatalogUpdateNotice notice) {
            this.trigger = Objects.requireNonNull(trigger, "Falta el disparador de la sincronización");
            this.pending = Objects.requireNonNull(pending, "Faltan los cambios pendientes");
            this.notice = notice;
            this.localVersion = localVersion;
            this.firstVersion = localVersion + 1;
            this.deadline = clock.instant().plus(timeBudget.value());
        }

        IncrementalSyncResult run() {
            try {
                long lastVersion = pending.isEmpty() ? localVersion : pending.lastKey();
                for (long version = firstVersion; version <= lastVersion; version++) {
                    applyVersion(version, version == lastVersion);
                }
                LOG.info(
                    "Sincronización incremental ({}): versiones {} a {} aplicadas en {} intentos",
                    trigger,
                    firstVersion,
                    localVersion,
                    attempts()
                );
                return new IncrementalSyncResult.Completed(localVersion, attempts());
            } catch (Stop stop) {
                return stop.result;
            }
        }

        private void applyVersion(long version, boolean last) {
            checkDeadline(version);
            VersionChanges changes = pending.get(version);
            if (changes == null) {
                throw needsSnapshot("falta el cambio de la versión " + version);
            }
            if (changes.version() != version) {
                throw needsSnapshot("el cambio de la versión " + version + " dice que es la versión " + changes.version());
            }
            ChangedEntities entities = step(version, () -> catalogChangesProvider.readEntities(changes));
            List<String> missing = entities.missingFrom(changes);
            if (!missing.isEmpty()) {
                throw needsSnapshot("en la versión " + version + " no están en Redis " + String.join(", ", missing));
            }
            boolean[] failedBefore = { false };
            Outcome outcome = step(version, () -> {
                try {
                    return transactionTemplate.execute(tx -> applyInTransaction(version, entities, last, failedBefore[0], tx));
                } catch (RuntimeException e) {
                    failedBefore[0] = true;
                    throw e;
                }
            });
            switch (outcome) {
                case Outcome.Applied applied -> LOG.debug("Sincronización incremental ({}): versión {} aplicada", trigger, version);
                case Outcome.AlreadyApplied already -> LOG.warn(
                    "Sincronización incremental ({}): la versión {} ya estaba confirmada después de un commit dudoso; se sigue",
                    trigger,
                    version
                );
                case Outcome.Preempted preempted -> {
                    LOG.warn(
                        "Sincronización incremental ({}): se esperaba la versión local {} y había {}; otro proceso la cambió, se termina sin cambios",
                        trigger,
                        version - 1,
                        preempted.found()
                    );
                    throw new Stop(new IncrementalSyncResult.Preempted(localVersion, preempted.found(), attempts()));
                }
                case Outcome.BrokenReferences broken -> throw needsSnapshot(
                    "la versión " + version + " tiene referencias inexistentes: " + String.join("; ", broken.references())
                );
            }
            localVersion = version;
        }

        /** Adentro de la transacción: si algo falla, no queda ni la versión, ni sus entidades, ni el {@code eventId}. */
        private Outcome applyInTransaction(
            long version,
            ChangedEntities entities,
            boolean last,
            boolean failedBefore,
            TransactionStatus tx
        ) {
            SyncStatus status = syncStatusRepository.load();
            Long found = status.getLocalVersion();
            if (failedBefore && Objects.equals(found, version)) {
                return new Outcome.AlreadyApplied();
            }
            if (!Objects.equals(found, version - 1)) {
                tx.setRollbackOnly();
                return new Outcome.Preempted(found);
            }
            List<String> broken = entities.brokenReferences(
                catalogRepository.findExistingCategoryIds(entities.categoryIdsOutside()),
                catalogRepository.findExistingProfessionalIds(entities.professionalIdsOutside())
            );
            if (!broken.isEmpty()) {
                tx.setRollbackOnly();
                return new Outcome.BrokenReferences(broken);
            }
            catalogRepository.saveAll(entities.categories(), entities.professionals(), entities.schedules());
            Instant now = clock.instant();
            status.recordIncrementalApplied(version, trigger, now);
            syncStatusRepository.save(status);
            if (last && notice != null) {
                processedEventRepository.registerIfAbsent(notice.processedAt(now));
            }
            return new Outcome.Applied();
        }

        /** Corre un paso con el presupuesto de reintentos del lote. Repite solo este paso. */
        private <T> T step(long version, Supplier<T> action) {
            while (true) {
                StepFailure failure;
                try {
                    return action.get();
                } catch (RuntimeException e) {
                    failure = classify(e, version);
                }
                if (failure.unverifiable()) {
                    throw needsSnapshot(failure.description());
                }
                if (!failure.retryable() || retries == retryBackoff.maxRetries()) {
                    throw fail(failure);
                }
                checkDeadline(version);
                LOG.warn(
                    "Sincronización incremental ({}): falló un paso de la versión {} ({}). Reintento {} de {} en {} ms",
                    trigger,
                    version,
                    failure.cause() != null ? failure.cause().toString() : failure.type(),
                    retries + 1,
                    retryBackoff.maxRetries(),
                    retryBackoff.delayBeforeRetry(retries + 1).toMillis()
                );
                try {
                    retryBackoff.pauseBeforeRetry(retries + 1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw fail(
                        new StepFailure(
                            SyncErrorType.INCREMENTAL_FAILED,
                            "La sincronización incremental se interrumpió mientras esperaba para reintentar la versión " + version,
                            false,
                            false,
                            null
                        )
                    );
                }
                retries++;
            }
        }

        private StepFailure classify(RuntimeException e, long version) {
            String where = " (versión " + version + ")";
            return switch (e) {
                case ChangesSourceUnavailableException unavailable -> new StepFailure(
                    SyncErrorType.REDIS_UNAVAILABLE,
                    unavailable.getMessage() + where,
                    true,
                    false,
                    null
                );
                case ChangesAccessDeniedException denied -> new StepFailure(
                    SyncErrorType.INVALID_CREDENTIALS,
                    denied.getMessage() + where,
                    false,
                    false,
                    null
                );
                case InvalidChangesException invalid -> new StepFailure(null, invalid.getMessage() + where, false, true, null);
                default -> {
                    if (DatabaseFailures.isTransient(e)) {
                        yield new StepFailure(
                            SyncErrorType.DATABASE_UNAVAILABLE,
                            "La base de datos local no respondió al aplicar la versión " + version,
                            true,
                            false,
                            e
                        );
                    }
                    String what = e instanceof DataAccessException || e instanceof TransactionException
                        ? "La base de datos local rechazó la versión "
                        : "Error inesperado al aplicar la versión ";
                    yield new StepFailure(SyncErrorType.INCREMENTAL_FAILED, what + version, false, false, e);
                }
            };
        }

        private void checkDeadline(long version) {
            if (clock.instant().isAfter(deadline)) {
                long applied = localVersion - firstVersion + 1;
                LOG.warn(
                    "Sincronización incremental ({}): venció el plazo de {} con {} {} aplicadas; sigue un snapshot desde la versión {}",
                    trigger,
                    timeBudget.value(),
                    applied,
                    applied == 1 ? "versión" : "versiones",
                    version
                );
                throw new Stop(
                    new IncrementalSyncResult.NeedsSnapshot(
                        localVersion,
                        "venció el plazo del lote con " + applied + " versiones aplicadas",
                        attempts()
                    )
                );
            }
        }

        private Stop needsSnapshot(String reason) {
            LOG.warn(
                "Sincronización incremental ({}): el estado no se puede verificar ({}); se conservan las versiones hasta la {} y sigue un snapshot",
                trigger,
                reason,
                localVersion
            );
            return new Stop(new IncrementalSyncResult.NeedsSnapshot(localVersion, reason, attempts()));
        }

        private Stop fail(StepFailure failure) {
            String kept = localVersion >= firstVersion
                ? ": se conservan las versiones hasta la " + localVersion
                : ": no se aplicó ninguna versión";
            String description = failure.retryable()
                ? failure.description() + " después de " + attempts() + (attempts() == 1 ? " intento" : " intentos") + kept
                : failure.description() + kept;
            if (failure.cause() == null) {
                LOG.error("Sincronización incremental ({}) fallida: {} [{}]", trigger, description, failure.type());
            } else {
                LOG.error("Sincronización incremental ({}) fallida: {} [{}]", trigger, description, failure.type(), failure.cause());
            }
            syncErrorRecorder.record(failure.type(), description);
            return new Stop(new IncrementalSyncResult.Failed(localVersion, failure.type(), description, attempts()));
        }

        private int attempts() {
            return retries + 1;
        }
    }
}
