package ar.edu.um.programacion2.catalogo.sync.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.sync.RedisTestServer;
import ar.edu.um.programacion2.catalogo.sync.domain.model.ChangedEntities;
import ar.edu.um.programacion2.catalogo.sync.domain.model.PublishedVersions;
import ar.edu.um.programacion2.catalogo.sync.domain.model.VersionChanges;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.ChangesAccessDeniedException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.ChangesRetrievalException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.ChangesSourceUnavailableException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.InvalidChangesException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.GenericContainer;

/**
 * El adaptador contra un Redis 7.4 con la ACL de la cátedra y los datos tal como están en el Redis real (7/11). Como el
 * usuario solo puede leer {@code catedra:sync:*}, cualquier escritura del adaptador haría fallar estos tests.
 * <p>
 * Los viajes se cuentan en el servidor ({@code INFO commandstats}), no en el cliente.
 */
class RedisCatalogChangesProviderIT {

    private static final String CAMBIO_88 = """
        {"version":88,"changes":{"professionalCategories":[],"professionals":[201],"weeklySchedules":[]}}""";
    private static final String CAMBIO_89 = """
        {"version":89,"changes":{"professionalCategories":[],"professionals":[201],"weeklySchedules":[]}}""";
    private static final String PROFESIONAL_201 = """
        {"id":201,"categoryId":101,"firstName":"Ana","lastName":"Torres","enabled":true,"createdAt":"2026-09-01T03:00:00Z","updatedAt":"2026-11-08T01:19:34.423296Z"}""";
    private static final String PROFESIONAL_202 = """
        {"id":202,"categoryId":101,"firstName":"Martin","lastName":"Rivas","enabled":true,"createdAt":"2026-09-01T03:00:00Z","updatedAt":"2026-09-01T03:00:00Z"}""";
    private static final String HORARIO_342 = """
        {"id":342,"professionalId":223,"dayOfWeek":"THURSDAY","startTime":"16:00:00","endTime":"19:00:00","slotDurationMinutes":30,"enabled":true,"createdAt":"2026-09-01T03:00:00Z","updatedAt":"2026-09-01T03:00:00Z"}""";

    @BeforeEach
    void redisComoElDeLaCatedra() {
        RedisTestServer.flushAll();
        var admin = RedisTestServer.admin();
        admin.hset(
            "catedra:sync:metadata",
            Map.of("currentVersion", "89", "oldestAvailableVersion", "1", "publishedAt", "2026-11-08T01:19:34.443824516Z")
        );
        admin.set("catedra:sync:changes:88", CAMBIO_88);
        admin.set("catedra:sync:changes:89", CAMBIO_89);
        admin.hset("catedra:sync:professionals", Map.of("201", PROFESIONAL_201, "202", PROFESIONAL_202));
        admin.hset("catedra:sync:weekly-schedules", Map.of("342", HORARIO_342));
    }

    @Test
    void leeLaMetadataEnUnViaje() {
        conAdaptador(redis -> {
            resetearContadores();

            assertThat(redis.readPublishedVersions()).isEqualTo(new PublishedVersions(89, 1));
            assertThat(viajes("hmget")).isEqualTo(1);
        });
    }

    @Test
    void sinMetadataOConUnaVersionQueNoEsUnNumeroElEstadoNoSePuedeVerificar() {
        conAdaptador(redis -> {
            RedisTestServer.admin().hset("catedra:sync:metadata", "currentVersion", "89.0");
            assertThatThrownBy(redis::readPublishedVersions).isInstanceOf(InvalidChangesException.class).hasMessage(
                "Redis de la cátedra publicó la metadata sin respetar el contrato"
            );

            RedisTestServer.admin().del("catedra:sync:metadata");
            assertThatThrownBy(redis::readPublishedVersions).isInstanceOf(InvalidChangesException.class);
        });
    }

    @Test
    void leeLosCambiosDeTodoElLoteEnUnViajeYLasVersionesQueFaltanNoFiguran() {
        conAdaptador(redis -> {
            resetearContadores();

            SortedMap<Long, VersionChanges> cambios = redis.readChanges(87, 90);

            assertThat(cambios).containsOnlyKeys(88L, 89L);
            assertThat(cambios.get(89L)).isEqualTo(new VersionChanges(89, Set.of(), Set.of(201L), Set.of()));
            assertThat(viajes("mget")).isEqualTo(1);
        });
    }

