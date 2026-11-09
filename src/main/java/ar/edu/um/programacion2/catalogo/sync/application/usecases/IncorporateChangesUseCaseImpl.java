package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogUpdateNotice;
import ar.edu.um.programacion2.catalogo.sync.domain.model.FullSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.IncorporationResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.IncrementalSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.PublishedVersions;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncPath;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.model.VersionChanges;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.ApplyIncrementalSyncUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.ExecuteFullSyncUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.IncorporateChangesUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.CatalogChangesProvider;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.ChangesAccessDeniedException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.ChangesSourceUnavailableException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.InvalidChangesException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.ProcessedEventRepository;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.SortedMap;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CU-03, pasos 6 a 10, con el disparador como parámetro. El orden:
 * <ol>
 * <li><strong>Sin versión local, snapshot directo</strong> (CU-02). La regla del camino ya lo decide sin Redis, así que
 * un Redis caído no impide armar la primera copia por REST.</li>
 * <li>Lee {@code catedra:sync:metadata}. <strong>La versión objetivo es {@code currentVersion} de Redis</strong>: el
 * {@code newVersion} del aviso es solo un aviso.</li>
 * <li>Escribe {@code remoteVersion} en una transacción corta, con {@code load} y {@code save} en la misma (la condición
 * de {@code @DynamicUpdate}). Si la copia está al día y lo disparó un aviso, esa misma transacción registra el
 * {@code eventId} (flujo 7a).</li>
 * <li>Elige el camino (contrato §14.2 y §18.2): snapshot si la local es <strong>menor</strong> que
 * {@code oldestAvailableVersion} o si falta algún {@code changes:{v}} entre la local + 1 y la objetivo; si no,
 * incremental (CU-04). Con la local en 3 y {@code oldest} en 4 es snapshot aunque exista {@code changes:4}: falta la
 * versión que conecta.</li>
 * <li>Si CU-04 no puede seguir (estado no verificable o plazo vencido), se conservan las versiones aplicadas y se sigue
 * con CU-02, con el mismo aviso: el {@code eventId} queda en la transacción del snapshot (flujo 8b).</li>
 * </ol>
 * <strong>Reintentos de la lectura previa (DP-08).</strong> Metadata, {@code remoteVersion} y cambios comparten un
 * presupuesto de 3 reintentos, y <strong>un reintento repite solo el paso que falló</strong>: si falla escribir
 * {@code remoteVersion}, la metadata no se vuelve a leer. CU-04 y CU-02 tienen el suyo cada uno.
 * <p>
 * Metadata inválida (falta un campo o no es un número) es un estado no verificable: va snapshot y {@code remoteVersion}
 * no se escribe. Redis no disponible o con la credencial rechazada termina con fallo, sin snapshot: lo primero es
 * transitorio y lo segundo es configuración.
 * <p>
 * Si el aviso anuncia una versión mayor que la publicada en Redis (el aviso llegó antes de que Redis se actualizara), se
 * llega hasta la publicada, con un WARN, y el {@code eventId} se registra igual: es lo mismo que el snapshot anterior a
 * {@code newVersion} del #31. La versión que falta llega con el próximo aviso o con la comprobación periódica.
 */
