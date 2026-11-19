package ar.edu.um.programacion2.catalogo.catalog.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class VersionedTest {

    @Test
    void guardaLaVersionYElContenido() {
        Versioned<List<String>> versionado = new Versioned<>(3, List.of("a"));

        assertThat(versionado.version()).isEqualTo(3);
        assertThat(versionado.content()).containsExactly("a");
    }

    @Test
    void unaListaVaciaEsUnContenidoValido() {
        assertThat(new Versioned<>(1, List.of()).content()).isEqualTo(List.of());
    }

    @Test
    void rechazaElContenidoNulo() {
        assertThatThrownBy(() -> new Versioned<>(1, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("Falta el contenido de la versión 1");
    }
}