    @Test
    void elCasoRealSonTreintaYTresVersionesEnUnViajeYUnViajePorVersion() {
        for (long v = 57; v <= 89; v++) {
            RedisTestServer.admin().set("catedra:sync:changes:" + v, CAMBIO_89.replace("89", Long.toString(v)));
        }
        conAdaptador(redis -> {
            resetearContadores();

            SortedMap<Long, VersionChanges> cambios = redis.readChanges(57, 89);
            cambios.values().forEach(redis::readEntities);

            assertThat(cambios).hasSize(33);
            assertThat(viajes("mget")).isEqualTo(1);
            assertThat(viajes("hmget")).isEqualTo(33);
        });
    }

    @Test
    void devuelveElVersionQueDiceElContenidoAunqueNoSeaElPedido() {
        // Lo verifica CU-04: el adaptador no decide.
        RedisTestServer.admin().set("catedra:sync:changes:89", CAMBIO_88);
        conAdaptador(redis -> assertThat(redis.readChanges(89, 89).get(89L).version()).isEqualTo(88));
    }

    @Test
    void ignoraLosCamposDesconocidosDeLosCambios() {
        RedisTestServer.admin().set(
            "catedra:sync:changes:89",
            "{\"version\":89,\"origen\":\"MANUAL\",\"changes\":{\"professionalCategories\":[],\"professionals\":[201],\"weeklySchedules\":[],\"otros\":[1]}}"
        );
        conAdaptador(redis -> assertThat(redis.readChanges(89, 89).get(89L).professionalIds()).containsExactly(201L));
    }

    @Test
    void unCambioRotoOIncompletoEsInvalido() {
        conAdaptador(redis -> {
            for (String roto : new String[] {
                "esto no es json",
                "null",
                "{\"version\":89}",
                "{\"changes\":{\"professionalCategories\":[],\"professionals\":[201],\"weeklySchedules\":[]}}",
                "{\"version\":89,\"changes\":{\"professionalCategories\":[],\"professionals\":[201]}}",
                "{\"version\":89,\"changes\":{\"professionalCategories\":[],\"professionals\":[null],\"weeklySchedules\":[]}}",
                "{\"version\":89,\"changes\":{\"professionalCategories\":[],\"professionals\":[201.5],\"weeklySchedules\":[]}}",
                "{\"version\":89.5,\"changes\":{\"professionalCategories\":[],\"professionals\":[201],\"weeklySchedules\":[]}}",
            }) {
                RedisTestServer.admin().set("catedra:sync:changes:89", roto);
                assertThatThrownBy(() -> redis.readChanges(89, 89)).as(roto).isInstanceOf(InvalidChangesException.class);
            }
        });
    }

    @Test
    void unCambioGuardadoConOtroTipoDeDatoCuentaComoQueFalta() {
        // MGET no da WRONGTYPE: devuelve nil para una clave que no es un String. La versión no figura y CU-03 va a snapshot.
        RedisTestServer.admin().del("catedra:sync:changes:89");
        RedisTestServer.admin().hset("catedra:sync:changes:89", "version", "89");
        conAdaptador(redis -> assertThat(redis.readChanges(88, 89)).containsOnlyKeys(88L));
    }

    @Test
    void unHashGuardadoConOtroTipoDeDatoEsInvalido() {
        // HMGET sí da WRONGTYPE.
        RedisTestServer.admin().del("catedra:sync:professionals");
        RedisTestServer.admin().set("catedra:sync:professionals", "no es un hash");
        conAdaptador(redis ->
            assertThatThrownBy(() -> redis.readEntities(new VersionChanges(89, Set.of(), Set.of(201L), Set.of())))
                .isInstanceOf(InvalidChangesException.class)
                .hasMessage("Redis de la cátedra tiene los profesionales con otro tipo de dato")
        );
    }

    @Test
    void leeLasEntidadesVigentesYLasQueNoEstanNoFiguran() {
        conAdaptador(redis -> {
            resetearContadores();

            ChangedEntities entidades = redis.readEntities(new VersionChanges(89, Set.of(), Set.of(201L, 999L), Set.of(342L)));

            assertThat(entidades.professionals()).containsExactly(
                new Professional(
                    201,
                    101,
                    "Ana",
                    "Torres",
                    true,
                    Instant.parse("2026-09-01T03:00:00Z"),
                    Instant.parse("2026-11-08T01:19:34.423296Z")
                )
            );
            assertThat(entidades.schedules()).extracting(s -> s.id()).containsExactly(342L);
            assertThat(entidades.categories()).isEmpty();
            // Un HMGET por Hash con IDs: el de categorías no viaja.
            assertThat(viajes("hmget")).isEqualTo(2);
        });
    }

    @Test
    void unaVersionSinCambiosNoViaja() {
        conAdaptador(redis -> {
            resetearContadores();

            assertThat(redis.readEntities(new VersionChanges(89, Set.of(), Set.of(), Set.of())).professionals()).isEmpty();
            assertThat(viajes("hmget")).isZero();
        });
    }

