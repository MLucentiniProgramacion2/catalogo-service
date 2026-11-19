package ar.edu.um.programacion2.catalogo.sync.infrastructure.catedra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.catedra.CatedraEntityJson.CategoryJson;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.catedra.CatedraEntityJson.ProfessionalJson;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.catedra.CatedraEntityJson.WeeklyScheduleJson;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Las reglas de formato compartidas por el snapshot y Redis, con los JSON tal como están en el Redis real (vistos el 7/11
 * con {@code HGET} y {@code HMGET}).
 */
class CatedraEntityMapperTest {

    /** {@code HGET catedra:sync:professionals 201}: {@code updatedAt} con 6 decimales. */
    private static final String PROFESIONAL_201 = """
        {"id":201,"categoryId":101,"firstName":"Ana","lastName":"Torres","enabled":true,"createdAt":"2026-09-01T03:00:00Z","updatedAt":"2026-11-08T01:19:34.423296Z"}""";

    /** {@code HGET catedra:sync:weekly-schedules 342}: horas {@code HH:mm:ss}. */
    private static final String HORARIO_342 = """
        {"id":342,"professionalId":223,"dayOfWeek":"THURSDAY","startTime":"16:00:00","endTime":"19:00:00","slotDurationMinutes":30,"enabled":true,"createdAt":"2026-09-01T03:00:00Z","updatedAt":"2026-09-01T03:00:00Z"}""";

    @Test
    void traduceElProfesionalRealDeRedis() {
        Professional profesional = CatedraEntityMapper.professional(read(PROFESIONAL_201, ProfessionalJson.class));

        assertThat(profesional).isEqualTo(
            new Professional(
                201,
                101,
                "Ana",
                "Torres",
                true,
                Instant.parse("2026-09-01T03:00:00Z"),
                Instant.parse("2026-11-08T01:19:34.423296Z")
            )
        );
    }

    @Test
    void traduceElHorarioRealDeRedis() {
        WeeklySchedule horario = CatedraEntityMapper.schedule(read(HORARIO_342, WeeklyScheduleJson.class));

        assertThat(horario.dayOfWeek()).isEqualTo(DayOfWeek.THURSDAY);
        assertThat(horario.startTime()).isEqualTo(LocalTime.of(16, 0));
        assertThat(horario.endTime()).isEqualTo(LocalTime.of(19, 0));
        assertThat(horario.slotDurationMinutes()).isEqualTo(30);
    }

    /** Los tres aparecen en el Redis real: sin decimales, con 6 y con 9 ({@code publishedAt} de la metadata). */
    @ParameterizedTest
    @ValueSource(strings = { "2026-11-08T01:19:34Z", "2026-11-08T01:19:34.423296Z", "2026-11-08T01:19:34.443824516Z" })
    void aceptaInstantesConCeroSeisONueveDecimales(String instante) {
        String json = PROFESIONAL_201.replace("2026-11-08T01:19:34.423296Z", instante);

        assertThat(CatedraEntityMapper.professional(read(json, ProfessionalJson.class)).updatedAt()).isEqualTo(Instant.parse(instante));
    }

    @Test
    void aceptaHorasSinSegundos() {
        String json = HORARIO_342.replace("\"16:00:00\"", "\"16:00\"");

        assertThat(CatedraEntityMapper.schedule(read(json, WeeklyScheduleJson.class)).startTime()).isEqualTo(LocalTime.of(16, 0));
    }

    @Test
    void ignoraLosCamposDesconocidos() {
        String json = PROFESIONAL_201.replace("{\"id\":201", "{\"campoNuevo\":{\"a\":[1,2]},\"id\":201");

        assertThat(CatedraEntityMapper.professional(read(json, ProfessionalJson.class)).id()).isEqualTo(201);
    }

    @Test
    void unBooleanoAusenteNoSeVuelveFalse() {
        String json = PROFESIONAL_201.replace("\"enabled\":true,", "");

        assertThatThrownBy(() -> CatedraEntityMapper.professional(read(json, ProfessionalJson.class))).hasMessage(
            "El profesional 201 no tiene enabled"
        );
    }

    @Test
    void unaCategoriaSinDescripcionEsValida() {
        CategoryJson dto = new CategoryJson(101L, "Clinica", null, false, "2026-09-01T03:00:00Z", "2026-09-01T03:00:00Z");

        assertThat(CatedraEntityMapper.category(dto)).isEqualTo(
            new ProfessionalCategory(101, "Clinica", null, false, Instant.parse("2026-09-01T03:00:00Z"), Instant.parse("2026-09-01T03:00:00Z"))
        );
    }

    @Test
    void unInstanteInvalidoNombraElCampo() {
        String json = PROFESIONAL_201.replace("2026-09-01T03:00:00Z", "ayer");

        assertThatThrownBy(() -> CatedraEntityMapper.professional(read(json, ProfessionalJson.class))).hasMessage(
            "El profesional 201 tiene un createdAt inválido: 'ayer'"
        );
    }

    private static <T> T read(String json, Class<T> type) {
        return CatedraJson.MAPPER.readValue(json, type);
    }
}
