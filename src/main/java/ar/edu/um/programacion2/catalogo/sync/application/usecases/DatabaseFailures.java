package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.UncategorizedDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

/**
 * Decide si una falla de la base local es transitoria, es decir, si reintentar la transacción entera puede servir
 * (DP-08, CU-02 flujo 5a).
 * <p>
 * No alcanza con {@link TransientDataAccessException}: con PostgreSQL, Hibernate y Hikari, ninguna caída o corte real de
 * la base llega con ese tipo (se probó, ver la explicación del #23):
 * <ul>
 * <li>sin conexión disponible: {@link DataAccessResourceFailureException}, que Spring ubica del lado no transitorio;</li>
 * <li>sin conexión al abrir una transacción de solo lectura: {@link CannotCreateTransactionException}, que ni siquiera
 * es una {@code DataAccessException};</li>
 * <li>conexión cortada a mitad de la transacción (por ejemplo, PostgreSQL reiniciándose): el rollback sobre la conexión
 * muerta también falla y su excepción, una {@link UncategorizedDataAccessException}, tapa a la original.</li>
 * </ul>
 * Se reintenta lo que indica que la base no respondió. No se reintenta lo que indica que el dato o el código están mal
 * (integridad, uso incorrecto de la API, SQL inválido, transacción mal usada), porque fallaría igual. Reintentar de más
 * una falla sin categoría que resultara permanente cuesta, como mucho, las esperas de DP-08.
 * <p>
 * <strong>El commit dudoso.</strong> Si la conexión se corta durante el commit ({@link TransactionSystemException}, o
 * {@link DataAccessResourceFailureException} con PostgreSQL), no se sabe si la base llegó a confirmar: es el §18.4 del
 * contrato ("un timeout no prueba que la operación haya fallado") aplicado a la base. Reintentar igual es seguro porque
 * aplicar un snapshot es idempotente: reemplazar todo y fijar su versión deja el mismo resultado aunque se haga dos
 * veces.
 */
final class DatabaseFailures {

    private DatabaseFailures() {}

    static boolean isTransient(RuntimeException failure) {
        return (
            failure instanceof TransientDataAccessException ||
            failure instanceof RecoverableDataAccessException ||
            failure instanceof DataAccessResourceFailureException ||
            failure instanceof UncategorizedDataAccessException ||
            failure instanceof CannotCreateTransactionException ||
            // Falló el commit o el rollback. Si fue el commit, no se sabe si la base confirmó, pero reintentar es seguro:
            // aplicar un snapshot es idempotente (ver "El commit dudoso" arriba).
            failure instanceof TransactionSystemException
        );
    }
}
