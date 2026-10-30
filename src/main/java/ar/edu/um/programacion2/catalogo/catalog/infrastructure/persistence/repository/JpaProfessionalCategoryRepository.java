package ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.repository;

import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.entity.ProfessionalCategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface JpaProfessionalCategoryRepository extends JpaRepository<ProfessionalCategoryEntity, Long> {
    /**
     * Borra todas las categorías con un solo {@code DELETE}, sin cargarlas.
     * <p>
     * El {@code DELETE} va directo a la base y el contexto de persistencia no se entera: {@code flushAutomatically}
     * manda antes lo pendiente (de cualquier tabla) y {@code clearAutomatically} vacía el contexto después, para que no
     * quede administrada una entidad borrada con el mismo ID que otra que se va a insertar.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ProfessionalCategoryEntity")
    int deleteAllRows();
}
