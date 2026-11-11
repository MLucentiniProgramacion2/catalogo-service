package ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.repository;

import ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.entity.ProcessedEventEntity;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface JpaProcessedEventRepository extends JpaRepository<ProcessedEventEntity, String> {
    /**
     * Inserta la fila si el {@code event_id} no existe, en una sola sentencia. {@code save} no sirve: hace un {@code merge}
     * (lee y después inserta), y entre esos dos pasos otra transacción puede insertar la misma clave, con una violación que
     * deshace la transacción entera.
     * <p>
     * Es HQL: {@code insert} y {@code on conflict} son de Hibernate, no de JPQL. Hibernate valida los nombres al crear el
     * repositorio, así que uno mal escrito impide arrancar. Con SQL nativo, recién fallaría al llegar el primer aviso.
     *
     * @return filas insertadas: 0 si ya estaba.
     */
    @Modifying
    @Query(
        """
        insert into ProcessedEventEntity (eventId, newVersion, processedAt)
        values (:eventId, :newVersion, :processedAt)
        on conflict(eventId) do nothing
        """
    )
    int insertIfAbsent(@Param("eventId") String eventId, @Param("newVersion") long newVersion, @Param("processedAt") Instant processedAt);
}
