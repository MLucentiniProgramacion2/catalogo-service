package ar.edu.um.programacion2.catalogo.sync.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.security.AuthoritiesConstants;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Con el contexto completo:
 * <ul>
 * <li>la contraseña de Redis no aparece en {@code /management/env} ni en {@code /management/configprops}, ni siquiera
 * para un administrador;</li>
 * <li>no hay nada de la autoconfiguración de Redis de Spring Boot: ni una segunda conexión, ni repositorios, ni un
 * health indicator que consulte a la cátedra (usaría {@code INFO}, que la ACL niega).</li>
 * </ul>
 * <strong>Las propiedades {@code spring.data.redis.*} son una trampa a propósito</strong>, como las de Kafka en
 * {@code KafkaCatalogUpdatedIT}: si algo las leyera, aparecería otra conexión a {@code localhost:6379} o los
 * repositorios. Como en {@code CatedraTokenExposureIT}, se repite la exposición de {@code /management}, que el YAML de
 * {@code src/test} tapa.
 */
@AutoConfigureMockMvc
@WithMockUser(authorities = AuthoritiesConstants.ADMIN)
@IntegrationTest
@TestPropertySource(
    properties = {
        "management.endpoints.web.base-path=/management",
        "management.endpoints.web.exposure.include=env,configprops,health",
        "management.endpoint.health.show-details=always",
        "catedra.redis.password=clave-redis-que-no-se-muestra-4k2",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "spring.data.redis.repositories.enabled=true",
    }
)
class CatedraRedisExposureIT {

    private static final String PASSWORD = "clave-redis-que-no-se-muestra-4k2";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApplicationContext context;

    @Test
    void envMuestraLaPropiedadSinLaContrasena() throws Exception {
        String body = mvc.perform(get("/management/env")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("catedra.redis.password").doesNotContain(PASSWORD);
    }

    @Test
    void configpropsMuestraLaPropiedadSinLaContrasena() throws Exception {
        String body = mvc.perform(get("/management/configprops")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("catedra.redis").contains("\"password\"").doesNotContain(PASSWORD);
    }

    @Test
    void laUnicaConexionEsLaDeLaCatedraYNoHayRepositoriosDeRedis() {
        assertThat(context.getBeanNamesForType(RedisConnectionFactory.class)).containsExactly("catedraRedisConnectionFactory");
        assertThat(Arrays.stream(context.getBeanDefinitionNames()).map(String::toLowerCase)).noneMatch(
            name -> name.contains("keyvalue") || name.contains("redisrepositor") || name.contains("redishealth")
        );
    }

    @Test
    void laSaludNoDependeDeRedis() throws Exception {
        String body = mvc.perform(get("/management/health")).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"db\"").doesNotContainIgnoringCase("redis");
    }
}
