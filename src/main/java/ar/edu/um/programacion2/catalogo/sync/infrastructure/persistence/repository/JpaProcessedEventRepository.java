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
     * Inserta la fila si el {@code event_id} no existe. Es SQL nativo porque {@code ON CONFLICT} es de PostgreSQL y JPQL no
     * tiene un equivalente: {@code save} hace un {@code merge} (lee y después inserta), y entre esos dos pasos otra
     * transacción puede insertar la misma clave, con una violación que deshace la transacción entera.
     *
     * @return filas insertadas: 0 si ya estaba.
     */
    @Modifying
    @Query(
        value = """
        insert into processed_event (event_id, new_version, processed_at)
        values (:eventId, :newVersion, :processedAt)
        on conflict (event_id) do nothing
        """,
        nativeQuery = true
    )
    int insertIfAbsent(@Param("eventId") String eventId, @Param("newVersion") long newVersion, @Param("processedAt") Instant processedAt);
}
