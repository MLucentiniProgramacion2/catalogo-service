package ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.CatalogRepository;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Los dos puertos usados como los va a usar CU-02 (issue #22): reemplazar la copia local y guardar la versión aplicada
 * en una sola transacción, que abre quien llama. Acá esa transacción la abre el test con {@link TransactionTemplate}.
 * <p>
 * Para no depender de cómo se leen las tablas del catálogo, la copia se verifica por los IDs de las categorías.
 */
@IntegrationTest
class SnapshotPersistenceTransactionIT {

    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-10T13:45:00Z");
    private static final Instant T2 = Instant.parse("2026-09-10T14:00:00Z");

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private SyncStatusRepository syncStatusRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager em;

    @BeforeEach
    @AfterEach
    void volverAlEstadoInicial() {
        transactionTemplate.executeWithoutResult(tx -> {
            em.createNativeQuery("delete from weekly_schedule").executeUpdate();
            em.createNativeQuery("delete from professional").executeUpdate();
            em.createNativeQuery("delete from professional_category").executeUpdate();
            syncStatusRepository.save(SyncStatus.initial());
        });
    }

    @Test
    void elReemplazoYElEstadoSeConfirmanJuntos() {
        aplicarSnapshot(7, List.of(categoria(1), categoria(2)));

        assertThat(idsDeCategorias()).containsExactly(1L, 2L);
        assertThat(versionLocal()).isEqualTo(7L);
    }

    @Test
    void siSeDeshaceNoQuedaNiElCatalogoNiLaVersion() {
        aplicarSnapshot(5, List.of(categoria(1)));

        transactionTemplate.executeWithoutResult(tx -> {
            catalogRepository.replaceAll(List.of(categoria(9)), List.of(), List.of());
            SyncStatus estado = syncStatusRepository.load();
            estado.recordSnapshotApplied(7, SyncTrigger.MANUAL, T2);
            syncStatusRepository.save(estado);
            tx.setRollbackOnly();
        });

        assertThat(idsDeCategorias()).containsExactly(1L);
        assertThat(versionLocal()).isEqualTo(5L);
    }

    @Test
    void unEstadoGuardadoAntesDelReemplazoNoSePierdeConElClear() {
        transactionTemplate.executeWithoutResult(tx -> {
            SyncStatus estado = syncStatusRepository.load();
            estado.recordSnapshotApplied(7, SyncTrigger.STARTUP, T1);
            // El UPDATE de sync_status queda pendiente en el contexto de persistencia. El DELETE del reemplazo no toca
            // esa tabla, así que Hibernate no lo manda solo: si no lo hiciera flushAutomatically, el clear lo descartaría.
            syncStatusRepository.save(estado);

            catalogRepository.replaceAll(List.of(categoria(1)), List.of(), List.of());
        });

        assertThat(versionLocal()).isEqualTo(7L);
        assertThat(idsDeCategorias()).containsExactly(1L);
    }

    @Test
    void atraparLaFallaDentroDeLaMismaTransaccionNoEvitaElRollback() {
        aplicarSnapshot(5, List.of(categoria(1)));

        // La falla atraviesa el proxy MANDATORY del adaptador y marca la transacción entera para deshacerse: aunque
        // quien llama la atrape, el commit termina en rollback.
        assertThatThrownBy(() ->
            transactionTemplate.executeWithoutResult(tx -> {
                try {
                    catalogRepository.replaceAll(List.of(categoria(9)), List.of(profesionalDeCategoriaInexistente()), List.of());
                } catch (DataIntegrityViolationException e) {
                    // Se ignora a propósito.
                }
            })
        ).isInstanceOf(UnexpectedRollbackException.class);

        assertThat(idsDeCategorias()).containsExactly(1L);
        assertThat(versionLocal()).isEqualTo(5L);
    }

    @Test
    void elErrorSeRegistraEnUnaTransaccionNuevaDespuesDelRollback() {
        aplicarSnapshot(5, List.of(categoria(1)));

        try {
            transactionTemplate.executeWithoutResult(tx ->
                catalogRepository.replaceAll(List.of(categoria(9)), List.of(profesionalDeCategoriaInexistente()), List.of())
            );
        } catch (DataIntegrityViolationException e) {
            transactionTemplate.executeWithoutResult(tx -> {
                SyncStatus estado = syncStatusRepository.load();
                estado.recordError(SyncErrorType.SNAPSHOT_FAILED, "El snapshot tiene una referencia inexistente", T2);
                syncStatusRepository.save(estado);
            });
        }

        SyncStatus estado = transactionTemplate.execute(tx -> syncStatusRepository.load());
        assertThat(estado.getLastErrorType()).isEqualTo(SyncErrorType.SNAPSHOT_FAILED);
        assertThat(estado.getLocalVersion()).isEqualTo(5L);
        assertThat(idsDeCategorias()).containsExactly(1L);
    }

    private void aplicarSnapshot(long version, List<ProfessionalCategory> categorias) {
        transactionTemplate.executeWithoutResult(tx -> {
            catalogRepository.replaceAll(categorias, List.of(), List.of());
            SyncStatus estado = syncStatusRepository.load();
            estado.recordSnapshotApplied(version, SyncTrigger.STARTUP, T1);
            syncStatusRepository.save(estado);
        });
    }

    private List<Long> idsDeCategorias() {
        return transactionTemplate.execute(tx ->
            em
                .createNativeQuery("select id from professional_category order by id", Long.class)
                .getResultList()
                .stream()
                .map(Long.class::cast)
                .toList()
        );
    }

    private Long versionLocal() {
        return transactionTemplate.execute(tx -> syncStatusRepository.load().getLocalVersion());
    }

    private static ProfessionalCategory categoria(long id) {
        return new ProfessionalCategory(id, "Categoria " + id, null, true, CREADO, CREADO);
    }

    private static Professional profesionalDeCategoriaInexistente() {
        return new Professional(15, 999, "Ana", "Gomez", true, CREADO, CREADO);
    }
}
