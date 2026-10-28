package ar.edu.um.programacion2.catalogo.sync.domain.ports.out;

/** La fuente rechazó la credencial (vencida, inválida o sin permisos). No es transitoria: reintentar no sirve. */
public final class SnapshotAccessDeniedException extends SnapshotRetrievalException {

    public SnapshotAccessDeniedException(String message) {
        super(message);
    }

    public SnapshotAccessDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
