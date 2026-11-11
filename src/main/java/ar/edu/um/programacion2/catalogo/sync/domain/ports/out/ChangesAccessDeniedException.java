package ar.edu.um.programacion2.catalogo.sync.domain.ports.out;

/** La fuente rechazó la credencial o el permiso. No es transitoria: hay que revisar la configuración. */
public final class ChangesAccessDeniedException extends ChangesRetrievalException {

    public ChangesAccessDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
