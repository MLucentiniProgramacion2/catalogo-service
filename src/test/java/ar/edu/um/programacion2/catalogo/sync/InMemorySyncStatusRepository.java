package ar.edu.um.programacion2.catalogo.sync;

import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;

/**
 * El estado de sincronización en memoria, para los unitarios: {@code load} devuelve una copia y {@code save} guarda otra,
 * como una fila de la base. Sin transacciones: lo que se guardó queda guardado, como si la transacción hubiera
 * confirmado.
 */
public final class InMemorySyncStatusRepository implements SyncStatusRepository {

    private SyncStatus stored;

    public InMemorySyncStatusRepository(SyncStatus initial) {
        this.stored = copy(initial);
    }

    @Override
    public SyncStatus load() {
        return copy(stored);
    }

    @Override
    public void save(SyncStatus status) {
        stored = copy(status);
    }

    public SyncStatus current() {
        return copy(stored);
    }

    private static SyncStatus copy(SyncStatus s) {
        return new SyncStatus(
            s.getLocalVersion(),
            s.getLastSuccessAt(),
            s.getLastSuccessPath(),
            s.getLastSuccessTrigger(),
            s.getRemoteVersion(),
            s.getRemoteVersionCheckedAt(),
            s.getLastErrorAt(),
            s.getLastErrorType(),
            s.getLastErrorDescription()
        );
    }
}
