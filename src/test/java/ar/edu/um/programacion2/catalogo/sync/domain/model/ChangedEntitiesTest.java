package ar.edu.um.programacion2.catalogo.sync.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ChangedEntitiesTest {

    private static final Instant T = Instant.parse("2026-09-01T03:00:00Z");

    @Test
    void informaLosIdsListadosQueNoSeEncontraron() {
        ChangedEntities leidas = new ChangedEntities(List.of(), List.of(profesional(201, 101)), List.of());
        VersionChanges cambios = new VersionChanges(89, Set.of(105L), Set.of(201L, 202L), Set.of(342L));

        assertThat(leidas.missingFrom(cambios)).containsExactly("la categoría 105", "el profesional 202", "el horario 342");
    }

    @Test
    void sinFaltantesDevuelveVacio() {
        ChangedEntities leidas = new ChangedEntities(List.of(), List.of(profesional(201, 101)), List.of());

        assertThat(leidas.missingFrom(new VersionChanges(89, Set.of(), Set.of(201L), Set.of()))).isEmpty();
    }

    @Test
    void lasReferenciasQueResuelveLaMismaVersionNoHayQueBuscarlasEnLaCopia() {
        ChangedEntities leidas = new ChangedEntities(
            List.of(categoria(105)),
            List.of(profesional(201, 101), profesional(202, 105)),
            List.of(horario(342, 202), horario(343, 223))
        );

        assertThat(leidas.categoryIdsOutside()).containsExactly(101L);
        assertThat(leidas.professionalIdsOutside()).containsExactly(223L);
    }

    @Test
    void unaReferenciaQueNoEstaNiEnLaVersionNiEnLaCopiaEstaRota() {
        ChangedEntities leidas = new ChangedEntities(List.of(), List.of(profesional(201, 101)), List.of(horario(342, 223)));

        assertThat(leidas.brokenReferences(Set.of(101L), Set.of(223L))).isEmpty();
        assertThat(leidas.brokenReferences(Set.of(), Set.of())).containsExactly(
            "el profesional 201 apunta a la categoría 101, que no existe",
            "el horario 342 apunta al profesional 223, que no existe"
        );
    }

    private static ProfessionalCategory categoria(long id) {
        return new ProfessionalCategory(id, "Categoría " + id, null, true, T, T);
    }

    private static Professional profesional(long id, long categoria) {
        return new Professional(id, categoria, "Ana", "Torres", true, T, T);
    }

    private static WeeklySchedule horario(long id, long profesional) {
        return new WeeklySchedule(id, profesional, DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0), 30, true, T, T);
    }
}
