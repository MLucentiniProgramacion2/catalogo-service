package ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.entity;

import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncPath;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.DynamicUpdate;

/**
 * Única fila de {@code sync_status} (changelog {@code sync/001-sync-status.xml}). La crea Liquibase con {@code id = 1}
 * y la base rechaza cualquier otra.
 * <p>
 * Los enums se guardan por nombre ({@code EnumType.STRING}): agregar un valor no cambia los ya guardados.
 * <p>
 * {@link DynamicUpdate}: el {@code UPDATE} lleva solo las columnas que cambiaron, no todas. Hace falta porque una
 * escritura puede ocurrir sin tener la exclusión mutua: el {@code LOCK_TIMEOUT} de DP-09 se registra justamente mientras
 * otra sincronización corre. Con el {@code UPDATE} completo, el que confirma último pisa al otro: el error volvía a dejar
 * la versión anterior en una fila con los datos nuevos, o el snapshot borraba el error. Las condiciones están en
 * {@code JpaSyncStatusRepositoryAdapter}; lo prueba {@code SyncStatusConcurrentWritesIT}.
 */
@DynamicUpdate
@Entity
@Table(name = "sync_status")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class SyncStatusEntity {

    public static final short SINGLE_ROW_ID = 1;

    @Id
    @Column(name = "id")
    private Short id;

    @Column(name = "local_version")
    private Long localVersion;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_success_path", length = 20)
    private SyncPath lastSuccessPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_success_trigger", length = 20)
    private SyncTrigger lastSuccessTrigger;

    @Column(name = "remote_version")
    private Long remoteVersion;

    @Column(name = "remote_version_checked_at")
    private Instant remoteVersionCheckedAt;

    @Column(name = "last_error_at")
    private Instant lastErrorAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_error_type", length = 40)
    private SyncErrorType lastErrorType;

    @Column(name = "last_error_description")
    private String lastErrorDescription;
}
