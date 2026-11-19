package ar.edu.um.programacion2.catalogo.sync.domain.ports.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class SnapshotRetrievalExceptionTest {

    private static final IOException CAUSA = new IOException("conexión rechazada");

    @Test
    void losTresTiposSonFallasAlObtenerElSnapshot() {
        List<SnapshotRetrievalException> fallas = List.of(
            new SnapshotSourceUnavailableException("no disponible"),
            new SnapshotAccessDeniedException("acceso denegado"),
            new InvalidSnapshotException("respuesta inválida")
        );

        assertThat(fallas).extracting(Throwable::getMessage).containsExactly("no disponible", "acceso denegado", "respuesta inválida");
    }

    @Test
    void conservanElMensajeYLaCausa() {
        assertThat(new SnapshotSourceUnavailableException("no disponible", CAUSA)).hasMessage("no disponible").hasCause(CAUSA);
        assertThat(new SnapshotAccessDeniedException("acceso denegado", CAUSA)).hasMessage("acceso denegado").hasCause(CAUSA);
        assertThat(new InvalidSnapshotException("respuesta inválida", CAUSA)).hasMessage("respuesta inválida").hasCause(CAUSA);
    }
}
