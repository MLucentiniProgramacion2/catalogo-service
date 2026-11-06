package ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.adapter;

import ar.edu.um.programacion2.catalogo.sync.domain.model.ProcessedEvent;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.ProcessedEventRepository;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.repository.JpaProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adaptador de salida: guarda los eventos procesados en {@code processed_event}.
 * <p>
 * {@code MANDATORY} y {@code @Repository}, como {@link JpaSyncStatusRepositoryAdapter}: la transacción la abre el caso de
 * uso, porque el {@code eventId} tiene que confirmarse junto con los datos que aplicó.
 * <p>
 * No hay mapper a {@code ProcessedEventEntity}: el adaptador nunca lee la entidad y el {@code INSERT} recibe los tres
 * valores sueltos. La entidad queda para que Hibernate valide la tabla.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
@RequiredArgsConstructor
public class JpaProcessedEventRepositoryAdapter implements ProcessedEventRepository {

    private final JpaProcessedEventRepository jpaProcessedEventRepository;

    @Override
    public boolean exists(String eventId) {
        return jpaProcessedEventRepository.existsById(eventId);
    }

    @Override
    public void registerIfAbsent(ProcessedEvent event) {
        jpaProcessedEventRepository.insertIfAbsent(event.eventId(), event.newVersion(), event.processedAt());
    }
}
