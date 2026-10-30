package ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.repository;

import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.entity.ProfessionalEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface JpaProfessionalRepository extends JpaRepository<ProfessionalEntity, Long> {
    /**
     * Borra todos los profesionales con un solo {@code DELETE}, sin cargarlos.
     * <p>
     * El {@code DELETE} va directo a la base y el contexto de persistencia no se entera: {@code flushAutomatically}
     * manda antes lo pendiente (de cualquier tabla) y {@code clearAutomatically} vacía el contexto después, para que no
     * quede administrada una entidad borrada con el mismo ID que otra que se va a insertar.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ProfessionalEntity")
    int deleteAllRows();
}
