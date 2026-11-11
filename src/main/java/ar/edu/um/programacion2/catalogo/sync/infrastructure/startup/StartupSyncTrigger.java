package ar.edu.um.programacion2.catalogo.sync.infrastructure.startup;

import ar.edu.um.programacion2.catalogo.sync.application.service.SyncService;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.config.SyncProperties;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Adaptador de entrada de CU-01: cuando la aplicación terminó de arrancar, inicializa el catálogo.
 * <p>
 * <strong>En otro hilo.</strong> Un {@code @EventListener} de {@link ApplicationReadyEvent} corre en el hilo que publica
 * el evento ({@code main}). Tomcat ya atiende en ese momento, pero {@code SpringApplication.run()} no vuelve y Spring
 * Boot recién marca la aplicación como lista para recibir tráfico (readiness) después de todos esos listeners: con el
 * peor caso de CU-02 (≈ 2,5 min) quedaría todo ese tiempo "no lista". Por eso el listener solo entrega la tarea al
 * executor que ya define JHipster ({@code AsyncConfiguration}) y vuelve enseguida. En los tests ese executor es
 * sincrónico ({@code AsyncSyncConfiguration}), así que el arranque se puede verificar sin esperas.
 * <p>
 * <strong>Nunca propaga.</strong> Una excepción que saliera de un listener de {@link ApplicationReadyEvent} haría fallar
 * el arranque. CU-01 (flujo 2a) pide lo contrario: el servicio queda levantado aunque el snapshot falle.
 * <p>
 * Se apaga con {@code sync.startup.enabled=false}, como en {@code src/test}: si no, cada contexto de test llamaría a la
 * cátedra falsa y escribiría {@code sync_status}.
 */
@Component
public class StartupSyncTrigger {

    private static final Logger LOG = LoggerFactory.getLogger(StartupSyncTrigger.class);

    private final SyncService syncService;
    private final SyncProperties syncProperties;
    private final Executor taskExecutor;

    /**
     * El executor va con {@code @Qualifier("taskExecutor")}, como en {@code LiquibaseConfiguration} de JHipster: además
     * de ese existe el {@code taskScheduler} de {@code @EnableScheduling}, que también es un {@link Executor}.
     * <p>
     * Constructor explícito y no {@code @RequiredArgsConstructor}: Lombok no copia un {@code @Qualifier} del campo al
     * parámetro del constructor sin configurarlo en un {@code lombok.config}, y el proyecto no tiene uno.
     */
    public StartupSyncTrigger(SyncService syncService, SyncProperties syncProperties, @Qualifier("taskExecutor") Executor taskExecutor) {
        this.syncService = syncService;
        this.syncProperties = syncProperties;
        this.taskExecutor = taskExecutor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!syncProperties.getStartup().isEnabled()) {
            LOG.info("Arranque: la inicialización del catálogo está desactivada (sync.startup.enabled=false)");
            return;
        }
        taskExecutor.execute(this::initializeCatalog);
    }

    void initializeCatalog() {
        try {
            syncService.initializeCatalog();
        } catch (RuntimeException e) {
            LOG.error("Arranque: no se pudo inicializar el catálogo; el servicio sigue levantado", e);
        }
    }
}