@Component
@Transactional(propagation = Propagation.NEVER)
@RequiredArgsConstructor
public class IncorporateChangesUseCaseImpl implements IncorporateChangesUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(IncorporateChangesUseCaseImpl.class);

    private final CatalogChangesProvider catalogChangesProvider;
    private final SyncStatusRepository syncStatusRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ApplyIncrementalSyncUseCase applyIncrementalSyncUseCase;
    private final ExecuteFullSyncUseCase executeFullSyncUseCase;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final RetryBackoff retryBackoff;
    private final SyncErrorRecorder syncErrorRecorder;

    @Override
    public IncorporationResult incorporate(SyncTrigger trigger, Long localVersion) {
        return new Run(trigger, localVersion, null).run();
    }

    @Override
    public IncorporationResult incorporate(SyncTrigger trigger, Long localVersion, CatalogUpdateNotice notice) {
        Objects.requireNonNull(notice, "Falta el aviso de catálogo");
        return new Run(trigger, localVersion, notice).run();
    }

    /** Una falla de un paso de la lectura previa que termina la ejecución con el resultado ya armado. */
    private static final class Stop extends RuntimeException {

        private final transient IncorporationResult result;

        Stop(IncorporationResult result) {
            super(null, null, false, false);
            this.result = result;
        }
    }

    /** Lo publicado no se puede verificar: sigue un snapshot. */
    private static final class Unverifiable extends RuntimeException {

        Unverifiable(String reason) {
            super(reason, null, false, false);
        }
    }

    private final class Run {

        private final SyncTrigger trigger;
        private final Long before;
        private final CatalogUpdateNotice notice;
        private int retries;

        Run(SyncTrigger trigger, Long localVersion, CatalogUpdateNotice notice) {
            this.trigger = Objects.requireNonNull(trigger, "Falta el disparador de la sincronización");
            this.before = localVersion;
            this.notice = notice;
        }

        IncorporationResult run() {
            if (before == null) {
                LOG.info("Sincronización ({}): no hay versión local; se sincroniza con un snapshot (CU-02)", trigger);
                return snapshot(null, 1);
            }
            try {
                return incorporate(before);
            } catch (Stop stop) {
                return stop.result;
            } catch (Unverifiable unverifiable) {
                LOG.warn(
                    "Sincronización ({}): el estado publicado no se puede verificar ({}); sigue un snapshot (CU-02)",
                    trigger,
                    unverifiable.getMessage()
                );
                return snapshot(before, attempts());
            }
        }

        private IncorporationResult incorporate(long local) {
            PublishedVersions published = step("la metadata", catalogChangesProvider::readPublishedVersions);
            long target = published.currentVersion();
            boolean upToDate = local >= target;
            step("la versión remota", () -> {
                transactionTemplate.executeWithoutResult(tx -> recordRemote(target, upToDate));
                return true;
            });
            warnIfNoticeIsAhead(target);
            if (upToDate) {
                logUpToDate(local, target);
                return new IncorporationResult.UpToDate(local, target, attempts());
            }
            if (local < published.oldestAvailableVersion()) {
                LOG.info(
                    "Sincronización ({}): la versión local {} es anterior a la más vieja disponible {}; sigue un snapshot (CU-02)",
                    trigger,
                    local,
                    published.oldestAvailableVersion()
                );
                return snapshot(local, attempts());
            }
            SortedMap<Long, VersionChanges> pending = step("los cambios", () -> catalogChangesProvider.readChanges(local + 1, target));
            long expected = target - local;
            if (pending.size() != expected) {
                LOG.info(
                    "Sincronización ({}): faltan {} de los {} cambios entre la versión {} y la {}; sigue un snapshot (CU-02)",
                    trigger,
                    expected - pending.size(),
                    expected,
                    local + 1,
                    target
                );
                return snapshot(local, attempts());
            }
            return incremental(local, pending);
        }

        private void recordRemote(long remoteVersion, boolean upToDate) {
            Instant now = clock.instant();
            SyncStatus status = syncStatusRepository.load();
            status.recordRemoteVersion(remoteVersion, now);
            syncStatusRepository.save(status);
            if (upToDate && notice != null) {
                processedEventRepository.registerIfAbsent(notice.processedAt(now));
            }
        }

        /**
         * La comprobación periódica encuentra la copia al día casi siempre, cada pocos minutos: en INFO llenaría el log, así
         * que va en DEBUG. Los demás disparadores son de a uno (un aviso, un arranque, un pedido) y quedan en INFO.
         */
        private void logUpToDate(long local, long target) {
            String message = "Sincronización ({}): la versión local {} ya está al día con la publicada {}";
            if (trigger == SyncTrigger.PERIODIC) {
                LOG.debug(message, trigger, local, target);
            } else {
                LOG.info(message, trigger, local, target);
            }
        }

        private void warnIfNoticeIsAhead(long target) {
            if (notice != null && notice.newVersion() > target) {
                LOG.warn(
                    "Sincronización ({}): el aviso {} anuncia la versión {}, pero Redis publica la {}; se llega hasta la publicada",
                    trigger,
                    notice.eventId(),
                    notice.newVersion(),
                    target
                );
            }
        }

        private IncorporationResult incremental(long local, SortedMap<Long, VersionChanges> pending) {
            IncrementalSyncResult result = notice == null
                ? applyIncrementalSyncUseCase.applyIncremental(trigger, local, pending)
                : applyIncrementalSyncUseCase.applyIncremental(trigger, local, pending, notice);
            int attempts = attempts() + result.attempts() - 1;
            return switch (result) {
                case IncrementalSyncResult.Completed completed -> new IncorporationResult.Applied(
                    before,
                    completed.localVersion(),
                    SyncPath.INCREMENTAL,
                    attempts
                );
                case IncrementalSyncResult.NeedsSnapshot needsSnapshot -> snapshot(needsSnapshot.localVersion(), attempts);
                case IncrementalSyncResult.Preempted preempted -> {
                    // Otro proceso avanzó la versión: lo que se publicó ya lo aplicó él. Este no cambió nada.
                    long found = preempted.foundVersion() != null ? preempted.foundVersion() : preempted.localVersion();
                    yield new IncorporationResult.UpToDate(found, pending.lastKey(), attempts);
                }
                case IncrementalSyncResult.Failed failed -> new IncorporationResult.Failed(
                    before,
                    failed.localVersion(),
                    failed.errorType(),
                    failed.description(),
                    attempts
                );
            };
        }

        /** CU-02 con el mismo aviso, si lo hay. {@code current} es la versión local en este momento. */
        private IncorporationResult snapshot(Long current, int attemptsSoFar) {
            FullSyncResult result = notice == null
                ? executeFullSyncUseCase.executeFullSync(trigger)
                : executeFullSyncUseCase.executeFullSync(trigger, notice);
            int attempts = attemptsSoFar + result.attempts() - 1;
            return switch (result) {
                case FullSyncResult.Applied applied -> new IncorporationResult.Applied(
                    before,
                    applied.snapshotVersion(),
                    SyncPath.SNAPSHOT,
                    attempts
                );
                case FullSyncResult.Failed failed -> new IncorporationResult.Failed(
                    before,
                    current,
                    failed.errorType(),
                    failed.description(),
                    attempts
                );
            };
        }

        /** Un paso de la lectura previa, con su presupuesto de reintentos: repite solo este paso. */
        private <T> T step(String what, Supplier<T> action) {
            while (true) {
                SyncErrorType type;
                String description;
                RuntimeException cause = null;
                try {
                    return action.get();
                } catch (ChangesSourceUnavailableException e) {
                    type = SyncErrorType.REDIS_UNAVAILABLE;
                    description = e.getMessage();
                } catch (ChangesAccessDeniedException e) {
                    throw fail(SyncErrorType.INVALID_CREDENTIALS, e.getMessage(), null);
                } catch (InvalidChangesException e) {
                    throw new Unverifiable(e.getMessage());
                } catch (RuntimeException e) {
                    if (!DatabaseFailures.isTransient(e)) {
                        throw fail(SyncErrorType.INCREMENTAL_FAILED, "Error inesperado al registrar " + what, e);
                    }
                    type = SyncErrorType.DATABASE_UNAVAILABLE;
                    description = "La base de datos local no respondió al registrar " + what;
                    cause = e;
                }
                if (retries == retryBackoff.maxRetries()) {
                    throw fail(type, description + " después de " + attempts() + " intentos", cause);
                }
                LOG.warn(
                    "Sincronización ({}): falló la lectura de {} ({}). Reintento {} de {} en {} ms",
                    trigger,
                    what,
                    cause != null ? cause.toString() : description,
                    retries + 1,
                    retryBackoff.maxRetries(),
                    retryBackoff.delayBeforeRetry(retries + 1).toMillis()
                );
                try {
                    retryBackoff.pauseBeforeRetry(retries + 1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw fail(SyncErrorType.INCREMENTAL_FAILED, "La sincronización se interrumpió mientras esperaba para reintentar", null);
                }
                retries++;
            }
        }

        private Stop fail(SyncErrorType type, String reason, RuntimeException cause) {
            String description = reason + ": no se aplicó ningún cambio";
            if (cause == null) {
                LOG.error("Sincronización ({}) fallida: {} [{}]", trigger, description, type);
            } else {
                LOG.error("Sincronización ({}) fallida: {} [{}]", trigger, description, type, cause);
            }
            syncErrorRecorder.record(type, description);
            return new Stop(new IncorporationResult.Failed(before, before, type, description, attempts()));
        }

        private int attempts() {
            return retries + 1;
        }
    }
}
