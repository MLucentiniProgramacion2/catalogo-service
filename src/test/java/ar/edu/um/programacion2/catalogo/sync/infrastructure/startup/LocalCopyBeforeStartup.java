package ar.edu.um.programacion2.catalogo.sync.infrastructure.startup;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.catalogo.sync.RedisCatalog;
import ar.edu.um.programacion2.catalogo.sync.RedisTestServer;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import ar.edu.um.programacion2.catalogo.sync.domain.model.FullSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.ExecuteFullSyncUseCase;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Para los tests de CU-01 con versión local: justo antes de que corra el disparador de arranque, deja la copia en
 * {@code test.startup.local-version} (con un snapshot por la cátedra simulada del test, que tiene que responder esa
 * versión) y publica en Redis {@code test.startup.redis-version}. Si Redis va adelante, cada versión que falta cambia al
 * profesional 15, que termina llamándose "Beatriz".
 * <p>
 * Igual que {@link EmptyDatabaseBeforeStartup}: corre con {@link ApplicationStartedEvent}, solo en los contextos que la
 * importan, y no es un {@code @Component}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class LocalCopyBeforeStartup {

    private final ExecuteFullSyncUseCase executeFullSyncUseCase;
    private final TransactionTemplate transactionTemplate;
    private final EntityManager em;
    private final long localVersion;
    private final long redisVersion;

    public LocalCopyBeforeStartup(
        ExecuteFullSyncUseCase executeFullSyncUseCase,
        TransactionTemplate transactionTemplate,
        EntityManager em,
        @Value("${test.startup.local-version}") long localVersion,
        @Value("${test.startup.redis-version}") long redisVersion
    ) {
        this.executeFullSyncUseCase = executeFullSyncUseCase;
        this.transactionTemplate = transactionTemplate;
        this.em = em;
        this.localVersion = localVersion;
        this.redisVersion = redisVersion;
    }

    @EventListener(ApplicationStartedEvent.class)
    public void prepareLocalCopyAndRedis() {
        SyncTestDatabase.reset(transactionTemplate, em);
        RedisTestServer.flushAll();
        assertThat(executeFullSyncUseCase.executeFullSync(SyncTrigger.MANUAL)).isEqualTo(new FullSyncResult.Applied(localVersion, 1));
        RedisCatalog redis = RedisCatalog.shared().metadata(redisVersion, 1);
        for (long version = localVersion + 1; version <= redisVersion; version++) {
            redis.professionalsChangedIn(version, 15L);
        }
        if (redisVersion > localVersion) {
            redis.professional(15, 1, "Beatriz", true);
        }
    }
}
