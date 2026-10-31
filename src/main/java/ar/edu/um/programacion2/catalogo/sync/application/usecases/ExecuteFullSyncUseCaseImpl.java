package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.CatalogRepository;
import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogSnapshot;
import ar.edu.um.programacion2.catalogo.sync.domain.model.FullSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.ExecuteFullSyncUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.CatalogSnapshotProvider;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.InvalidSnapshotException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SnapshotAccessDeniedException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SnapshotRetrievalException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SnapshotSourceUnavailableException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CU-02: sincronización completa por snapshot.
 * <p>
 * Cada intento:
 * <ol>
 * <li>pide el snapshot <strong>fuera de toda transacción</strong>: el pedido puede tardar hasta connect + read timeout
 * (ADR 0004) y no tiene sentido retener una conexión de la base mientras tanto;</li>
 * <li>valida las referencias, todavía sin transacción: si hay alguna rota, no se toca la base;</li>
 * <li>en <strong>una</strong> transacción reemplaza la copia local y guarda {@code snapshotVersion} (§6.1, contrato §7).</li>
 * </ol>
 * Si el intento falla de forma transitoria (cátedra no disponible o base local, ver {@link DatabaseFailures}), se
 * reintenta entero, por fuera de la transacción, con las esperas de {@link RetryBackoff} (DP-08). Reintentar adentro no
 * serviría: una transacción en la que falló algo queda marcada para deshacerse.
 * <p>
 * Si falla del todo, el error se registra en una transacción <strong>nueva</strong>, después del rollback de la anterior
 * (explicación del #21, sección 7). La descripción se informa a los usuarios (CU-09): se arma con el mensaje de las
 * excepciones del puerto, que por contrato no llevan credenciales, o solo con el nombre de la excepción de la base, cuyo
 * mensaje puede traer SQL. Nunca con la causa ni con el stack.
 * <p>
 * Las transacciones son programáticas ({@link TransactionTemplate}) porque un mismo método necesita una parte sin
 * transacción, una transacción que se repite y otra distinta para el error. Con {@code @Transactional} en métodos de
 * esta clase, las llamadas internas no pasarían por el proxy y no abrirían nada.
 * <p>
 * {@code Propagation.NEVER}: si quien llama tiene una transacción abierta, falla antes de pedir nada. Así el pedido a la
 * cátedra no puede terminar dentro de una transacción ajena. Las transacciones del {@link TransactionTemplate} se abren
 * igual, porque no hay ninguna real que continuar.
 */
@Component
@Transactional(propagation = Propagation.NEVER)
@RequiredArgsConstructor
public class ExecuteFullSyncUseCaseImpl implements ExecuteFullSyncUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(ExecuteFullSyncUseCaseImpl.class);

    /** Cuántas referencias rotas se listan en la descripción del error; del resto solo se informa la cantidad. */
    static final int MAX_LISTED_REFERENCES = 3;

    private final CatalogSnapshotProvider catalogSnapshotProvider;
    private final CatalogRepository catalogRepository;
    private final SyncStatusRepository syncStatusRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final RetryBackoff retryBackoff;

    @Override
    public FullSyncResult executeFullSync(SyncTrigger trigger) {
        Objects.requireNonNull(trigger, "Falta el disparador de la sincronización");
        int maxAttempts = retryBackoff.maxRetries() + 1;
        for (int attempt = 1; ; attempt++) {
            Failure failure;
            try {
                return attemptOnce(trigger, attempt);
            } catch (RuntimeException e) {
                failure = classify(e);
            }
            if (!failure.retryable() || attempt == maxAttempts) {
                return fail(trigger, failure.type(), failure.finalDescription(attempt), attempt, failure.stackTrace());
            }
            LOG.warn(
                "Sincronización completa ({}): falló el intento {} de {} ({}). Reintento en {} ms",
                trigger,
                attempt,
                maxAttempts,
                failure.logDetail(),
                retryBackoff.delayBeforeRetry(attempt).toMillis()
            );
            try {
                retryBackoff.pauseBeforeRetry(attempt);
            } catch (InterruptedException e) {
                // Normalmente es el apagado de la aplicación: se deja de reintentar y se conserva la interrupción.
                Thread.currentThread().interrupt();
                String description =
                    "La sincronización se interrumpió mientras esperaba para reintentar. Último error: " +
                    failure.finalDescription(attempt);
                return fail(trigger, SyncErrorType.SNAPSHOT_FAILED, description, attempt, null);
            }
        }
    }

    private FullSyncResult attemptOnce(SyncTrigger trigger, int attempt) {
        CatalogSnapshot snapshot = catalogSnapshotProvider.fetchSnapshot();

        List<String> brokenReferences = snapshot.brokenReferences();
        if (!brokenReferences.isEmpty()) {
            return fail(trigger, SyncErrorType.SNAPSHOT_FAILED, describeBrokenReferences(snapshot, brokenReferences), attempt, null);
        }

        transactionTemplate.executeWithoutResult(tx -> apply(snapshot, trigger));
        LOG.info(
            "Sincronización completa ({}): snapshot {} aplicado en el intento {} ({} categorías, {} profesionales, {} horarios)",
            trigger,
            snapshot.snapshotVersion(),
            attempt,
            snapshot.professionalCategories().size(),
            snapshot.professionals().size(),
            snapshot.weeklySchedules().size()
        );
        return new FullSyncResult.Applied(snapshot.snapshotVersion(), attempt);
    }

    /** Dentro de la transacción: si algo falla, no queda ni la copia nueva ni la versión. */
    private void apply(CatalogSnapshot snapshot, SyncTrigger trigger) {
        catalogRepository.replaceAll(snapshot.professionalCategories(), snapshot.professionals(), snapshot.weeklySchedules());
        SyncStatus status = syncStatusRepository.load();
        if (status.hasLocalCopy() && snapshot.snapshotVersion() < status.getLocalVersion()) {
            // Se aplica igual: la versión local tiene que ser la de los datos guardados (§6.1), y los datos son los del
            // snapshot. Rechazarlo dejaría la copia trabada si la cátedra reinició sus versiones.
            LOG.warn(
                "Sincronización completa ({}): el snapshot {} es anterior a la versión local {}; se aplica igual",
                trigger,
                snapshot.snapshotVersion(),
                status.getLocalVersion()
            );
        }
        status.recordSnapshotApplied(snapshot.snapshotVersion(), trigger, clock.instant());
        syncStatusRepository.save(status);
    }

    private static Failure classify(RuntimeException failure) {
        if (failure instanceof SnapshotRetrievalException retrieval) {
            // El switch sobre la jerarquía sealed obliga a cubrir los tres tipos del puerto.
            return switch (retrieval) {
                case SnapshotSourceUnavailableException e -> Failure.retryable(
                    SyncErrorType.CATEDRA_UNAVAILABLE,
                    "La cátedra no estuvo disponible",
                    e.getMessage()
                );
                case SnapshotAccessDeniedException e -> Failure.definitive(
                    SyncErrorType.INVALID_CREDENTIALS,
                    "La cátedra rechazó la credencial técnica",
                    e.getMessage(),
                    null
                );
                case InvalidSnapshotException e -> Failure.definitive(
                    SyncErrorType.SNAPSHOT_FAILED,
                    "No se aplicó el snapshot",
                    e.getMessage(),
                    null
                );
            };
        }
        String exceptionName = failure.getClass().getSimpleName();
        if (DatabaseFailures.isTransient(failure)) {
            return Failure.retryable(
                SyncErrorType.DATABASE_UNAVAILABLE,
                "La base de datos local no respondió al aplicar el snapshot",
                exceptionName
            ).withStackTrace(failure);
        }
        if (failure instanceof DataAccessException || failure instanceof TransactionException) {
            return Failure.definitive(SyncErrorType.SNAPSHOT_FAILED, "La base de datos local rechazó el snapshot", exceptionName, failure);
        }
        return Failure.definitive(SyncErrorType.SNAPSHOT_FAILED, "Error inesperado al aplicar el snapshot", exceptionName, failure);
    }

    private static String describeBrokenReferences(CatalogSnapshot snapshot, List<String> brokenReferences) {
        int count = brokenReferences.size();
        String listed = brokenReferences.stream().limit(MAX_LISTED_REFERENCES).collect(Collectors.joining("; "));
        String rest = count > MAX_LISTED_REFERENCES ? " (y " + (count - MAX_LISTED_REFERENCES) + " más)" : "";
        String noun = count == 1 ? "referencia inexistente" : "referencias inexistentes";
        return "No se aplicó el snapshot " + snapshot.snapshotVersion() + ": tiene " + count + " " + noun + ": " + listed + rest;
    }

    private FullSyncResult.Failed fail(SyncTrigger trigger, SyncErrorType type, String description, int attempts, RuntimeException cause) {
        if (cause == null) {
            LOG.error("Sincronización completa ({}) fallida: {} [{}]", trigger, description, type);
        } else {
            LOG.error("Sincronización completa ({}) fallida: {} [{}]", trigger, description, type, cause);
        }
        recordError(type, description);
        return new FullSyncResult.Failed(type, description, attempts);
    }

    /**
     * En una transacción nueva: la de aplicar ya terminó, con rollback si falló. Registrar el error adentro de ella no
     * serviría: quedó marcada rollback-only y PostgreSQL rechaza toda sentencia después de un error. Si registrar
     * también falla (la base sigue caída), queda solo en el log: el resultado igual se devuelve.
     */
    private void recordError(SyncErrorType type, String description) {
        try {
            transactionTemplate.executeWithoutResult(tx -> {
                SyncStatus status = syncStatusRepository.load();
                status.recordError(type, description, clock.instant());
                syncStatusRepository.save(status);
            });
        } catch (RuntimeException e) {
            LOG.error("No se pudo registrar el error de sincronización {} en el estado", type, e);
        }
    }

    /**
     * Una falla ya clasificada. {@code summary} y {@code detail} se informan a los usuarios; {@code stackTrace} solo va
     * al log, y nunca es una excepción del puerto (su causa lleva la URL de la cátedra).
     */
    private record Failure(SyncErrorType type, String summary, String detail, boolean retryable, RuntimeException stackTrace) {
        static Failure retryable(SyncErrorType type, String summary, String detail) {
            return new Failure(type, summary, detail, true, null);
        }

        static Failure definitive(SyncErrorType type, String summary, String detail, RuntimeException stackTrace) {
            return new Failure(type, summary, detail, false, stackTrace);
        }

        Failure withStackTrace(RuntimeException exception) {
            return new Failure(type, summary, detail, retryable, exception);
        }

        /** Para el log: con la excepción de la base completa (tipo y mensaje), que no se le muestra al usuario. */
        String logDetail() {
            return stackTrace != null ? stackTrace.toString() : detail;
        }

        String finalDescription(int attempts) {
            if (!retryable) {
                return summary + ": " + detail;
            }
            return summary + " después de " + attempts + (attempts == 1 ? " intento: " : " intentos: ") + detail;
        }
    }
}
