package ar.edu.um.programacion2.catalogo.sync.domain.model;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * Estado de sincronización de la copia local (CU-09): versión aplicada, última sincronización exitosa, última versión
 * remota conocida y último error. Existe uno solo por servicio.
 * <p>
 * Es una clase y no un record porque cambia en el tiempo: cada sincronización lo actualiza. No tiene setters; los
 * cambios entran por métodos que respetan las reglas de la sincronización (por ejemplo, el incremental avanza de a una
 * versión). Si una sincronización está en curso no se guarda acá: es un dato del proceso vivo, no de la base.
 * <p>
 * Los campos van en grupos que se completan juntos: la versión local con la fecha, el camino y el disparador de la
 * sincronización que la dejó; la versión remota con la fecha en que se leyó; y el error con su fecha, tipo y
 * descripción. Un éxito no borra el último error ni al revés: CU-09 informa los dos, cada uno con su fecha.
 */
public class SyncStatus {

    private Long localVersion;
    private Instant lastSuccessAt;
    private SyncPath lastSuccessPath;
    private SyncTrigger lastSuccessTrigger;

    private Long remoteVersion;
    private Instant remoteVersionCheckedAt;

    private Instant lastErrorAt;
    private SyncErrorType lastErrorType;
    private String lastErrorDescription;

    public SyncStatus(
        Long localVersion,
        Instant lastSuccessAt,
        SyncPath lastSuccessPath,
        SyncTrigger lastSuccessTrigger,
        Long remoteVersion,
        Instant remoteVersionCheckedAt,
        Instant lastErrorAt,
        SyncErrorType lastErrorType,
        String lastErrorDescription
    ) {
        requireAllOrNone("la última sincronización exitosa", localVersion, lastSuccessAt, lastSuccessPath, lastSuccessTrigger);
        requireAllOrNone("la versión remota", remoteVersion, remoteVersionCheckedAt);
        requireAllOrNone("el último error", lastErrorAt, lastErrorType, lastErrorDescription);
        if (lastErrorDescription != null) {
            requireNotBlank(lastErrorDescription);
        }
        this.localVersion = localVersion;
        this.lastSuccessAt = lastSuccessAt;
        this.lastSuccessPath = lastSuccessPath;
        this.lastSuccessTrigger = lastSuccessTrigger;
        this.remoteVersion = remoteVersion;
        this.remoteVersionCheckedAt = remoteVersionCheckedAt;
        this.lastErrorAt = lastErrorAt;
        this.lastErrorType = lastErrorType;
        this.lastErrorDescription = lastErrorDescription;
    }

    /** Estado de un servicio que nunca sincronizó: sin versión local ("sin datos"). */
    public static SyncStatus initial() {
        return new SyncStatus(null, null, null, null, null, null, null, null, null);
    }

    public boolean hasLocalCopy() {
        return localVersion != null;
    }

    /**
     * Registra un snapshot aplicado (CU-02). Acepta cualquier versión, incluso una menor que la local: la versión local
     * tiene que ser exactamente la que corresponde a los datos guardados, y esos datos son los del snapshot.
     */
    public void recordSnapshotApplied(long snapshotVersion, SyncTrigger trigger, Instant appliedAt) {
        recordSuccess(snapshotVersion, SyncPath.SNAPSHOT, trigger, appliedAt);
    }

    /**
     * Registra una versión incremental aplicada (CU-04). Las versiones se aplican en orden, así que solo se acepta la
     * siguiente a la local. Sin versión local no hay incremental posible: corresponde un snapshot.
     */
    public void recordIncrementalApplied(long version, SyncTrigger trigger, Instant appliedAt) {
        if (localVersion == null) {
            throw new IllegalStateException("No hay versión local: la versión " + version + " requiere un snapshot");
        }
        if (version != localVersion + 1) {
            throw new IllegalStateException("La versión local es " + localVersion + ": no se puede aplicar la versión " + version);
        }
        recordSuccess(version, SyncPath.INCREMENTAL, trigger, appliedAt);
    }

    /** Registra la última versión publicada que se leyó de la metadata de Redis, para informarla sin consultar Redis. */
    public void recordRemoteVersion(long version, Instant checkedAt) {
        this.remoteVersion = version;
        this.remoteVersionCheckedAt = Objects.requireNonNull(checkedAt, "Falta la fecha de lectura de la versión remota");
    }

    /** Registra el último error. La descripción se informa a los usuarios: no puede llevar datos sensibles. */
    public void recordError(SyncErrorType type, String description, Instant occurredAt) {
        Objects.requireNonNull(type, "Falta el tipo de error");
        Objects.requireNonNull(description, "Falta la descripción del error");
        requireNotBlank(description);
        this.lastErrorAt = Objects.requireNonNull(occurredAt, "Falta la fecha del error");
        this.lastErrorType = type;
        this.lastErrorDescription = description;
    }

    private void recordSuccess(long version, SyncPath path, SyncTrigger trigger, Instant appliedAt) {
        Objects.requireNonNull(trigger, "Falta el disparador de la sincronización");
        Objects.requireNonNull(appliedAt, "Falta la fecha de la sincronización");
        this.localVersion = version;
        this.lastSuccessAt = appliedAt;
        this.lastSuccessPath = path;
        this.lastSuccessTrigger = trigger;
    }

    private static void requireAllOrNone(String group, Object... values) {
        long present = Arrays.stream(values).filter(Objects::nonNull).count();
        if (present != 0 && present != values.length) {
            throw new IllegalArgumentException("Los datos de " + group + " tienen que estar todos o ninguno");
        }
    }

    private static void requireNotBlank(String description) {
        if (description.isBlank()) {
            throw new IllegalArgumentException("La descripción del error no puede estar en blanco");
        }
    }

    public Long getLocalVersion() {
        return localVersion;
    }

    public Instant getLastSuccessAt() {
        return lastSuccessAt;
    }

    public SyncPath getLastSuccessPath() {
        return lastSuccessPath;
    }

    public SyncTrigger getLastSuccessTrigger() {
        return lastSuccessTrigger;
    }

    public Long getRemoteVersion() {
        return remoteVersion;
    }

    public Instant getRemoteVersionCheckedAt() {
        return remoteVersionCheckedAt;
    }

    public Instant getLastErrorAt() {
        return lastErrorAt;
    }

    public SyncErrorType getLastErrorType() {
        return lastErrorType;
    }

    public String getLastErrorDescription() {
        return lastErrorDescription;
    }
}
