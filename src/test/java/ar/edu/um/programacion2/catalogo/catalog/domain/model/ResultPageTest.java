package ar.edu.um.programacion2.catalogo.catalog.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ResultPageTest {

    @ParameterizedTest
    @CsvSource({ "0, 20, 0", "1, 20, 1", "20, 20, 1", "21, 20, 2", "24, 20, 2", "40, 20, 2", "41, 20, 3", "5, 1, 5" })
    void calculaLaCantidadDePaginas(long total, int tamano, int paginas) {
        assertThat(new ResultPage<>(List.of(), 0, tamano, total).totalPages()).isEqualTo(paginas);
    }

    @Test
    void unaPaginaFueraDeRangoEsValida() {
        ResultPage<String> pagina = new ResultPage<>(List.of(), 50, 20, 24);

        assertThat(pagina.items()).isEmpty();
        assertThat(pagina.number()).isEqualTo(50);
        assertThat(pagina.totalElements()).isEqualTo(24);
        assertThat(pagina.totalPages()).isEqualTo(2);
    }

    @Test
    void copiaLaListaParaQueNoCambieDespues() {
        List<String> elementos = new ArrayList<>(List.of("a"));
        ResultPage<String> pagina = new ResultPage<>(elementos, 0, 20, 1);

        elementos.add("b");

        assertThat(pagina.items()).containsExactly("a");
        assertThatThrownBy(() -> pagina.items().add("c")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rechazaLosElementosNulos() {
        assertThatNullPointerException().isThrownBy(() -> new ResultPage<>(null, 0, 20, 0));
    }

    @Test
    void rechazaDatosImposibles() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ResultPage<>(List.of(), -1, 20, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new ResultPage<>(List.of(), 0, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new ResultPage<>(List.of(), 0, 20, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> new ResultPage<>(List.of("a", "b"), 0, 1, 2));
    }
}
