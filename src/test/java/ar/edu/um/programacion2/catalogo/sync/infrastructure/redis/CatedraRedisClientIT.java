package ar.edu.um.programacion2.catalogo.sync.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import ar.edu.um.programacion2.catalogo.sync.RedisTestServer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * El cliente armado por {@link CatedraRedisConfiguration} contra un Redis 7.4 con la ACL de la cátedra
 * ({@link RedisTestServer}). Fija lo que mide el ADR 0006: qué manda al conectar, que hay una sola conexión, y que
 * escribir en {@code catedra:sync:*} falla.
 */
class CatedraRedisClientIT {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(CatedraRedisConfiguration.class)
        .withPropertyValues(
            "catedra.redis.host=" + RedisTestServer.host(),
            "catedra.redis.port=" + RedisTestServer.port(),
            "catedra.redis.username=" + RedisTestServer.USERNAME,
            "catedra.redis.password=" + RedisTestServer.PASSWORD
        );

    @BeforeEach
    void redisVacio() {
        RedisTestServer.flushAll();
        RedisTestServer.killConnectionsOfTechnicalUser();
        RedisTestServer.admin().hset("catedra:sync:metadata", Map.of("currentVersion", "89", "oldestAvailableVersion", "1"));
        RedisTestServer.admin().set("catedra:sync:changes:89", "{\"version\":89}");
    }

    @Test
    void conLaAclDeLaCatedraLeeYLoUnicoNegadoEsElSetinfoDelSaludo() {
        runner.run(context -> {
            StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);

            assertThat(redis.<String, String>opsForHash().multiGet("catedra:sync:metadata", List.of("currentVersion", "oldestAvailableVersion")))
                .containsExactly("89", "1");
            assertThat(redis.opsForValue().multiGet(List.of("catedra:sync:changes:89", "catedra:sync:changes:90"))).containsExactly(
                "{\"version\":89}",
                null
            );

            // Ni PING, ni ECHO, ni INFO, ni CLIENT SETNAME: si el cliente usara alguno, aparecería acá.
            assertThat(RedisTestServer.deniedCounts()).containsOnlyKeys("client|setinfo");
        });
    }

    @Test
    void hayUnaSolaConexionYElSetinfoQuedaUnaVezPorConexionNoPorComando() {
        runner.run(context -> {
            StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);

            for (int i = 0; i < 20; i++) {
                redis.opsForValue().get("catedra:sync:changes:89");
            }
            long porConexion = RedisTestServer.deniedCounts().get("client|setinfo");

            assertThat(RedisTestServer.connectionsOfTechnicalUser()).isEqualTo(1);
            // Lettuce manda un SETINFO para el nombre y otro para la versión de la librería, los dos en el saludo.
            assertThat(porConexion).isEqualTo(2);

            // Un corte: el cliente reconecta solo y el saludo se repite, una vez más.
            RedisTestServer.killConnectionsOfTechnicalUser();
            Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .ignoreExceptions()
                .until(() -> "{\"version\":89}".equals(redis.opsForValue().get("catedra:sync:changes:89")));
            for (int i = 0; i < 20; i++) {
                redis.opsForValue().get("catedra:sync:changes:89");
            }

            assertThat(RedisTestServer.connectionsOfTechnicalUser()).isEqualTo(1);
            assertThat(RedisTestServer.deniedCounts().get("client|setinfo")).isEqualTo(2 * porConexion);
        });
    }

    @Test
    void escribirEnElNamespaceDeLaCatedraFallaPorLaAcl() {
        runner.run(context -> {
            StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);

            assertThatThrownBy(() -> redis.opsForValue().set("catedra:sync:changes:90", "{}")).hasRootCauseMessage(
                "NOPERM No permissions to access a key"
            );
            assertThat(RedisTestServer.admin().exists("catedra:sync:changes:90")).isZero();
        });
    }

    @Test
    void conLaContrasenaEquivocadaFallaSinMostrarlaNiElUsuario() {
        runner
            .withPropertyValues("catedra.redis.password=otra-clave-equivocada-77")
            .run(context -> {
                Throwable falla = catchThrowable(() -> context.getBean(StringRedisTemplate.class).opsForValue().get("catedra:sync:changes:89"));

                assertThat(falla).isNotNull();
                for (Throwable t = falla; t != null; t = t.getCause()) {
                    assertThat(String.valueOf(t.getMessage())).doesNotContain(
                        "otra-clave-equivocada-77",
                        RedisTestServer.PASSWORD,
                        RedisTestServer.USERNAME
                    );
                }
            });
    }
}
