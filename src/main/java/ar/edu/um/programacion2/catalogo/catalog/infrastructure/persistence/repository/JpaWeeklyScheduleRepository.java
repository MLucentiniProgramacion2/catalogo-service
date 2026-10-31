package ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.repository;

import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.entity.WeeklyScheduleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface JpaWeeklyScheduleRepository extends JpaRepository<WeeklyScheduleEntity, Long> {
    /**
     * Borra todos los horarios con un solo {@code DELETE}, sin cargarlos.
     * <p>
     * El {@code DELETE} va directo a la base y el contexto de persistencia no se entera: {@code flushAutomatically}
     * manda antes lo pendiente (de cualquier tabla) y {@code clearAutomatically} vacía el contexto después, para que no
     * quede administrada una entidad borrada con el mismo ID que otra que se va a insertar.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from WeeklyScheduleEntity")
    int deleteAllRows();
}
