package ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.adapter;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalWithCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.ProfessionalAgendaRepository;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.mapper.ProfessionalCategoryMapper;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.mapper.ProfessionalMapper;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.mapper.WeeklyScheduleMapper;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.repository.JpaProfessionalRepository;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.repository.JpaWeeklyScheduleRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adaptador de salida de la agenda (CU-07) sobre las mismas tablas que {@code JpaCatalogRepositoryAdapter}.
 * <p>
 * Dos sentencias: el profesional con su categoría en una ({@code @EntityGraph}, sin la consulta extra de la categoría
 * {@code LAZY}) y los horarios habilitados en otra. El orden lo pone {@code ProfessionalAgenda}: el día se guarda como
 * texto y la base lo ordenaría alfabéticamente.
 * <p>
 * Las horas salen sin convertir de zona, porque las columnas son {@code LOCAL_TIME} ({@code WeeklyScheduleEntity}): las
 * consultas no comparan horas, así que no hace falta {@code ProfessionalSpecifications.timeParameter}.
 * <p>
 * {@code MANDATORY}, como el otro adaptador: la transacción y su aislamiento son del caso de uso.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
@RequiredArgsConstructor
public class JpaProfessionalAgendaRepositoryAdapter implements ProfessionalAgendaRepository {

    private final JpaProfessionalRepository professionalRepository;
    private final JpaWeeklyScheduleRepository weeklyScheduleRepository;
    private final ProfessionalMapper professionalMapper;
    private final ProfessionalCategoryMapper categoryMapper;
    private final WeeklyScheduleMapper weeklyScheduleMapper;

    @Override
    public Optional<ProfessionalWithCategory> findProfessionalWithCategory(long professionalId) {
        return professionalRepository
            .findWithCategoryById(professionalId)
            .map(entity -> new ProfessionalWithCategory(professionalMapper.toDomain(entity), categoryMapper.toDomain(entity.getCategory())));
    }

    @Override
    public List<WeeklySchedule> findEnabledSchedules(long professionalId) {
        return weeklyScheduleRepository.findByProfessionalIdAndEnabledTrue(professionalId).stream().map(weeklyScheduleMapper::toDomain).toList();
    }
}
