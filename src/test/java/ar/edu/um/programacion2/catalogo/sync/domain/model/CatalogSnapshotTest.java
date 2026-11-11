package ar.edu.um.programacion2.catalogo.sync.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogSnapshotTest {

    private static final Instant GENERADO = Instant.parse("2026-09-10T13:44:59Z");
    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");
    private static final Instant ACTUALIZADO = Instant.parse("2026-09-10T13:40:00Z");

    private static final ProfessionalCategory CATEGORIA = new ProfessionalCategory(
        1,
        "Clinica medica",
        "Atencion clinica general",
        true,
        CREADO,
        ACTUALIZADO
    );
    private static final Professional PROFESIONAL = new Professional(15, 1, "Ana", "Gomez", true, CREADO, ACTUALIZADO);
    private static final WeeklySchedule HORARIO = new WeeklySchedule(
        21,
        15,
        DayOfWeek.MONDAY,
        LocalTime.of(9, 0),
        LocalTime.of(12, 0),
        30,
        true,
        CREADO,
        ACTUALIZADO
    );

    @Test
    void conservaLaVersionLaFechaYLasTresColecciones() {
        CatalogSnapshot snapshot = new CatalogSnapshot(7, GENERADO, List.of(CATEGORIA), List.of(PROFESIONAL), List.of(HORARIO));

        assertThat(snapshot.snapshotVersion()).isEqualTo(7);
        assertThat(snapshot.generatedAt()).isEqualTo(GENERADO);
        assertThat(snapshot.professionalCategories()).containsExactly(CATEGORIA);
        assertThat(snapshot.professionals()).containsExactly(PROFESIONAL);
        assertThat(snapshot.weeklySchedules()).containsExactly(HORARIO);
    }

    @Test
    void aceptaColeccionesVacias() {
        CatalogSnapshot snapshot = new CatalogSnapshot(7, GENERADO, List.of(), List.of(), List.of());

        assertThat(snapshot.professionalCategories()).isEmpty();
        assertThat(snapshot.professionals()).isEmpty();
        assertThat(snapshot.weeklySchedules()).isEmpty();
    }

    @Test
    void lasColeccionesNoSePuedenModificar() {
        CatalogSnapshot snapshot = new CatalogSnapshot(7, GENERADO, List.of(CATEGORIA), List.of(PROFESIONAL), List.of(HORARIO));

        assertThatThrownBy(() -> snapshot.professionalCategories().add(CATEGORIA)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.professionals().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.weeklySchedules().remove(0)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void copiaLasListasRecibidas() {
        List<Professional> profesionales = new ArrayList<>(List.of(PROFESIONAL));
        CatalogSnapshot snapshot = new CatalogSnapshot(7, GENERADO, List.of(), profesionales, List.of());

        profesionales.clear();

        assertThat(snapshot.professionals()).containsExactly(PROFESIONAL);
    }

    @Test
    void rechazaGeneratedAtNulo() {
        assertThatNullPointerException()
            .isThrownBy(() -> new CatalogSnapshot(7, null, List.of(), List.of(), List.of()))
            .withMessageContaining("generatedAt");
    }

    @Test
    void rechazaCategoriasAusentes() {
        assertThatNullPointerException()
            .isThrownBy(() -> new CatalogSnapshot(7, GENERADO, null, List.of(), List.of()))
            .withMessageContaining("professionalCategories");
    }

    @Test
    void rechazaProfesionalesAusentes() {
        assertThatNullPointerException()
            .isThrownBy(() -> new CatalogSnapshot(7, GENERADO, List.of(), null, List.of()))
            .withMessageContaining("professionals");
    }

    @Test
    void rechazaHorariosAusentes() {
        assertThatNullPointerException()
            .isThrownBy(() -> new CatalogSnapshot(7, GENERADO, List.of(), List.of(), null))
            .withMessageContaining("weeklySchedules");
    }

    @Test
    void rechazaUnElementoNulo() {
        assertThatNullPointerException().isThrownBy(() ->
            new CatalogSnapshot(7, GENERADO, List.of(), Arrays.asList(PROFESIONAL, null), List.of())
        );
    }

    @Test
    void unSnapshotCoherenteNoTieneReferenciasRotas() {
        CatalogSnapshot snapshot = new CatalogSnapshot(7, GENERADO, List.of(CATEGORIA), List.of(PROFESIONAL), List.of(HORARIO));

        assertThat(snapshot.brokenReferences()).isEmpty();
    }

    @Test
    void unSnapshotVacioNoTieneReferenciasRotas() {
        assertThat(new CatalogSnapshot(7, GENERADO, List.of(), List.of(), List.of()).brokenReferences()).isEmpty();
    }

    @Test
    void informaUnProfesionalCuyaCategoriaNoEstaEnElSnapshot() {
        Professional huerfano = new Professional(16, 999, "Luis", "Perez", true, CREADO, ACTUALIZADO);
        CatalogSnapshot snapshot = new CatalogSnapshot(7, GENERADO, List.of(CATEGORIA), List.of(PROFESIONAL, huerfano), List.of());

        assertThat(snapshot.brokenReferences()).containsExactly(
            "el profesional 16 apunta a la categoría 999, que no está en el snapshot"
        );
    }

    @Test
    void informaUnHorarioCuyoProfesionalNoEstaEnElSnapshot() {
        WeeklySchedule huerfano = horarioDe(22, 99);
        CatalogSnapshot snapshot = new CatalogSnapshot(7, GENERADO, List.of(CATEGORIA), List.of(PROFESIONAL), List.of(HORARIO, huerfano));

        assertThat(snapshot.brokenReferences()).containsExactly("el horario 22 apunta al profesional 99, que no está en el snapshot");
    }

    @Test
    void informaTodasLasReferenciasRotasPrimeroLasDeProfesionales() {
        Professional huerfano = new Professional(16, 999, "Luis", "Perez", true, CREADO, ACTUALIZADO);
        WeeklySchedule horarioHuerfano = horarioDe(22, 99);
        CatalogSnapshot snapshot = new CatalogSnapshot(
            7,
            GENERADO,
            List.of(),
            List.of(PROFESIONAL, huerfano),
            List.of(horarioHuerfano, HORARIO)
        );

        assertThat(snapshot.brokenReferences()).containsExactly(
            "el profesional 15 apunta a la categoría 1, que no está en el snapshot",
            "el profesional 16 apunta a la categoría 999, que no está en el snapshot",
            "el horario 22 apunta al profesional 99, que no está en el snapshot"
        );
    }

    @Test
    void lasEntidadesDeshabilitadasCuentanComoPresentes() {
        ProfessionalCategory deshabilitada = new ProfessionalCategory(1, "Clinica medica", null, false, CREADO, ACTUALIZADO);
        Professional profesionalDeshabilitado = new Professional(15, 1, "Ana", "Gomez", false, CREADO, ACTUALIZADO);
        CatalogSnapshot snapshot = new CatalogSnapshot(
            7,
            GENERADO,
            List.of(deshabilitada),
            List.of(profesionalDeshabilitado),
            List.of(HORARIO)
        );

        assertThat(snapshot.brokenReferences()).isEmpty();
    }

    private static WeeklySchedule horarioDe(long id, long professionalId) {
        LocalTime nueve = LocalTime.of(9, 0);
        return new WeeklySchedule(id, professionalId, DayOfWeek.TUESDAY, nueve, nueve.plusHours(1), 30, true, CREADO, ACTUALIZADO);
    }
}
