package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registra el último error en el estado de sincronización, en una transacción propia. La usan CU-02 y la exclusión mutua
 * (DP-09), para que todos los errores se guarden por el mismo camino.
 * <p>
 * Transacción nueva: la que falló ya terminó con rollback. Registrar adentro de ella no serviría: quedó marcada
 * rollback-only y PostgreSQL rechaza toda sentencia después de un error. Si registrar también falla (la base sigue
 * caída), queda solo en el log: registrar nunca le agrega una excepción a quien ya está informando una falla.
 * <p>
 * La lectura y el guardado van en la <strong>misma</strong> transacción, como pide {@link SyncStatusRepository}: este
 * camino puede correr mientras otra sincronización confirma una versión, y solo así no la pisa.
 */
@Component
@RequiredArgsConstructor
public class SyncErrorRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(SyncErrorRecorder.class);

    private final SyncStatusRepository syncStatusRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /** La descripción se informa a los usuarios: no puede llevar datos sensibles. */
    public void record(SyncErrorType type, String description) {
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
}
