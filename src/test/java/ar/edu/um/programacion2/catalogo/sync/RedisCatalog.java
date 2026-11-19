package ar.edu.um.programacion2.catalogo.sync;

import io.lettuce.core.api.sync.RedisCommands;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Lo que publica la cátedra en Redis (contrato §14.3 a §14.5), cargado con el usuario {@code default} de un Redis de
 * prueba: la metadata, un {@code changes:{v}} y las entidades vigentes de los Hashes. Solo arma datos válidos en formato.
 */
public final class RedisCatalog {

    private static final String CREADO = "2026-07-01T12:00:00Z";

    private final RedisCommands<String, String> redis;

    public RedisCatalog(RedisCommands<String, String> redis) {
        this.redis = redis;
    }

    /** El catálogo del Redis compartido de los tests ({@link RedisTestServer}). */
    public static RedisCatalog shared() {
        return new RedisCatalog(RedisTestServer.admin());
    }

    public RedisCatalog metadata(long currentVersion, long oldestAvailableVersion) {
        redis.hset(
            "catedra:sync:metadata",
            Map.of(
                "currentVersion",
                String.valueOf(currentVersion),
                "oldestAvailableVersion",
                String.valueOf(oldestAvailableVersion),
                "publishedAt",
                "2026-11-08T13:19:00Z"
            )
        );
        return this;
    }

    /** {@code changes:{version}} con los IDs de profesionales que cambiaron. */
    public RedisCatalog professionalsChangedIn(long version, Long... professionalIds) {
        return changes(version, List.of(), List.of(professionalIds), List.of());
    }

    public RedisCatalog changes(long version, List<Long> categories, List<Long> professionals, List<Long> schedules) {
        redis.set(
            "catedra:sync:changes:" + version,
            "{\"version\":" +
                version +
                ",\"changes\":{\"professionalCategories\":" +
                ids(categories) +
                ",\"professionals\":" +
                ids(professionals) +
                ",\"weeklySchedules\":" +
                ids(schedules) +
                "}}"
        );
        return this;
    }

    public RedisCatalog professional(long id, long categoryId, String firstName, boolean enabled) {
        redis.hset(
            "catedra:sync:professionals",
            String.valueOf(id),
            "{\"id\":" +
                id +
                ",\"categoryId\":" +
                categoryId +
                ",\"firstName\":\"" +
                firstName +
                "\",\"lastName\":\"Gomez\",\"enabled\":" +
                enabled +
                ",\"createdAt\":\"" +
                CREADO +
                "\",\"updatedAt\":\"2026-11-08T13:19:00Z\"}"
        );
        return this;
    }

    public RedisCatalog schedule(long id, long professionalId, String dayOfWeek, String start, String end, boolean enabled) {
        redis.hset(
            "catedra:sync:weekly-schedules",
            String.valueOf(id),
            "{\"id\":" +
                id +
                ",\"professionalId\":" +
                professionalId +
                ",\"dayOfWeek\":\"" +
                dayOfWeek +
                "\",\"startTime\":\"" +
                start +
                "\",\"endTime\":\"" +
                end +
                "\",\"slotDurationMinutes\":30,\"enabled\":" +
                enabled +
                ",\"createdAt\":\"" +
                CREADO +
                "\",\"updatedAt\":\"2026-11-08T13:19:00Z\"}"
        );
        return this;
    }

    private static String ids(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }
}
