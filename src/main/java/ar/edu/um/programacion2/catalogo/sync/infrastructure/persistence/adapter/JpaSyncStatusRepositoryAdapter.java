package ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.adapter;

import static ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.entity.SyncStatusEntity.SINGLE_ROW_ID;

import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.entity.SyncStatusEntity;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.mapper.SyncStatusMapper;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.repository.JpaSyncStatusRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adaptador de salida: guarda el estado de sincronización en la única fila de {@code sync_status}.
 * <p>
 * {@code MANDATORY}: las operaciones se suman a la transacción del caso de uso y fallan con
 * {@code IllegalTransactionStateException} si no hay una abierta. {@code @Repository} (y no {@code @Component}) hace que
 * Spring traduzca las fallas de la base a {@code DataAccessException}. La traducción alcanza también a las
 * {@code IllegalStateException} e {@code IllegalArgumentException} que salen de acá: llegan como
 * {@code InvalidDataAccessApiUsageException}, con el mismo mensaje y la original como causa. Es una falla no transitoria,
 * así que el caso de uso no la reintenta.
 * <p>
 * La fila la crea Liquibase ({@code sync/001-sync-status.xml}). {@code save} la busca antes de guardar: un
 * {@code merge} sobre una fila inexistente la insertaría, y el código nunca decide crearla.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
@RequiredArgsConstructor
public class JpaSyncStatusRepositoryAdapter implements SyncStatusRepository {

    private final JpaSyncStatusRepository jpaSyncStatusRepository;
    private final SyncStatusMapper syncStatusMapper;

    @Override
    public SyncStatus load() {
        return syncStatusMapper.toDomain(findSingleRow());
    }

    @Override
    public void save(SyncStatus status) {
        // Deja la fila administrada en el contexto: el merge de save copia sobre ella sin otro SELECT.
        findSingleRow();
        jpaSyncStatusRepository.save(syncStatusMapper.toEntity(status));
    }

    private SyncStatusEntity findSingleRow() {
        return jpaSyncStatusRepository
            .findById(SINGLE_ROW_ID)
            .orElseThrow(() ->
                new IllegalStateException(
                    "No existe la fila " + SINGLE_ROW_ID + " de sync_status: la crea Liquibase al instalar (sync/001-sync-status.xml)"
                )
            );
    }
}
