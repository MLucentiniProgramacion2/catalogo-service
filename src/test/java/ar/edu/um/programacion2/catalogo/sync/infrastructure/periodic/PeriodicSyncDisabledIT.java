package ar.edu.um.programacion2.catalogo.sync.infrastructure.periodic;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * Con {@code sync.periodic.enabled=false} (lo de {@code src/test}) la tarea ni se registra. Usa la misma configuración
 * que la mayoría de los IT, así que comparte su contexto: es el caso de todos los demás tests.
 */
@IntegrationTest
class PeriodicSyncDisabledIT {

    @Autowired
    private ApplicationContext context;

    @Test
    void apagadaNoQuedaNingunaTareaProgramadaAunqueHayaScheduler() {
        // Hay scheduler (el @EnableScheduling de la feature funciona también en los perfiles de test): la tarea falta
        // porque está apagada, no porque no haya dónde programarla.
        assertThat(context.getBeansOfType(ScheduledTaskHolder.class)).isNotEmpty();
        assertThat(context.getBeansOfType(ScheduledTaskHolder.class).values()).allSatisfy(holder ->
            assertThat(holder.getScheduledTasks()).isEmpty()
        );
    }
}
