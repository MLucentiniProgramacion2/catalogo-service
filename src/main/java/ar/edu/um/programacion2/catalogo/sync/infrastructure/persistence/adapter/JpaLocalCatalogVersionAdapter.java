package ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.adapter;

import static ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.entity.SyncStatusEntity.SINGLE_ROW_ID;

import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.LocalCatalogVersionProvider;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.entity.SyncStatusEntity;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.repository.JpaSyncStatusRepository;
import java.util.OptionalLong;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adaptador de salida de {@code sync} para un puerto de {@code catalog}: lee la versión aplicada de la única fila de
 * {@code sync_status}.
 * <p>
 * Está en {@code sync} porque {@code sync_status} es de {@code sync}: así la tabla tiene un solo dueño y la dependencia
 * entre features sigue yendo de {@code sync} a {@code catalog}, como en CU-02.
 * <p>
 * {@code MANDATORY} y {@code @Repository}, como {@link JpaSyncStatusRepositoryAdapter}. Que falte la fila es un error de
 * instalación: falla igual que ese adaptador y nunca la crea.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
@RequiredArgsConstructor
public class JpaLocalCatalogVersionAdapter implements LocalCatalogVersionProvider {

    private final JpaSyncStatusRepository jpaSyncStatusRepository;

    @Override
    public OptionalLong findAppliedVersion() {
        SyncStatusEntity row = jpaSyncStatusRepository
            .findById(SINGLE_ROW_ID)
            .orElseThrow(() ->
                new IllegalStateException(
                    "No existe la fila " + SINGLE_ROW_ID + " de sync_status: la crea Liquibase al instalar (sync/001-sync-status.xml)"
                )
            );
        Long version = row.getLocalVersion();
        return version == null ? OptionalLong.empty() : OptionalLong.of(version);
    }
}
