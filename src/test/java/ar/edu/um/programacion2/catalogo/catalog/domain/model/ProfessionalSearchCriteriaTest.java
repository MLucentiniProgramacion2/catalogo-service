package ar.edu.um.programacion2.catalogo.catalog.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProfessionalSearchCriteriaTest {

    @Test
    void sinEnabledSeTomanLosHabilitados() {
        assertThat(ProfessionalSearchCriteria.of(null, null, null).enabled()).isTrue();
    }

    @Test
    void conEnabledExplicitoSeRespeta() {
        assertThat(ProfessionalSearchCriteria.of(null, null, false).enabled()).isFalse();
        assertThat(ProfessionalSearchCriteria.of(null, null, true).enabled()).isTrue();
    }

    @Test
    void conservaLaCategoria() {
        assertThat(ProfessionalSearchCriteria.of(7L, null, null).categoryId()).isEqualTo(7L);
        assertThat(ProfessionalSearchCriteria.of(null, null, null).categoryId()).isNull();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", " ", "   ", "\t", " \n " })
    void unNombreVacioODeSoloEspaciosEsFiltroAusente(String nombre) {
        assertThat(ProfessionalSearchCriteria.of(null, nombre, null).name()).isNull();
    }

    @Test
    void recortaLosEspaciosDeLosExtremosYConservaLosInternos() {
        assertThat(ProfessionalSearchCriteria.of(null, "  ana  pe ", null).name()).isEqualTo("ana  pe");
    }

    @Test
    void elConstructorCanonicoTambienNormalizaElNombre() {
        assertThat(new ProfessionalSearchCriteria(null, "  ", true, null).name()).isNull();
        assertThat(new ProfessionalSearchCriteria(null, " Ana ", true, null).name()).isEqualTo("Ana");
    }

    @Test
    void conservaLosComodinesComoTexto() {
        // El escape para el LIKE lo hace el adaptador: el criterio guarda lo que escribió el usuario.
        assertThat(ProfessionalSearchCriteria.of(null, "50%_\\", null).name()).isEqualTo("50%_\\");
    }
}
