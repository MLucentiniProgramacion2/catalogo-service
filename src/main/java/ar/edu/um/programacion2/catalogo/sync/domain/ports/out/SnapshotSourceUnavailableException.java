package ar.edu.um.programacion2.catalogo.sync.domain.ports.out;

/** La fuente no está disponible: timeout, error de conexión o falla informada como transitoria. Se puede reintentar. */
public final class SnapshotSourceUnavailableException extends SnapshotRetrievalException {

    public SnapshotSourceUnavailableException(String message) {
        super(message);
    }

    public SnapshotSourceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
