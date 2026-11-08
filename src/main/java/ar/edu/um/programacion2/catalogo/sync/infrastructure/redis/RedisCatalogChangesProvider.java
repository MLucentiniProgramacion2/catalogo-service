package ar.edu.um.programacion2.catalogo.sync.infrastructure.redis;

import ar.edu.um.programacion2.catalogo.sync.domain.model.ChangedEntities;
import ar.edu.um.programacion2.catalogo.sync.domain.model.PublishedVersions;
import ar.edu.um.programacion2.catalogo.sync.domain.model.VersionChanges;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.CatalogChangesProvider;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.ChangesAccessDeniedException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.ChangesRetrievalException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.ChangesSourceUnavailableException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.InvalidChangesException;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.catedra.CatedraEntityJson.CategoryJson;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.catedra.CatedraEntityJson.ProfessionalJson;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.catedra.CatedraEntityJson.WeeklyScheduleJson;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.catedra.CatedraEntityMapper;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.catedra.CatedraJson;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

/**
 * Adaptador de salida: lee de Redis las versiones publicadas, los cambios y las entidades vigentes (contrato §14).
 * <p>
 * <strong>Solo lee</strong>, con {@code HMGET} y {@code MGET}: la ACL de la cátedra solo permite leer
 * {@code catedra:sync:*}, y los tests usan un usuario con esa misma ACL.
 * <p>
 * Viajes a Redis: uno para la metadata, uno para los cambios de todo el lote y uno por cada Hash con IDs en cada
 * versión. Hoy la cátedra cambia un solo profesional por versión, así que es un viaje por versión.
 * <p>
 * Hace un solo intento por lectura; los reintentos son del caso de uso. La falla se clasifica por el tipo de excepción
 * de Spring Data Redis y por el código de error de Redis (la primera palabra, como {@code NOPERM}), nunca por el resto
 * del texto:
 * <ul>
 * <li>sin conexión, timeout o cualquier otra falla del cliente → {@link ChangesSourceUnavailableException};</li>
 * <li>{@code NOPERM} o {@code WRONGPASS} → {@link ChangesAccessDeniedException};</li>
 * <li>{@code WRONGTYPE}, JSON roto o un dato que no respeta el contrato → {@link InvalidChangesException}.</li>
 * </ul>
 * {@code MGET} no da {@code WRONGTYPE}: un {@code changes:{v}} guardado con otro tipo vuelve como {@code nil} y cuenta
 * como que falta (medido). {@code HMGET} sobre un Hash que no es Hash, sí.
 * Los mensajes de esas excepciones llegan a los usuarios (CU-08, CU-09): son textos fijos, sin host, usuario ni nombres
 * de clases. El tipo de la causa y el detalle de un dato inválido van solo al log.
 */
@Component
@RequiredArgsConstructor
public class RedisCatalogChangesProvider implements CatalogChangesProvider {

    static final String METADATA_KEY = "catedra:sync:metadata";
    static final String CHANGES_KEY_PREFIX = "catedra:sync:changes:";
    static final String CATEGORIES_KEY = "catedra:sync:professional-categories";
    static final String PROFESSIONALS_KEY = "catedra:sync:professionals";
    static final String SCHEDULES_KEY = "catedra:sync:weekly-schedules";

    private static final Logger LOG = LoggerFactory.getLogger(RedisCatalogChangesProvider.class);

    private final StringRedisTemplate catedraRedisTemplate;

    @Override
    public PublishedVersions readPublishedVersions() {
        List<String> values = read("la metadata", () ->
            catedraRedisTemplate.<String, String>opsForHash().multiGet(METADATA_KEY, List.of("currentVersion", "oldestAvailableVersion"))
        );
        return parse("la metadata", () -> RedisChangesMapper.publishedVersions(values));
    }

    @Override
    public SortedMap<Long, VersionChanges> readChanges(long fromVersion, long toVersion) {
        SortedMap<Long, VersionChanges> changes = new TreeMap<>();
        if (fromVersion > toVersion) {
            return changes;
        }
        List<String> keys = new ArrayList<>();
        for (long version = fromVersion; version <= toVersion; version++) {
            keys.add(CHANGES_KEY_PREFIX + version);
        }
        List<String> values = read("los cambios de las versiones " + fromVersion + " a " + toVersion, () ->
            catedraRedisTemplate.opsForValue().multiGet(keys)
        );
        for (int i = 0; i < keys.size(); i++) {
            String value = values.get(i);
            if (value != null) {
                long version = fromVersion + i;
                String what = "el cambio de la versión " + version;
                ChangesJson json = json(value, ChangesJson.class, what);
                changes.put(version, parse(what, () -> RedisChangesMapper.versionChanges(json, version)));
            }
        }
        return changes;
    }

