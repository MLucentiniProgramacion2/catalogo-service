package ar.edu.um.programacion2.catalogo.catalog.application.usecases;

import ar.edu.um.programacion2.catalogo.catalog.application.exception.CatalogNotInitializedException;
import ar.edu.um.programacion2.catalogo.catalog.application.exception.ProfessionalNotFoundException;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalAgenda;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalWithCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Versioned;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.in.GetProfessionalAgendaUseCase;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.CatalogRetryHint;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.LocalCatalogVersionProvider;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.ProfessionalAgendaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * CU-07: la versión local, el profesional con su categoría y sus horarios habilitados, en una sola transacción de solo
 * lectura.
 * <p>
 * Igual que CU-05 ({@link SearchProfessionalsUseCaseImpl}), {@code REPEATABLE_READ} hace que PostgreSQL tome una sola
 * foto en la primera sentencia: acá son hasta tres (la versión, el profesional y los horarios). Con {@code READ COMMITTED},
 * un snapshot confirmado en el medio podría dar la versión anterior con los horarios de la nueva, y turnos validaría un
 * turno contra una agenda que no corresponde a la versión informada. La transacción solo lee: no bloquea al snapshot
 * (DP-03, flujo 4d).
 * <p>
 * El orden: sin versión aplicada, {@code 503} sin consultar nada más; sin el profesional, {@code 404}; con el profesional
 * deshabilitado (4b), la agenda va sin horarios y sin leerlos: un profesional dado de baja no tiene agenda vigente.
 */
@Component
@RequiredArgsConstructor
public class GetProfessionalAgendaUseCaseImpl implements GetProfessionalAgendaUseCase {

    private final LocalCatalogVersionProvider versionProvider;
    private final CatalogRetryHint retryHint;
    private final ProfessionalAgendaRepository agendaRepository;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Versioned<ProfessionalAgenda> getProfessionalAgenda(long professionalId) {
        long version = versionProvider.findAppliedVersion().orElseThrow(() -> new CatalogNotInitializedException(retryHint.retryAfter()));
        ProfessionalWithCategory professional = agendaRepository
            .findProfessionalWithCategory(professionalId)
            .orElseThrow(ProfessionalNotFoundException::new);
        ProfessionalAgenda agenda = professional.professional().enabled()
            ? new ProfessionalAgenda(professional, agendaRepository.findEnabledSchedules(professionalId))
            : ProfessionalAgenda.withoutSchedules(professional);
        return new Versioned<>(version, agenda);
    }
}
