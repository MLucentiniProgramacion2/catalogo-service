package ar.edu.um.programacion2.catalogo.sync.infrastructure.config;

import ar.edu.um.programacion2.catalogo.sync.application.usecases.RetryBackoff;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncLock;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.lock.InMemorySyncLock;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Arma, a partir de {@link SyncProperties}, lo que los casos de uso necesitan y depende de la configuración: el reloj, la
 * política de reintentos y la exclusión mutua. {@code application} no puede depender de esta clase ni de las propiedades (ArchUnit), así que recibe
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

    /** La exclusión mutua de DP-09, en memoria: una sola instancia del servicio. */
    @Bean
    public SyncLock syncLock(SyncProperties properties) {
        return new InMemorySyncLock(properties.getLock().getWaitTimeout());
    }
}
