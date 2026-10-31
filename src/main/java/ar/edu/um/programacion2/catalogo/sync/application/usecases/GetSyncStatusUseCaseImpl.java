package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.GetSyncStatusUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lee el estado en una transacción de solo lectura: el adaptador exige una transacción abierta ({@code MANDATORY}).
 * <p>
 * Acá alcanza con {@code @Transactional}: la transacción es el método entero y quien llama es otro bean, así que la
 * llamada pasa por el proxy.
 */
@Component
@RequiredArgsConstructor
public class GetSyncStatusUseCaseImpl implements GetSyncStatusUseCase {

    private final SyncStatusRepository syncStatusRepository;

    @Override
    @Transactional(readOnly = true)
    public SyncStatus getSyncStatus() {
        return syncStatusRepository.load();
    }
}
