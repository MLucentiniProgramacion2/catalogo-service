package ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.entity.ProfessionalCategoryEntity;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProfessionalCategoryMapperTest {

    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");
    private static final Instant ACTUALIZADO = Instant.parse("2026-09-10T13:40:00Z");

    private final ProfessionalCategoryMapper mapper = new ProfessionalCategoryMapper();

    @Test
    void idaYVueltaConservaTodosLosCampos() {
        ProfessionalCategory categoria = new ProfessionalCategory(
            1,
            "Clinica medica",
            "Atencion clinica general",
            false,
            CREADO,
            ACTUALIZADO
        );

        assertThat(mapper.toDomain(mapper.toEntity(categoria))).isEqualTo(categoria);
    }

    @Test
    void idaYVueltaConservaUnaDescripcionNula() {
        ProfessionalCategory categoria = new ProfessionalCategory(1, "Clinica medica", null, true, CREADO, ACTUALIZADO);

        assertThat(mapper.toDomain(mapper.toEntity(categoria)).description()).isNull();
    }

    @Test
    void unEnabledNuloEnLaBaseFallaEnVezDeLeerseComoFalse() {
        ProfessionalCategoryEntity entidad = mapper.toEntity(
            new ProfessionalCategory(1, "Clinica medica", null, true, CREADO, ACTUALIZADO)
        );
        entidad.setEnabled(null);

        assertThatNullPointerException()
            .isThrownBy(() -> mapper.toDomain(entidad))
            .withMessage("La categoría 1 tiene enabled nulo en la base");
    }
}
