package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.CatalogRepository;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogSnapshot;
import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogUpdateNotice;
import ar.edu.um.programacion2.catalogo.sync.domain.model.FullSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.ProcessedEvent;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.ExecuteFullSyncUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.CatalogSnapshotProvider;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.ProcessedEventRepository;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import jakarta.persistence.EntityManager;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El {@code eventId} y los datos se confirman o se deshacen juntos (CU-03, paso 9, y flujo 8a), contra PostgreSQL real y
 * con las transacciones de verdad. Hacen falta los dos sentidos:
 * <ul>
 * <li>si falla aplicar el snapshot, no queda el evento: detecta un registro hecho <em>antes</em> de la transacción;</li>
 * <li>si falla registrar el evento, no quedan los datos: detecta un registro hecho <em>después</em> de confirmarla. Ese
 * caso el primero no lo ve, porque nunca llega a registrar.</li>
 * </ul>
 * Los espías dejan correr el adaptador real: la falla llega con el reemplazo ya escrito en la transacción.
 */
@IntegrationTest
class FullSyncWithEventIT {

    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");
    private static final Instant ANTES = Instant.parse("2026-11-05T12:00:00Z");
    private static final CatalogUpdateNotice AVISO = new CatalogUpdateNotice("8b097be5-7ba5-4618-97e8-c502d3f46723", 41);

    @MockitoBean
    private CatalogSnapshotProvider catalogSnapshotProvider;

    @MockitoSpyBean
    private CatalogRepository catalogRepository;

    @MockitoSpyBean
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ExecuteFullSyncUseCase executeFullSyncUseCase;

    @Autowired
    private SyncStatusRepository syncStatusRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager em;

    @BeforeEach
    @AfterEach
    void volverAlEstadoInicial() {
        SyncTestDatabase.reset(transactionTemplate, em);
    }

    @Test
    void siSaleBienQuedanElEventoYLaVersion() {
        when(catalogSnapshotProvider.fetchSnapshot()).thenReturn(snapshot(41, 15));

        FullSyncResult resultado = executeFullSyncUseCase.executeFullSync(SyncTrigger.KAFKA, AVISO);

        assertThat(resultado).isEqualTo(new FullSyncResult.Applied(41, 1));
        assertThat(eventos()).containsExactly(AVISO.eventId());
        assertThat(estado().getLocalVersion()).isEqualTo(41L);
        assertThat(estado().getLastSuccessTrigger()).isEqualTo(SyncTrigger.KAFKA);
    }

    @Test
    void siFallaAplicarElSnapshotElEventoNoQuedaYLaCopiaSigueComoEstaba() {
        versionLocalDosConElProfesional15();
        when(catalogSnapshotProvider.fetchSnapshot()).thenReturn(snapshot(41, 16));
        doAnswer(invocacion -> {
            invocacion.callRealMethod();
            throw new DataIntegrityViolationException("La base rechazó el snapshot");
        })
            .when(espia(catalogRepository))
            .replaceAll(any(), any(), any());

        FullSyncResult resultado = executeFullSyncUseCase.executeFullSync(SyncTrigger.KAFKA, AVISO);

        assertThat(resultado).isInstanceOf(FullSyncResult.Failed.class);
        assertThat(eventos()).isEmpty();
        assertThat(estado().getLocalVersion()).isEqualTo(2L);
        assertThat(estado().getLastErrorType()).isEqualTo(SyncErrorType.SNAPSHOT_FAILED);
        assertThat(SyncTestDatabase.professionalIds(transactionTemplate, em)).containsExactly(15L);
    }

    @Test
    void siFallaRegistrarElEventoNoQuedanNiLaCopiaNuevaNiLaVersion() {
        versionLocalDosConElProfesional15();
        when(catalogSnapshotProvider.fetchSnapshot()).thenReturn(snapshot(41, 16));
        doThrow(new DataIntegrityViolationException("La base rechazó el evento"))
            .when(espia(processedEventRepository))
            .registerIfAbsent(any());

        FullSyncResult resultado = executeFullSyncUseCase.executeFullSync(SyncTrigger.KAFKA, AVISO);

        assertThat(resultado).isInstanceOf(FullSyncResult.Failed.class);
        assertThat(eventos()).isEmpty();
        assertThat(estado().getLocalVersion()).isEqualTo(2L);
        assertThat(SyncTestDatabase.professionalIds(transactionTemplate, em)).containsExactly(15L);
    }

    @Test
    void unEventoYaRegistradoNoDeshaceElSnapshot() {
        Instant registrado = Instant.parse("2026-11-05T22:00:00Z");
        transactionTemplate.executeWithoutResult(tx ->
            processedEventRepository.registerIfAbsent(new ProcessedEvent(AVISO.eventId(), AVISO.newVersion(), registrado))
        );
        when(catalogSnapshotProvider.fetchSnapshot()).thenReturn(snapshot(41, 15));

        FullSyncResult resultado = executeFullSyncUseCase.executeFullSync(SyncTrigger.KAFKA, AVISO);

        assertThat(resultado).isEqualTo(new FullSyncResult.Applied(41, 1));
        assertThat(estado().getLocalVersion()).isEqualTo(41L);
        assertThat(eventos()).containsExactly(AVISO.eventId());
    }

    /**
     * El espía queda adentro del proxy transaccional del adaptador. Stubbear sobre el proxy llamaría al método sin
     * transacción, y {@code MANDATORY} lo cortaría antes de que Mockito lo vea.
     */
    private static <T> T espia(T adaptador) {
        return AopTestUtils.getUltimateTargetObject(adaptador);
    }

    private void versionLocalDosConElProfesional15() {
        when(catalogSnapshotProvider.fetchSnapshot()).thenReturn(snapshot(2, 15));
        assertThat(executeFullSyncUseCase.executeFullSync(SyncTrigger.STARTUP)).isEqualTo(new FullSyncResult.Applied(2, 1));
    }

    private List<String> eventos() {
        return SyncTestDatabase.processedEventIds(transactionTemplate, em);
    }

    private SyncStatus estado() {
        return transactionTemplate.execute(tx -> syncStatusRepository.load());
    }

    private static CatalogSnapshot snapshot(long version, long profesional) {
        LocalTime nueve = LocalTime.of(9, 0);
        return new CatalogSnapshot(
            version,
            ANTES,
            List.of(new ProfessionalCategory(1L, "Categoria", null, true, CREADO, CREADO)),
            List.of(new Professional(profesional, 1L, "Ana", "Gomez", true, CREADO, CREADO)),
            List.of(new WeeklySchedule(21L, profesional, DayOfWeek.MONDAY, nueve, nueve.plusHours(3), 30, true, CREADO, CREADO))
        );
    }
}
