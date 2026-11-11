package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;

/**
 * Clasificación de las fallas de la base. Los casos "reales" son los que dio el experimento del #23 con PostgreSQL,
 * Hibernate y Hikari: ninguno es una {@code TransientDataAccessException}.
 */
class DatabaseFailuresTest {

    static Stream<RuntimeException> transitorias() {
        return Stream.of(
            // Las que Spring ya considera transitorias.
            new QueryTimeoutException("canceling statement due to statement timeout"),
            new CannotAcquireLockException("could not obtain lock"),
            new DeadlockLoserDataAccessException("deadlock detected", null),
            new TransientDataAccessResourceException("recurso transitorio"),
            new RecoverableDataAccessException("conexión recuperable"),
            // Sin conexión disponible al ejecutar la primera sentencia (real).
            new DataAccessResourceFailureException("Unable to acquire JDBC Connection"),
            // Sin conexión al abrir una transacción de solo lectura (real).
            new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"),
            // Conexión cortada a mitad de la transacción: el rollback también falla y tapa la original (real).
            new JpaSystemException(new RuntimeException("Unable to rollback against JDBC Connection")),
            // Falló el commit: no se sabe si confirmó, pero aplicar el snapshot es idempotente.
            new TransactionSystemException("Could not commit JPA transaction")
        );
    }

    @ParameterizedTest
    @MethodSource("transitorias")
    void seReintentaLoQueIndicaQueLaBaseNoRespondio(RuntimeException falla) {
        assertThat(DatabaseFailures.isTransient(falla)).isTrue();
    }

    static Stream<RuntimeException> noTransitorias() {
        return Stream.of(
            new DataIntegrityViolationException("violates foreign key constraint"),
            new InvalidDataAccessApiUsageException("No existe la fila 1 de sync_status"),
            new InvalidDataAccessResourceUsageException("relation does not exist"),
            new BadSqlGrammarException("select", "select x", new SQLException("syntax error", "42601")),
            new IllegalTransactionStateException("No existing transaction found"),
            new UnexpectedRollbackException("Transaction rolled back because it has been marked as rollback-only"),
            new IllegalStateException("un error de programación"),
            new IllegalArgumentException("un dato inválido")
        );
    }

    @ParameterizedTest
    @MethodSource("noTransitorias")
    void noSeReintentaLoQueIndicaQueElDatoOElCodigoEstanMal(RuntimeException falla) {
        assertThat(DatabaseFailures.isTransient(falla)).isFalse();
    }
}
