package ar.edu.um.programacion2.catalogo.sync.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

/**
 * El log de cada intento fallido de un aviso nombra la falla real. Caso encontrado contra la cátedra (#35): falló el
 * commit del offset porque el consumidor había quedado fuera del grupo, y el log decía {@code null}.
 */
@ExtendWith(OutputCaptureExtension.class)
class CatedraKafkaRetryLogTest {

    private static final ConsumerRecord<String, String> AVISO = new ConsumerRecord<>("catedra.catalog.grupo", 0, 127, "90", "{}");

    @Test
    void unaFallaDelCommitDelOffsetLlegaSinEnvolverYSeLogueaConSuClaseYMensaje(CapturedOutput salida) {
        // Con AckMode.RECORD, commitSync corre en el mismo try que el listener: la excepción llega tal cual y sin causa.
        CommitFailedException commit = new CommitFailedException("el consumidor quedó fuera del grupo");
        assertThat(commit.getCause()).isNull();

        CatedraKafkaConfiguration.retryListener().failedDelivery(AVISO, commit, 1);

        assertThat(salida.getAll())
            .contains(
                "Aviso de catálogo: falló el intento 1 (partición 0, offset 127): org.apache.kafka.clients.consumer.CommitFailedException: el consumidor quedó fuera del grupo"
            )
            .doesNotContain("offset 127): null");
    }

    @Test
    void loQueLanzaElListenerSeDesenvuelveUnaVez() {
        ListenerExecutionFailedException envuelta = new ListenerExecutionFailedException(
            "Listener failed",
            new DataAccessResourceFailureException("sin conexión")
        );

        assertThat(CatedraKafkaConfiguration.describe(envuelta)).isEqualTo(
            "org.springframework.dao.DataAccessResourceFailureException: sin conexión"
        );
    }

    @Test
    void soloSeDesenvuelveListenerExecutionFailedException() {
        IllegalStateException otra = new IllegalStateException("afuera", new IllegalArgumentException("adentro"));

        assertThat(CatedraKafkaConfiguration.describe(otra)).isEqualTo("java.lang.IllegalStateException: afuera");
    }

    @Test
    void unaListenerExecutionFailedExceptionSinCausaSeLogueaElla() {
        assertThat(CatedraKafkaConfiguration.describe(new ListenerExecutionFailedException("Listener failed"))).isEqualTo(
            "org.springframework.kafka.listener.ListenerExecutionFailedException: Listener failed"
        );
    }

    @Test
    void sinMensajeQuedaSoloLaClase() {
        assertThat(CatedraKafkaConfiguration.describe(new IllegalStateException())).isEqualTo("java.lang.IllegalStateException");
    }
}
