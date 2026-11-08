package ar.edu.um.programacion2.catalogo.sync.infrastructure.redis;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Cliente de solo lectura del Redis de la cátedra, armado en Java a partir de {@code catedra.redis.*} (ADR 0006).
 * <p>
 * Spring Data Redis sobre Lettuce, <strong>sin</strong> la autoconfiguración de Spring Boot (el proyecto no tiene
 * {@code spring-boot-starter-data-redis}). Medido con ese starter: sin un bean propio arma una conexión a
 * {@code localhost:6379}, y con uno propio igual activa los repositorios de Redis y un health indicator que manda
 * {@code INFO}, un comando que la ACL de la cátedra niega, así que {@code /management/health} quedaba en DOWN.
 * <p>
 * Lo que hace Lettuce, medido contra un Redis 7.4 con la misma ACL que la cátedra:
 * <ul>
 * <li>Al conectar manda {@code HELLO 3 AUTH} (permitido) y después {@code CLIENT SETINFO} con el nombre y la versión de
 * la librería. La ACL lo niega, Lettuce ignora el {@code NOPERM} y la conexión funciona. Spring Data Redis fija ese
 * nombre y no se puede apagar: queda una entrada en el ACL LOG de la cátedra por cada conexión. No manda {@code PING}.</li>
 * <li>Hay <strong>una sola conexión</strong>, compartida por todas las lecturas ({@code shareNativeConnection}, el valor
 * por defecto), y se abre recién con la primera: un Redis caído no frena el arranque.</li>
 * <li>Si la conexión se corta, la vuelve a abrir sola, en segundo plano. Mientras tanto, cada comando espera en cola
 * hasta su timeout: es el comportamiento por defecto, y se dejó a propósito porque aguanta mejor un microcorte que
 * rechazar enseguida ({@code REJECT_COMMANDS}).</li>
 * </ul>
 * <strong>Nunca subir {@code io.lettuce.core.protocol} a TRACE</strong> con la contraseña real: en ese nivel
 * {@code CommandEncoder} imprime los bytes del {@code HELLO}, contraseña incluida. En DEBUG no aparece.
 * {@code logback-spring.xml} deja {@code io.lettuce} en WARN.
 * <p>
 * Lo único que expone el adaptador es leer: el código nunca escribe en Redis.
 */
@Configuration
@EnableConfigurationProperties(CatedraRedisProperties.class)
public class CatedraRedisConfiguration {

    /**
     * Es un bean para que Spring lo arranque y lo cierre con el contexto. Implementa {@code RedisConnectionFactory}, pero
     * sin la autoconfiguración de Spring Boot no hay nada más que reaccione a eso.
     */
    @Bean
    public LettuceConnectionFactory catedraRedisConnectionFactory(CatedraRedisProperties properties) {
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(properties.getHost(), properties.getPort());
        server.setUsername(properties.getUsername());
        server.setPassword(properties.getPassword());
        ClientOptions options = ClientOptions.builder()
            .socketOptions(SocketOptions.builder().connectTimeout(properties.getConnectTimeout()).build())
            .timeoutOptions(TimeoutOptions.enabled(properties.getCommandTimeout()))
            .build();
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
            .commandTimeout(properties.getCommandTimeout())
            .clientOptions(options)
            .build();
        return new LettuceConnectionFactory(server, client);
    }

    @Bean
    public StringRedisTemplate catedraRedisTemplate(LettuceConnectionFactory catedraRedisConnectionFactory) {
        return new StringRedisTemplate(catedraRedisConnectionFactory);
    }
}
