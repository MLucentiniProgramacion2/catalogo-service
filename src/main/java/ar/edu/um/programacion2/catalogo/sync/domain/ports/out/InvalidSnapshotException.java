package ar.edu.um.programacion2.catalogo.sync.domain.ports.out;

/**
 * La respuesta no respeta el contrato: formato inválido, campo obligatorio ausente o respuesta inesperada. No es
 * transitoria: reintentar no sirve.
 */
public final class InvalidSnapshotException extends SnapshotRetrievalException {

    public InvalidSnapshotException(String message) {
        super(message);
    }

    public InvalidSnapshotException(String message, Throwable cause) {
        super(message, cause);
    }
}
