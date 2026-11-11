package ar.edu.um.programacion2.catalogo.sync.infrastructure.client;

import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogSnapshot;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.InvalidSnapshotException;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.catedra.CatedraEntityMapper;
import java.time.DateTimeException;
import java.util.List;
import java.util.function.Function;

/**
 * Traduce el DTO del snapshot de la cátedra al modelo de dominio.
 * <p>
 * Las reglas de formato de las entidades son las de {@link CatedraEntityMapper}, compartidas con Redis. Acá se suman
 * las del snapshot mismo: versión, {@code generatedAt} y las tres colecciones presentes, sin elementos nulos. Cualquier
 * dato que no cumpla termina en {@link InvalidSnapshotException}: no hay snapshot parcial.
 */
final class CatedraSnapshotMapper {

    private CatedraSnapshotMapper() {}

    static CatalogSnapshot toDomain(SnapshotResponse response) {
        try {
            long version = CatedraEntityMapper.required(response.snapshotVersion(), "El snapshot", "snapshotVersion");
            String snapshot = "El snapshot " + version;
            return new CatalogSnapshot(
                version,
                CatedraEntityMapper.instant(response.generatedAt(), snapshot, "generatedAt"),
                list(
                    CatedraEntityMapper.required(response.professionalCategories(), snapshot, "professionalCategories"),
                    CatedraEntityMapper::category
                ),
                list(CatedraEntityMapper.required(response.professionals(), snapshot, "professionals"), CatedraEntityMapper::professional),
                list(CatedraEntityMapper.required(response.weeklySchedules(), snapshot, "weeklySchedules"), CatedraEntityMapper::schedule)
            );
        } catch (NullPointerException | IllegalArgumentException | DateTimeException e) {
            throw new InvalidSnapshotException("Snapshot inválido: " + e.getMessage(), e);
        }
    }

    /** Una colección con un elemento {@code null} también es inválida. */
    private static <D, M> List<M> list(List<D> dtos, Function<D, M> mapper) {
        return dtos
            .stream()
            .map(dto -> {
                if (dto == null) {
                    throw new IllegalArgumentException("Una colección tiene un elemento nulo");
                }
                return mapper.apply(dto);
            })
            .toList();
    }
}
