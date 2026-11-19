package ar.edu.um.programacion2.catalogo.sync.infrastructure.periodic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ar.edu.um.programacion2.catalogo.sync.infrastructure.config.SyncProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/** Cómo se programa la comprobación periódica, sin esperar al reloj: se mira el registro y se corre la tarea a mano. */
@ExtendWith(MockitoExtension.class)
class PeriodicSyncConfigurationTest {

    @Mock
    private PeriodicSyncTrigger trigger;

    private final SyncProperties properties = new SyncProperties();

    @Test
    void prendidaRegistraUnaTareaConDemoraFijaDelIntervaloYLaPrimeraUnIntervaloDespues() {
        properties.getPeriodic().setInterval(Duration.ofMinutes(7));
        ScheduledTaskRegistrar registro = new ScheduledTaskRegistrar();

        new PeriodicSyncConfiguration(properties, trigger).configureTasks(registro);

        assertThat(registro.getFixedRateTaskList()).isEmpty();
        assertThat(registro.getCronTaskList()).isEmpty();
        assertThat(registro.getFixedDelayTaskList()).hasSize(1);
        FixedDelayTask tarea = (FixedDelayTask) registro.getFixedDelayTaskList().getFirst();
        assertThat(tarea.getIntervalDuration()).isEqualTo(Duration.ofMinutes(7));
        assertThat(tarea.getInitialDelayDuration()).isEqualTo(Duration.ofMinutes(7));

        tarea.getRunnable().run();
        verify(trigger).checkCatalogVersion();
    }

    @Test
    void apagadaNoRegistraNada() {
        properties.getPeriodic().setEnabled(false);
        ScheduledTaskRegistrar registro = new ScheduledTaskRegistrar();

        new PeriodicSyncConfiguration(properties, trigger).configureTasks(registro);

        assertThat(registro.hasTasks()).isFalse();
        verifyNoInteractions(trigger);
    }

    @Test
    void porDefectoEstaPrendidaCadaCincoMinutos() {
        assertThat(properties.getPeriodic().isEnabled()).isTrue();
        assertThat(properties.getPeriodic().getInterval()).isEqualTo(Duration.ofMinutes(5));
    }
}
