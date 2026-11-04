package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncLock;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * DP-09: corre una sincronización con la exclusión mutua tomada y, si no se pudo tomar, lo deja registrado. La usan los
 * disparadores (CU-01, CU-08 y CU-03), no CU-02 ni CU-04: esos son casos incluidos que exigen que quien los llama ya la
 * tenga.
 * <p>
 * {@code Propagation.NEVER}: la espera puede durar minutos, y dentro de una transacción retendría una conexión de la base
 * todo ese tiempo. Además CU-02 exige que lo llamen sin transacción. El orden es siempre primero la exclusión y después
 * las transacciones de adentro, nunca al revés.
 * <p>
 * El error se registra en una transacción propia, <strong>sin</strong> la exclusión y mientras otra sincronización corre:
 * por eso hace falta lo que explica {@code JpaSyncStatusRepositoryAdapter}. Si no se puede registrar, queda en el log.
 */
@Component
@RequiredArgsConstructor
public class ExclusiveSync {

    private static final Logger LOG = LoggerFactory.getLogger(ExclusiveSync.class);

    private final SyncLock syncLock;
    private final SyncErrorRecorder syncErrorRecorder;

    /**
     * @param trigger quién pide la sincronización; solo se usa para informar si no se ejecuta.
     * @param action la sincronización; no puede devolver {@code null}. Una excepción suya sale tal cual, sin registrarse
     *        acá: la exclusión ya se liberó.
     * @return el resultado de la acción, o vacío si no se ejecutó (el motivo ya quedó registrado).
     */
    @Transactional(propagation = Propagation.NEVER)
    public <T> Optional<T> run(SyncTrigger trigger, Supplier<T> action) {
        try {
            Optional<T> result = syncLock.runExclusively(action);
            if (result.isEmpty()) {
                notExecuted(trigger, "La sincronización (" + trigger + ") no se ejecutó: otra seguía en curso al vencer la espera máxima");
            }
            return result;
        } catch (InterruptedException e) {
            // Normalmente es el apagado de la aplicación: se deja de esperar y se conserva la interrupción.
            Thread.currentThread().interrupt();
            notExecuted(trigger, "La sincronización (" + trigger + ") no se ejecutó: se interrumpió mientras esperaba a que terminara otra");
            return Optional.empty();
        }
    }

    /** Si hay una sincronización corriendo ahora (CU-09). */
    public boolean isRunning() {
        return syncLock.isHeld();
    }

    private void notExecuted(SyncTrigger trigger, String description) {
        LOG.warn("{} [{}]", description, SyncErrorType.LOCK_TIMEOUT);
        syncErrorRecorder.record(SyncErrorType.LOCK_TIMEOUT, description);
    }
}
