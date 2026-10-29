package ar.edu.um.programacion2.catalogo.sync.domain.ports.out;

/**
 * Falla al obtener el snapshot con {@link CatalogSnapshotProvider}. Es parte del contrato del puerto.
 * <p>
 * Es {@code sealed}: solo existen los tres tipos permitidos, así un {@code switch} del caso de uso tiene que
 * cubrirlos todos y el compilador avisa si se agrega otro. Los mensajes nunca llevan credenciales.
 */
public abstract sealed class SnapshotRetrievalException
    extends RuntimeException
    permits SnapshotSourceUnavailableException, SnapshotAccessDeniedException, InvalidSnapshotException {

    protected SnapshotRetrievalException(String message) {
        super(message);
    }

    protected SnapshotRetrievalException(String message, Throwable cause) {
        super(message, cause);
    }
}
