package ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.adapter;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.CatalogRepository;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.mapper.ProfessionalCategoryMapper;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.mapper.ProfessionalMapper;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.mapper.WeeklyScheduleMapper;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.repository.JpaProfessionalCategoryRepository;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.repository.JpaProfessionalRepository;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.repository.JpaWeeklyScheduleRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adaptador de salida: guarda la copia local del catálogo en {@code professional_category}, {@code professional} y
 * {@code weekly_schedule}.
 * <p>
 * El reemplazo borra todo y vuelve a insertar, en la transacción del caso de uso:
 * <ol>
 * <li>Tres {@code DELETE} masivos, de abajo hacia arriba por las claves foráneas (horarios, profesionales,
 * categorías). Cada uno manda antes lo pendiente y vacía después el contexto de persistencia ({@code @Modifying}).</li>
 * <li>Un {@code INSERT} por fila, de arriba hacia abajo, con {@code persist}: el ID lo asigna la cátedra, y
 * {@code save} haría un {@code merge} con un {@code SELECT} antes de cada {@code INSERT}.</li>
 * <li>Las referencias entre filas se arman con {@code getReferenceById}, sin consultar la base.</li>
 * <li>Un {@code flush} final, para que una falla de la base salga de este método y no recién en el commit.</li>
 * </ol>
 * Sin {@code TRUNCATE}: toma un lock exclusivo que bloquea las lecturas hasta el commit. Con {@code DELETE}, las demás
 * transacciones siguen leyendo la copia anterior hasta que esta se confirma (DP-03).
 * <p>
 * {@code MANDATORY}: falla con {@code IllegalTransactionStateException} antes de tocar nada si no hay una transacción
 * abierta. Sin eso, lo que pasa depende de cada repositorio: los métodos heredados de {@code JpaRepository} (como
 * {@code deleteAllInBatch}) abren y confirman su propia transacción, y un error a mitad de camino dejaría la copia
 * borrada. {@code @Repository} hace que Spring traduzca las fallas de la base, incluidas las de {@code persist} y
 * {@code flush}, a {@code DataAccessException}; también traduce las {@code IllegalArgumentException} de los mappers a
 * {@code InvalidDataAccessApiUsageException}.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
@RequiredArgsConstructor
public class JpaCatalogRepositoryAdapter implements CatalogRepository {

    private final EntityManager entityManager;
    private final JpaProfessionalCategoryRepository categoryRepository;
    private final JpaProfessionalRepository professionalRepository;
    private final JpaWeeklyScheduleRepository weeklyScheduleRepository;
    private final ProfessionalCategoryMapper categoryMapper;
    private final ProfessionalMapper professionalMapper;
    private final WeeklyScheduleMapper weeklyScheduleMapper;

    @Override
    public void replaceAll(List<ProfessionalCategory> categories, List<Professional> professionals, List<WeeklySchedule> weeklySchedules) {
        Objects.requireNonNull(categories, "Faltan las categorías");
        Objects.requireNonNull(professionals, "Faltan los profesionales");
        Objects.requireNonNull(weeklySchedules, "Faltan los horarios");

        weeklyScheduleRepository.deleteAllRows();
        professionalRepository.deleteAllRows();
        categoryRepository.deleteAllRows();

        categories.forEach(category -> entityManager.persist(categoryMapper.toEntity(category)));
        professionals.forEach(professional ->
            entityManager.persist(professionalMapper.toEntity(professional, categoryRepository.getReferenceById(professional.categoryId())))
        );
        weeklySchedules.forEach(schedule ->
            entityManager.persist(
                weeklyScheduleMapper.toEntity(schedule, professionalRepository.getReferenceById(schedule.professionalId()))
            )
        );
        entityManager.flush();
    }
}
