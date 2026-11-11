package ar.edu.um.programacion2.catalogo.sync.domain.ports.out;

import ar.edu.um.programacion2.catalogo.sync.domain.model.ChangedEntities;
import ar.edu.um.programacion2.catalogo.sync.domain.model.PublishedVersions;
import ar.edu.um.programacion2.catalogo.sync.domain.model.VersionChanges;
import java.util.SortedMap;

/**
 * Fuente de las versiones y los cambios incrementales que publica la cátedra (CU-03 pasos 6 y 8, CU-04 pasos 1 a 3). El
 * dominio no sabe que detrás hay Redis: lo implementa un adaptador de {@code infrastructure.redis}.
 * <p>
 * Solo lee. Cada método hace un solo intento: los reintentos (DP-08) son del caso de uso, que decide según el tipo de
 * excepción. Las tres son de {@link ChangesRetrievalException}:
 * <ul>
 * <li>{@link ChangesSourceUnavailableException}: no respondió a tiempo o no se pudo conectar. Se puede reintentar.</li>
 * <li>{@link ChangesAccessDeniedException}: rechazó la credencial o el permiso. Reintentar no sirve.</li>
 * <li>{@link InvalidChangesException}: lo publicado no respeta el contrato. El estado no se puede verificar.</li>
 * </ul>
 */
public interface CatalogChangesProvider {
    /** La última versión publicada y la más vieja disponible (contrato §14.3). */
    PublishedVersions readPublishedVersions();

    /**
     * Los cambios de cada versión entre {@code fromVersion} y {@code toVersion}, inclusive, en una sola lectura.
     *
     * @return por versión pedida, lo que publicó; una versión que no está publicada (salió de la ventana o nunca existió)
     *         no figura. Vacío si {@code fromVersion > toVersion}.
     */
    SortedMap<Long, VersionChanges> readChanges(long fromVersion, long toVersion);

    /**
     * El estado vigente de los IDs de {@code changes} (contrato §14.4). Un ID que no está en su Hash no figura en el
     * resultado: decide el caso de uso.
     */
    ChangedEntities readEntities(VersionChanges changes);
}
