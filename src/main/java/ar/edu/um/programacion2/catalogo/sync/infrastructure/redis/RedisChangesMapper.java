package ar.edu.um.programacion2.catalogo.sync.infrastructure.redis;

import ar.edu.um.programacion2.catalogo.sync.domain.model.PublishedVersions;
import ar.edu.um.programacion2.catalogo.sync.domain.model.VersionChanges;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.catedra.CatedraEntityMapper;
import java.util.List;
import java.util.Set;

/**
 * Traduce lo que hay en Redis al dominio: la metadata (Hash de strings, contrato §14.3) y los cambios de una versión
 * (contrato §14.5). Las entidades usan {@link CatedraEntityMapper}, compartido con el snapshot.
 * <p>
 * Valida solo lo que garantiza el contrato: números decimales, y los campos y las tres listas presentes. Un dato que no
 * cumple sale como {@link IllegalArgumentException}; el adaptador la traduce a la excepción del puerto.
 */
final class RedisChangesMapper {

    private RedisChangesMapper() {}

    /** @param values {@code currentVersion} y {@code oldestAvailableVersion}, en ese orden, tal como los da {@code HMGET}. */
    static PublishedVersions publishedVersions(List<String> values) {
        return new PublishedVersions(number(values.get(0), "currentVersion"), number(values.get(1), "oldestAvailableVersion"));
    }

    static VersionChanges versionChanges(ChangesJson json, long requestedVersion) {
        String owner = "El cambio de la versión " + requestedVersion;
        long version = CatedraEntityMapper.required(json.version(), owner, "version");
        ChangesJson.ChangeListsJson lists = CatedraEntityMapper.required(json.changes(), owner, "changes");
        return new VersionChanges(
            version,
            ids(lists.professionalCategories(), owner, "professionalCategories"),
            ids(lists.professionals(), owner, "professionals"),
            ids(lists.weeklySchedules(), owner, "weeklySchedules")
        );
    }

    /** Un Hash de strings: el valor tiene que ser un entero decimal, sin decimales ni espacios. */
    private static long number(String value, String field) {
        CatedraEntityMapper.required(value, "La metadata", field);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("La metadata tiene un " + field + " que no es un número: '" + value + "'", e);
        }
    }

    private static Set<Long> ids(List<Long> ids, String owner, String field) {
        CatedraEntityMapper.required(ids, owner, field);
        if (ids.contains(null)) {
            throw new IllegalArgumentException(owner + " tiene un ID nulo en " + field);
        }
        return Set.copyOf(ids);
    }
}
