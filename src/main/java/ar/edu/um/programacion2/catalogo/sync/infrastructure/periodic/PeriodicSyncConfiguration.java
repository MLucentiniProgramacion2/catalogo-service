package ar.edu.um.programacion2.catalogo.sync.infrastructure.periodic;

import ar.edu.um.programacion2.catalogo.sync.infrastructure.config.SyncProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Programa la comprobación periódica de versión (DP-04) con {@code sync.periodic.*}.
 * <p>
 * <strong>{@link SchedulingConfigurer} y no {@code @Scheduled(fixedDelayString = ...)}</strong>: así se usa el
 * {@link Duration} ya validado de {@link SyncProperties} (con su mínimo de 1 min), y con {@code enabled=false} la tarea
 * ni se registra. {@code @Scheduled} lee el texto de la propiedad por su cuenta, sin esa validación (con {@code 0} no
 * arranca, pero el error es un {@code IllegalArgumentException} sin mensaje), y quedaría registrada aunque esté apagada.
 * <p>
 * <strong>{@code fixedDelay} y no {@code fixedRate}</strong>: el intervalo se cuenta desde que termina una pasada.
 * Después de un snapshot largo (hasta 297 s con la cátedra caída), {@code fixedRate} encadenaría pasadas una atrás de
 * otra. La primera es un intervalo después de arrancar: CU-01 ya compara al arrancar, y si sigue corriendo, la pasada se
 * saltea (no espera la exclusión). Corre en el hilo del scheduler, sin pasar a otro executor: si no, {@code fixedDelay}
 * no esperaría a que termine.
 * <p>
 * <strong>{@code @EnableScheduling} propio.</strong> Lo tiene también {@code AsyncConfiguration} de JHipster, pero esa
 * clase está apagada en los perfiles de test ({@code testdev} y {@code testprod}): sin este, en los tests no habría
 * scheduler y la tarea no se registraría nunca. Repetirlo no tiene efecto. El scheduler es el {@code taskScheduler} que
 * arma Spring Boot ({@code spring.task.scheduling.*}); nadie más lo usa.
 */
@Configuration
@EnableScheduling
public class PeriodicSyncConfiguration implements SchedulingConfigurer {

    private static final Logger LOG = LoggerFactory.getLogger(PeriodicSyncConfiguration.class);

    private final SyncProperties syncProperties;
    private final PeriodicSyncTrigger periodicSyncTrigger;

    public PeriodicSyncConfiguration(SyncProperties syncProperties, PeriodicSyncTrigger periodicSyncTrigger) {
        this.syncProperties = syncProperties;
        this.periodicSyncTrigger = periodicSyncTrigger;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        SyncProperties.Periodic periodic = syncProperties.getPeriodic();
        if (!periodic.isEnabled()) {
            LOG.info("Comprobación periódica de versión desactivada (sync.periodic.enabled=false)");
            return;
        }
        Duration interval = periodic.getInterval();
        registrar.addFixedDelayTask(new FixedDelayTask(periodicSyncTrigger::checkCatalogVersion, interval, interval));
        LOG.info("Comprobación periódica de versión cada {}", interval);
    }
}
