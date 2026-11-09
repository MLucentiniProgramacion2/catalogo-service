package ar.edu.um.programacion2.catalogo.sync.infrastructure.periodic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import ar.edu.um.programacion2.catalogo.sync.application.service.SyncService;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.config.SyncProperties;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.DefaultLifecycleProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Qué pasa si el servicio se apaga en medio de una pasada, con la configuración real de la tarea y el mismo scheduler que
 * arma Spring Boot ({@link ThreadPoolTaskScheduler}, con {@code spring.task.scheduling.shutdown.*} por defecto).
 * <p>
 * Spring espera a la pasada en curso hasta el plazo de la fase de apagado ({@code spring.lifecycle.timeout-per-shutdown-phase},
 * 30 s por defecto en Boot; acá, corto) y recién ahí la interrumpe. Una pasada al día (menos de 1 s) termina normal; una
 * larga se interrumpe, y su versión en curso hace rollback porque cada versión es su propia transacción. La pasada se
 * simula con un latch: sin {@code sleep}.
 */
class PeriodicSyncShutdownTest {

    @Test
    void unaPasadaQueTerminaDentroDelPlazoNoSeInterrumpe() throws Exception {
        Pasada pasada = new Pasada();
        AnnotationConfigApplicationContext context = contexto(Duration.ofSeconds(20), pasada);

        assertThat(pasada.empezo.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> cierre = CompletableFuture.runAsync(context::close);
        pasada.soltar.countDown();
        cierre.get(15, TimeUnit.SECONDS);

        assertThat(pasada.termino.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(pasada.fin.get()).isEqualTo("terminó");
    }

    @Test
    void unaPasadaQueSuperaElPlazoSeInterrumpeYElContextoCierraIgual() throws Exception {
        // Nadie suelta la pasada: solo termina si la interrumpen.
        Pasada pasada = new Pasada();
        AnnotationConfigApplicationContext context = contexto(Duration.ofMillis(300), pasada);

        assertThat(pasada.empezo.await(10, TimeUnit.SECONDS)).isTrue();
        context.close();

        // close() vuelve apenas interrumpe el hilo, sin esperar a que la pasada lo note: se espera a que termine.
        assertThat(pasada.termino.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(pasada.fin.get()).isEqualTo("interrumpida");
    }

    /** Una pasada simulada: avisa cuando empieza, espera a que la suelten y avisa cuando termina y cómo. */
    private static final class Pasada {

        private final CountDownLatch empezo = new CountDownLatch(1);
        private final CountDownLatch soltar = new CountDownLatch(1);
        private final CountDownLatch termino = new CountDownLatch(1);
        private final AtomicReference<String> fin = new AtomicReference<>("sin terminar");

        void correr() {
            empezo.countDown();
            try {
                soltar.await(30, TimeUnit.SECONDS);
                fin.set("terminó");
            } catch (InterruptedException e) {
                fin.set("interrumpida");
                Thread.currentThread().interrupt();
            } finally {
                termino.countDown();
            }
        }
    }

    /** La configuración real de la tarea, con la primera pasada enseguida. */
    private static AnnotationConfigApplicationContext contexto(Duration plazoDeApagado, Pasada pasada) {
        SyncService syncService = mock(SyncService.class);
        doAnswer(invocation -> {
            pasada.correr();
            return null;
        })
            .when(syncService)
            .checkCatalogVersion();
        SyncProperties properties = new SyncProperties();
        // Sin validar: el mínimo de 1 min es de la configuración; acá la primera pasada tiene que empezar ya.
        properties.getPeriodic().setInterval(Duration.ofMillis(10));

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(SyncProperties.class, () -> properties);
        context.registerBean(PeriodicSyncTrigger.class, () -> new PeriodicSyncTrigger(syncService));
        context.registerBean("taskScheduler", ThreadPoolTaskScheduler.class, ThreadPoolTaskScheduler::new);
        context.registerBean("lifecycleProcessor", DefaultLifecycleProcessor.class, () -> {
            DefaultLifecycleProcessor processor = new DefaultLifecycleProcessor();
            processor.setTimeoutPerShutdownPhase(plazoDeApagado.toMillis());
            return processor;
        });
        context.register(PeriodicSyncConfiguration.class);
        context.refresh();
        return context;
    }
}
