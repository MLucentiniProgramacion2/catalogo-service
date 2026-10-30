package ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.entity.ProfessionalCategoryEntity;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.entity.ProfessionalEntity;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProfessionalMapperTest {

    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");
    private static final Instant ACTUALIZADO = Instant.parse("2026-09-10T13:40:00Z");
    private static final Professional PROFESIONAL = new Professional(15, 1, "Ana", "Gomez", false, CREADO, ACTUALIZADO);

    private final ProfessionalMapper mapper = new ProfessionalMapper();

    @Test
    void toEntityUsaLaReferenciaRecibida() {
        ProfessionalCategoryEntity referencia = categoria(1L);

        assertThat(mapper.toEntity(PROFESIONAL, referencia).getCategory()).isSameAs(referencia);
    }

    @Test
    void idaYVueltaConservaTodosLosCampos() {
        assertThat(mapper.toDomain(mapper.toEntity(PROFESIONAL, categoria(1L)))).isEqualTo(PROFESIONAL);
    }

    @Test
    void rechazaUnaReferenciaAOtraCategoria() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> mapper.toEntity(PROFESIONAL, categoria(2L)))
            .withMessage("La referencia a la categoría 2 no coincide con la categoría 1 del profesional 15");
    }

    @Test
    void unEnabledNuloEnLaBaseFallaEnVezDeLeerseComoFalse() {
        ProfessionalEntity entidad = mapper.toEntity(PROFESIONAL, categoria(1L));
        entidad.setEnabled(null);

        assertThatNullPointerException()
            .isThrownBy(() -> mapper.toDomain(entidad))
            .withMessage("El profesional 15 tiene enabled nulo en la base");
    }

    private static ProfessionalCategoryEntity categoria(Long id) {
        ProfessionalCategoryEntity categoria = new ProfessionalCategoryEntity();
        categoria.setId(id);
        return categoria;
    }
}
