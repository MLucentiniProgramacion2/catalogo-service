package ar.edu.um.programacion2.catalogo.sync.infrastructure.config;

import ar.edu.um.programacion2.catalogo.sync.application.usecases.RetryBackoff;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Arma, a partir de {@link SyncProperties}, lo que los casos de uso necesitan y no son puertos: el reloj y la política
 * de reintentos. {@code application} no puede depender de esta clase ni de las propiedades (ArchUnit), así que recibe
 * los objetos ya armados.
 */
@Configuration
@EnableConfigurationProperties(SyncProperties.class)
public class SyncConfiguration {

    /** El "ahora" de la sincronización, en UTC. Los tests lo reemplazan por un reloj fijo. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** Las esperas de DP-08, durmiendo de verdad el hilo que sincroniza. */
    @Bean
    public RetryBackoff retryBackoff(SyncProperties properties) {
        return new RetryBackoff(properties.getRetry().getDelays(), Thread::sleep);
    }
}
