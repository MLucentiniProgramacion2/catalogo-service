package ar.edu.um.programacion2.catalogo.sync.domain.ports.out;

/**
 * Falla al leer con {@link CatalogChangesProvider}. Es parte del contrato del puerto.
 * <p>
 * Es {@code sealed}, como {@link SnapshotRetrievalException}: un {@code switch} del caso de uso tiene que cubrir los
 * tres tipos. Los mensajes llegan a los usuarios (CU-08, CU-09): son textos fijos, sin credenciales, usuarios, hosts ni
 * nombres de clases.
 */
public abstract sealed class ChangesRetrievalException
    extends RuntimeException
    permits ChangesSourceUnavailableException, ChangesAccessDeniedException, InvalidChangesException {

    protected ChangesRetrievalException(String message, Throwable cause) {
        super(message, cause);
    }
}
