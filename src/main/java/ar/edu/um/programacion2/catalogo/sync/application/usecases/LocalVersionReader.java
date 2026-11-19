package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.GetSyncStatusUseCase;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * Lee la versión local que usa un disparador (CU-01, CU-08 y la comprobación periódica), y si la base no responde no
 * intenta nada: registra {@code DATABASE_UNAVAILABLE} con un texto fijo.
 * <p>
 * Hay que llamarlo con la exclusión mutua tomada (DP-09): leída afuera, otra sincronización podría cambiar la versión
 * mientras este disparador espera. Por eso lo usa cada disparador adentro de su {@code ExclusiveSync} y no es parte de
 * CU-03, que recibe la versión ya leída.
 * <p>
 * Solo las fallas de la base cuentan como "no se pudo leer": {@link DataAccessException}, y {@link TransactionException}
 * porque con la base caída lo primero que falla es abrir la transacción. Cualquier otra sale tal cual.
 */
@Component
@RequiredArgsConstructor
public class LocalVersionReader {

    private static final Logger LOG = LoggerFactory.getLogger(LocalVersionReader.class);

    /** Se informa a los usuarios (CU-09): texto fijo, sin el mensaje de la excepción. */
    public static final String VERSION_UNREADABLE = "No se pudo leer la versión local en la base de datos: no se intentó la sincronización";

    private final GetSyncStatusUseCase getSyncStatusUseCase;
    private final SyncErrorRecorder syncErrorRecorder;

    /**
     * @param sync lo que corre con la versión leída; recibe {@code null} si no hay copia local.
     * @param unreadable el resultado si la base no respondió; el error ya quedó registrado.
     */
    public <T> T withLocalVersion(SyncTrigger trigger, Function<Long, T> sync, Supplier<T> unreadable) {
        Objects.requireNonNull(trigger, "Falta el disparador de la sincronización");
        Long localVersion;
        try {
            localVersion = getSyncStatusUseCase.getSyncStatus().getLocalVersion();
        } catch (DataAccessException | TransactionException e) {
            LOG.error("Sincronización ({}): {}", trigger, VERSION_UNREADABLE, e);
            syncErrorRecorder.record(SyncErrorType.DATABASE_UNAVAILABLE, VERSION_UNREADABLE);
            return unreadable.get();
        }
        return sync.apply(localVersion);
    }
}
