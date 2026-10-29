package ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** Fila de {@code processed_event} (changelog {@code sync/002-processed-event.xml}). El ID es el {@code eventId} del evento. */
@Entity
@Table(name = "processed_event")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class ProcessedEventEntity {

    @Id
    @Column(name = "event_id")
    private String eventId;

    @Column(name = "new_version", nullable = false)
    private Long newVersion;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;
}
