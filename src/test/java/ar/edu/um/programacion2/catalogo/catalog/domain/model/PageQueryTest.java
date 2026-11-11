package ar.edu.um.programacion2.catalogo.catalog.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PageQueryTest {

    @Test
    void aceptaLosBordes() {
        assertThat(new PageQuery(0, 1)).isEqualTo(new PageQuery(0, 1));
        assertThat(new PageQuery(0, PageQuery.MAX_SIZE).size()).isEqualTo(100);
        assertThat(new PageQuery(Integer.MAX_VALUE, 20).number()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void rechazaUnNumeroNegativo() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PageQuery(-1, 20)).withMessageContaining("negativo");
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, -1, 101 })
    void rechazaUnTamanoFueraDeRango(int tamano) {
        assertThatIllegalArgumentException().isThrownBy(() -> new PageQuery(0, tamano)).withMessageContaining("entre 1 y 100");
    }
}
