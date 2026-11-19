package ar.edu.um.programacion2.catalogo.sync.infrastructure.startup;

import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import jakarta.persistence.EntityManager;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Para los tests de CU-01: deja la base como recién instalada justo antes de que corra el disparador de arranque.
 * <p>
 * {@link ApplicationStartedEvent} se publica después de crear el contexto (Liquibase ya corrió) y antes de
 * {@code ApplicationReadyEvent}. Así "base vacía al arrancar" no depende de lo que hayan dejado los tests anteriores,
 * que comparten el mismo PostgreSQL.
 * <p>
 * Solo existe en los contextos que la importan ({@code @Import} en {@link StartupFullSyncIT} y
 * {@link StartupFullSyncFailureIT}). No es un {@code @Component}, y el escaneo de componentes de los
 * {@code @SpringBootTest} excluye las {@code @TestConfiguration}: si la encontrara, cualquier contexto de test vaciaría
 * la base al arrancar. {@code FullSyncIT} verifica que en un contexto que no la importa no está.
 */
@TestConfiguration(proxyBeanMethods = false)
public class EmptyDatabaseBeforeStartup {

    private final TransactionTemplate transactionTemplate;
    private final EntityManager em;

    public EmptyDatabaseBeforeStartup(TransactionTemplate transactionTemplate, EntityManager em) {
        this.transactionTemplate = transactionTemplate;
        this.em = em;
    }

    @EventListener(ApplicationStartedEvent.class)
    public void emptyDatabase() {
        SyncTestDatabase.reset(transactionTemplate, em);
    }
}
