package ar.edu.um.programacion2.catalogo.sync.domain.ports.out;

/**
 * Lo publicado no respeta el contrato: un número que no es número, un campo obligatorio ausente, un JSON roto o una
 * entidad cuyo ID no es el de su clave. El estado no se puede verificar: corresponde una sincronización completa.
 */
public final class InvalidChangesException extends ChangesRetrievalException {

    public InvalidChangesException(String message, Throwable cause) {
        super(message, cause);
    }
}
