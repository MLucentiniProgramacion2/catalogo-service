package ar.edu.um.programacion2.catalogo.catalog.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ar.edu.um.programacion2.catalogo.catalog.application.exception.CatalogNotInitializedException;
import ar.edu.um.programacion2.catalogo.catalog.application.exception.ProfessionalNotFoundException;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalAgenda;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalWithCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Versioned;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.CatalogRetryHint;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.LocalCatalogVersionProvider;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.ProfessionalAgendaRepository;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GetProfessionalAgendaUseCaseImplTest {

    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");

    @Mock
    private LocalCatalogVersionProvider versionProvider;

    @Mock
    private CatalogRetryHint retryHint;

    @Mock
    private ProfessionalAgendaRepository agendaRepository;

    @InjectMocks
    private GetProfessionalAgendaUseCaseImpl useCase;

    @Test
    void leePrimeroLaVersionYDevuelveLaAgendaConEsaVersion() {
        when(versionProvider.findAppliedVersion()).thenReturn(OptionalLong.of(57));
        when(agendaRepository.findProfessionalWithCategory(15)).thenReturn(Optional.of(profesional(true)));
        when(agendaRepository.findEnabledSchedules(15)).thenReturn(List.of(horario(22, DayOfWeek.TUESDAY), horario(21, DayOfWeek.MONDAY)));

        Versioned<ProfessionalAgenda> agenda = useCase.getProfessionalAgenda(15);

        assertThat(agenda.version()).isEqualTo(57);
        assertThat(agenda.content().weeklySchedules()).extracting(WeeklySchedule::id).containsExactly(21L, 22L);
        InOrder orden = inOrder(versionProvider, agendaRepository);
        orden.verify(versionProvider).findAppliedVersion();
        orden.verify(agendaRepository).findProfessionalWithCategory(15);
        orden.verify(agendaRepository).findEnabledSchedules(15);
    }

    @Test
    void sinVersionAplicadaFallaConElPlazoSinConsultarNadaMas() {
        when(versionProvider.findAppliedVersion()).thenReturn(OptionalLong.empty());
        when(retryHint.retryAfter()).thenReturn(Optional.of(Duration.ofMinutes(5)));

        assertThatThrownBy(() -> useCase.getProfessionalAgenda(15)).isInstanceOfSatisfying(CatalogNotInitializedException.class, e ->
            assertThat(e.getRetryAfter()).contains(Duration.ofMinutes(5))
        );
        verifyNoInteractions(agendaRepository);
    }

    @Test
    void sinElProfesionalFallaSinLeerHorarios() {
        when(versionProvider.findAppliedVersion()).thenReturn(OptionalLong.of(57));
        when(agendaRepository.findProfessionalWithCategory(15)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.getProfessionalAgenda(15)).isInstanceOf(ProfessionalNotFoundException.class);
        verify(agendaRepository, never()).findEnabledSchedules(anyLong());
    }

    @Test
    void conElProfesionalDeshabilitadoLaAgendaVaSinHorariosYSinLeerlos() {
        when(versionProvider.findAppliedVersion()).thenReturn(OptionalLong.of(57));
        when(agendaRepository.findProfessionalWithCategory(15)).thenReturn(Optional.of(profesional(false)));

        Versioned<ProfessionalAgenda> agenda = useCase.getProfessionalAgenda(15);

        assertThat(agenda.content().professional().professional().enabled()).isFalse();
        assertThat(agenda.content().weeklySchedules()).isEmpty();
        verify(agendaRepository, never()).findEnabledSchedules(anyLong());
    }

    private static ProfessionalWithCategory profesional(boolean habilitado) {
        return new ProfessionalWithCategory(
            new Professional(15, 1, "Ana", "Gomez", habilitado, CREADO, CREADO),
            new ProfessionalCategory(1, "Clínica", null, true, CREADO, CREADO)
        );
    }

    private static WeeklySchedule horario(long id, DayOfWeek dia) {
        return new WeeklySchedule(id, 15, dia, LocalTime.of(9, 0), LocalTime.of(12, 0), 30, true, CREADO, CREADO);
    }
}
