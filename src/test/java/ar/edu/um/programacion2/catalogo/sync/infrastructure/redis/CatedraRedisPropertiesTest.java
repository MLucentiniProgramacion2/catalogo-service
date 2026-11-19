package ar.edu.um.programacion2.catalogo.sync.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Sin host, puerto, usuario o contraseña de Redis la aplicación no arranca, como con Kafka y la API (ADR 0006), y la
 * contraseña no se imprime. Levanta solo la configuración: nadie se conecta, porque la conexión se abre con la primera
 * lectura.
 */
class CatedraRedisPropertiesTest {

    private static final String HOST = "catedra.redis.host=redis.test";
    private static final String PORT = "catedra.redis.port=6380";
    private static final String USER = "catedra.redis.username=usuario-de-prueba";
    private static final String PASSWORD = "catedra.redis.password=clave-de-prueba-9z8y";

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(CatedraRedisConfiguration.class);

    @Test
    void conLosCuatroDatosArrancaConLosTimeoutsPorDefectoSinConectarse() {
        runner
            .withPropertyValues(HOST, PORT, USER, PASSWORD)
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(StringRedisTemplate.class).hasSingleBean(LettuceConnectionFactory.class);
                CatedraRedisProperties properties = context.getBean(CatedraRedisProperties.class);
                assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
                assertThat(properties.getCommandTimeout()).isEqualTo(Duration.ofSeconds(3));
                LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
                assertThat(factory.getHostName()).isEqualTo("redis.test");
                assertThat(factory.getPort()).isEqualTo(6380);
                assertThat(factory.getClientConfiguration().getCommandTimeout()).isEqualTo(Duration.ofSeconds(3));
            });
    }

    @Test
    void tomaLosTimeoutsConfigurados() {
        runner
            .withPropertyValues(HOST, PORT, USER, PASSWORD, "catedra.redis.connect-timeout=2s", "catedra.redis.command-timeout=500ms")
            .run(context -> {
                CatedraRedisProperties properties = context.getBean(CatedraRedisProperties.class);
                assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
                assertThat(properties.getCommandTimeout()).isEqualTo(Duration.ofMillis(500));
            });
    }

    @ParameterizedTest
    @MethodSource("configuracionesIncompletas")
    void sinAlgunDatoNoArranca(String campo, String[] propiedades) {
        runner
            .withPropertyValues(propiedades)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(campo);
            });
    }

    static Stream<Arguments> configuracionesIncompletas() {
        return Stream.of(
            Arguments.of("host", new String[] { PORT, USER, PASSWORD }),
            Arguments.of("port", new String[] { HOST, USER, PASSWORD }),
            Arguments.of("username", new String[] { HOST, PORT, PASSWORD }),
            Arguments.of("password", new String[] { HOST, PORT, USER }),
            // Es lo que deja application.yml cuando falta la variable: ${CATEDRA_REDIS_...:}.
            Arguments.of("host", new String[] { "catedra.redis.host=", PORT, USER, PASSWORD }),
            Arguments.of("port", new String[] { HOST, "catedra.redis.port=", USER, PASSWORD }),
            Arguments.of("username", new String[] { HOST, PORT, "catedra.redis.username=  ", PASSWORD }),
            Arguments.of("password", new String[] { HOST, PORT, USER, "catedra.redis.password=" }),
            Arguments.of("port", new String[] { HOST, "catedra.redis.port=0", USER, PASSWORD }),
            Arguments.of("port", new String[] { HOST, "catedra.redis.port=65536", USER, PASSWORD }),
            Arguments.of("commandTimeout", new String[] { HOST, PORT, USER, PASSWORD, "catedra.redis.command-timeout=0s" })
        );
    }

    @Test
    void toStringNoMuestraNiLaContrasenaNiElUsuario() {
        runner
            .withPropertyValues(HOST, PORT, USER, PASSWORD)
            .run(context -> {
                String texto = context.getBean(CatedraRedisProperties.class).toString();
                assertThat(texto).contains("redis.test").doesNotContain("clave-de-prueba-9z8y", "usuario-de-prueba");
            });
    }
}
