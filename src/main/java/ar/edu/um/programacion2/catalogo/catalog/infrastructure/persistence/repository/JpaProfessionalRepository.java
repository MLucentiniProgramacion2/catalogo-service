package ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.repository;

import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.entity.ProfessionalEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repositorio de Spring Data de los profesionales. Con {@link JpaSpecificationExecutor}, la búsqueda de CU-05 arma los
 * filtros con {@link ProfessionalSpecifications}.
 */
@Repository
public interface JpaProfessionalRepository extends JpaRepository<ProfessionalEntity, Long>, JpaSpecificationExecutor<ProfessionalEntity> {
    /**
     * Borra todos los profesionales con un solo {@code DELETE}, sin cargarlos.
     * <p>
     * El {@code DELETE} va directo a la base y el contexto de persistencia no se entera: {@code flushAutomatically}
     * manda antes lo pendiente (de cualquier tabla) y {@code clearAutomatically} vacía el contexto después, para que no
     * quede administrada una entidad borrada con el mismo ID que otra que se va a insertar.
     */
    /** Cuáles de esos IDs existen, en una consulta y sin cargar las filas. */
    @Query("select e.id from ProfessionalEntity e where e.id in :ids")
    List<Long> findExistingIds(@Param("ids") Collection<Long> ids);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ProfessionalEntity")
    int deleteAllRows();

    /**
     * La búsqueda paginada, con la categoría de cada profesional cargada en la misma consulta.
     * <p>
     * La categoría es {@code LAZY}: sin el grafo, leer su nombre al armar la respuesta haría una consulta por
     * profesional (N+1). {@code @EntityGraph} le pide a Hibernate que la traiga con un join en la consulta de datos. Spring
     * Data no lo aplica a la consulta del count, que no necesita la categoría: por eso no va como fetch join dentro de una
     * {@code Specification}, que también llegaría al count y lo haría fallar.
     */
    @Override
    @EntityGraph(attributePaths = "category")
    Page<ProfessionalEntity> findAll(Specification<ProfessionalEntity> spec, Pageable pageable);

    /** Un profesional con su categoría en la misma consulta (CU-07): sin el grafo, la categoría {@code LAZY} sería otra. */
    @EntityGraph(attributePaths = "category")
    Optional<ProfessionalEntity> findWithCategoryById(Long id);
}