    @Override
    public ChangedEntities readEntities(VersionChanges changes) {
        return new ChangedEntities(
            entities(CATEGORIES_KEY, "las categorías", changes.categoryIds(), CategoryJson.class, CatedraEntityMapper::category, c -> c.id()),
            entities(
                PROFESSIONALS_KEY,
                "los profesionales",
                changes.professionalIds(),
                ProfessionalJson.class,
                CatedraEntityMapper::professional,
                p -> p.id()
            ),
            entities(SCHEDULES_KEY, "los horarios", changes.scheduleIds(), WeeklyScheduleJson.class, CatedraEntityMapper::schedule, s -> s.id())
        );
    }

    /**
     * Un {@code HMGET} con los IDs pedidos; si no hay ninguno, no viaja. Un ID que no está en el Hash no figura en el
     * resultado. El field es el ID (contrato §14.4): si la entidad dice otro, es inválida.
     */
    private <D, M> List<M> entities(
        String key,
        String what,
        Set<Long> ids,
        Class<D> type,
        Function<D, M> mapper,
        ToLongFunction<M> idOf
    ) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Long> ordered = new ArrayList<>(new TreeSet<>(ids));
        List<String> fields = ordered.stream().map(String::valueOf).toList();
        List<String> values = read(what, () -> catedraRedisTemplate.<String, String>opsForHash().multiGet(key, fields));
        List<M> entities = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            String value = values.get(i);
            if (value == null) {
                continue;
            }
            long field = ordered.get(i);
            String one = what + " (ID " + field + ")";
            D dto = json(value, type, one);
            M entity = parse(one, () -> mapper.apply(dto));
            if (idOf.applyAsLong(entity) != field) {
                LOG.warn("Redis: en {} el ID {} tiene una entidad con el ID {}", key, field, idOf.applyAsLong(entity));
                throw new InvalidChangesException("Redis de la cátedra publicó " + one + " con otro ID", null);
            }
            entities.add(entity);
        }
        return entities;
    }

    /** Una lectura, con la falla clasificada. {@code what} es un texto fijo para el usuario. */
    private <T> T read(String what, Supplier<T> call) {
        try {
            return call.get();
        } catch (DataAccessException e) {
            throw classify(e, what);
        }
    }

    private static ChangesRetrievalException classify(DataAccessException failure, String what) {
        String code = redisErrorCode(failure);
        LOG.warn("Redis: falló la lectura de {} ({}{})", what, failure.getClass().getSimpleName(), code == null ? "" : ", " + code);
        if ("NOPERM".equals(code) || "WRONGPASS".equals(code)) {
            return new ChangesAccessDeniedException("Redis de la cátedra rechazó la credencial o el permiso al leer " + what, failure);
        }
        if ("WRONGTYPE".equals(code)) {
            return new InvalidChangesException("Redis de la cátedra tiene " + what + " con otro tipo de dato", failure);
        }
        return new ChangesSourceUnavailableException("Redis de la cátedra no respondió al leer " + what, failure);
    }

    /**
     * El código de error de Redis de la causa, si la hay: la primera palabra del mensaje de un error del servidor, como
     * {@code NOPERM} o {@code WRONGPASS}. Es estable, como el {@code code} de un problem+json; el resto del texto no.
     */
    private static String redisErrorCode(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof io.lettuce.core.RedisCommandExecutionException && cause.getMessage() != null) {
                String message = cause.getMessage().strip();
                int space = message.indexOf(' ');
                return space < 0 ? message : message.substring(0, space);
            }
        }
        return null;
    }

    private static <T> T json(String value, Class<T> type, String what) {
        try {
            T parsed = CatedraJson.MAPPER.readValue(value, type);
            if (parsed == null) {
                throw new InvalidChangesException("Redis de la cátedra publicó " + what + " vacío", null);
            }
            return parsed;
        } catch (JacksonException e) {
            // El detalle de Jackson va solo al log: nombra tipos de Java.
            LOG.warn("Redis: {} no es el JSON esperado: {}", what, e.getOriginalMessage());
            throw new InvalidChangesException("Redis de la cátedra publicó " + what + " con un JSON que no es el esperado", e);
        }
    }

    private static <T> T parse(String what, Supplier<T> mapping) {
        try {
            return mapping.get();
        } catch (IllegalArgumentException | NullPointerException | DateTimeException e) {
            LOG.warn("Redis: {} no respeta el contrato: {}", what, e.getMessage());
            throw new InvalidChangesException("Redis de la cátedra publicó " + what + " sin respetar el contrato", e);
        }
    }
}
