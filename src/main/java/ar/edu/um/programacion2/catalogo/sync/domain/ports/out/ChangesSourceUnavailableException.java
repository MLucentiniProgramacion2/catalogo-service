package ar.edu.um.programacion2.catalogo.sync.domain.ports.out;

/** La fuente no respondió a tiempo o no se pudo conectar. Se puede reintentar. */
public final class ChangesSourceUnavailableException extends ChangesRetrievalException {

    public ChangesSourceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