    @Test
    void unaEntidadRotaOConOtroIdEsInvalida() {
        conAdaptador(redis -> {
            VersionChanges cambio = new VersionChanges(89, Set.of(), Set.of(201L), Set.of());

            RedisTestServer.admin().hset("catedra:sync:professionals", "201", PROFESIONAL_202);
            assertThatThrownBy(() -> redis.readEntities(cambio)).isInstanceOf(InvalidChangesException.class).hasMessage(
                "Redis de la cátedra publicó los profesionales (ID 201) con otro ID"
            );

            RedisTestServer.admin().hset("catedra:sync:professionals", "201", PROFESIONAL_201.replace("\"enabled\":true,", ""));
            assertThatThrownBy(() -> redis.readEntities(cambio)).isInstanceOf(InvalidChangesException.class);

            RedisTestServer.admin().hset("catedra:sync:professionals", "201", "{roto");
            assertThatThrownBy(() -> redis.readEntities(cambio)).isInstanceOf(InvalidChangesException.class);
        });
    }

    @Test
    void conLaContrasenaEquivocadaEsAccesoDenegadoSinUsuarioNiHost() {
        conAdaptador(
            redis -> {
                ChangesAccessDeniedException falla = catchThrowableOfType(ChangesAccessDeniedException.class, redis::readPublishedVersions);

                assertThat(falla).hasMessage("Redis de la cátedra rechazó la credencial o el permiso al leer la metadata");
            },
            "catedra.redis.password=otra-clave"
        );
    }

    @Test
    void conElPuertoCerradoFallaEnseguidaComoNoDisponible() {
        conAdaptador(
            redis -> {
                long inicio = System.nanoTime();
                ChangesRetrievalException falla = catchThrowableOfType(ChangesSourceUnavailableException.class, redis::readPublishedVersions);

                assertThat(falla).hasMessage("Redis de la cátedra no respondió al leer la metadata");
                assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(2));
            },
            "catedra.redis.port=1"
        );
    }

    @Test
    void siRedisDejaDeResponderFallaAlVencerElTimeoutDelComando() {
        GenericContainer<?> propio = RedisTestServer.newContainer();
        try {
            conAdaptador(
                redis -> {
                    // La primera lectura abre la conexión; después, Redis deja de responder sin cortarla.
                    assertThatThrownBy(redis::readPublishedVersions).isInstanceOf(InvalidChangesException.class);
                    propio.getDockerClient().pauseContainerCmd(propio.getContainerId()).exec();
                    try {
                        long inicio = System.nanoTime();
                        assertThatThrownBy(redis::readPublishedVersions).isInstanceOf(ChangesSourceUnavailableException.class);
                        Duration demora = Duration.ofNanos(System.nanoTime() - inicio);

                        assertThat(demora).isBetween(Duration.ofMillis(450), Duration.ofSeconds(3));
                    } finally {
                        propio.getDockerClient().unpauseContainerCmd(propio.getContainerId()).exec();
                    }
                },
                "catedra.redis.host=" + propio.getHost(),
                "catedra.redis.port=" + propio.getMappedPort(6379),
                "catedra.redis.command-timeout=500ms"
            );
        } finally {
            propio.stop();
        }
    }

    private static void conAdaptador(Consumer<RedisCatalogChangesProvider> prueba, String... propiedades) {
        new ApplicationContextRunner()
            .withUserConfiguration(CatedraRedisConfiguration.class, RedisCatalogChangesProvider.class)
            .withPropertyValues(
                "catedra.redis.host=" + RedisTestServer.host(),
                "catedra.redis.port=" + RedisTestServer.port(),
                "catedra.redis.username=" + RedisTestServer.USERNAME,
                "catedra.redis.password=" + RedisTestServer.PASSWORD
            )
            .withPropertyValues(propiedades)
            .run(context -> prueba.accept(context.getBean(RedisCatalogChangesProvider.class)));
    }

    private static void resetearContadores() {
        RedisTestServer.admin().configResetstat();
    }

    /** Cuántas veces atendió el servidor el comando desde el último {@link #resetearContadores()}. */
    private static long viajes(String comando) {
        return RedisTestServer.admin()
            .info("commandstats")
            .lines()
            .filter(line -> line.startsWith("cmdstat_" + comando + ":"))
            .findFirst()
            .map(line -> Long.parseLong(line.replaceAll("^cmdstat_[^:]+:calls=(\\d+),.*$", "$1")))
            .orElse(0L);
    }
}
