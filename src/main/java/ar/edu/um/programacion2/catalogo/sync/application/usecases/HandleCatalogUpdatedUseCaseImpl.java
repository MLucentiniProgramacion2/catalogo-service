package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogEventResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogUpdateNotice;
import ar.edu.um.programacion2.catalogo.sync.domain.model.FullSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncPath;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.ExecuteFullSyncUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.GetSyncStatusUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.HandleCatalogUpdatedUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.ProcessedEventRepository;
import java.time.Clock;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CU-03 con el disparador Kafka. El orden importa:
 * <ol>
 * <li>Primera barrera: si el {@code eventId} ya está registrado, termina acá, sin esperar la exclusión.</li>
 * <li>Exclusión mutua (DP-09). Si vence la espera, {@link ExclusiveSync} ya registró {@code LOCK_TIMEOUT}.</li>
 * <li>La versión local se lee <strong>adentro</strong>: durante la espera otra sincronización pudo haberla cambiado.</li>
 * <li>Segunda barrera: si {@code newVersion} no supera a la versión local, no hay nada que aplicar. El {@code eventId} se
 * registra en una transacción corta propia (flujo 7a).</li>
 * <li>Si hace falta, {@link #catchUp} pone la copia al día y registra el {@code eventId} junto con los datos.</li>
 * </ol>
 * 7a no escribe {@code sync_status}: no pasó nada que informar. Las escrituras que sí lo hacen pasan por CU-02 o por
 * {@link SyncErrorRecorder}, que leen y guardan en la misma transacción como pide {@code SyncStatusRepository}.
 * <p>
 * Las fallas de CU-02 vuelven como resultado. Una falla de la base en las barreras <strong>sale como excepción</strong>,
 * a propósito: la toma el manejador de errores del adaptador de Kafka, que reintenta con las esperas de DP-08. Si la base
 * vuelve en esos segundos el aviso se procesa; atrapándola acá habría un solo intento y se perdería hasta el próximo aviso.
 * <p>
 * <strong>No puede ser {@code @Transactional}</strong>, por lo mismo que CU-01: ver {@link ExclusiveSync}.
 */
@Component
@RequiredArgsConstructor
public class HandleCatalogUpdatedUseCaseImpl implements HandleCatalogUpdatedUseCase {

    static final String INVALID_EVENT_DESCRIPTION = "Se descartó un aviso de catálogo ilegible o incompleto: no se aplicó ningún cambio";
    static final String UNEXPECTED_FAILURE_DESCRIPTION =
        "No se pudo procesar un aviso de catálogo después de los reintentos: no se aplicó ningún cambio";

    private final ProcessedEventRepository processedEventRepository;
    private final GetSyncStatusUseCase getSyncStatusUseCase;
    private final ExecuteFullSyncUseCase executeFullSyncUseCase;
    private final ExclusiveSync exclusiveSync;
    private final SyncErrorRecorder syncErrorRecorder;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Override
    public CatalogEventResult handle(CatalogUpdateNotice notice) {
        Objects.requireNonNull(notice, "Falta el aviso de catálogo");
        if (alreadyProcessed(notice)) {
            return new CatalogEventResult.Duplicate(notice.eventId());
        }
        return exclusiveSync
            .run(SyncTrigger.KAFKA, () -> incorporate(notice))
            .orElseGet(CatalogEventResult.NotExecuted::new);
    }

    @Override
    public CatalogEventResult rejectInvalid() {
        syncErrorRecorder.record(SyncErrorType.INVALID_EVENT, INVALID_EVENT_DESCRIPTION);
        return new CatalogEventResult.Invalid();
    }

    @Override
    public void reportUnexpectedFailure() {
        syncErrorRecorder.record(SyncErrorType.EVENT_PROCESSING_FAILED, UNEXPECTED_FAILURE_DESCRIPTION);
    }

    private boolean alreadyProcessed(CatalogUpdateNotice notice) {
        return Boolean.TRUE.equals(transactionTemplate.execute(tx -> processedEventRepository.exists(notice.eventId())));
    }

    private CatalogEventResult incorporate(CatalogUpdateNotice notice) {
        Long localVersion = getSyncStatusUseCase.getSyncStatus().getLocalVersion();
        if (localVersion != null && notice.newVersion() <= localVersion) {
            transactionTemplate.executeWithoutResult(tx -> processedEventRepository.registerIfAbsent(notice.processedAt(clock.instant())));
            return new CatalogEventResult.UpToDate(localVersion, notice.newVersion());
        }
        return catchUp(notice, localVersion);
    }

    /**
     * <strong>Provisorio hasta CU-04.</strong> Sin Redis no se puede elegir el camino, así que siempre es un snapshot. CU-04
     * reemplaza este método por la lectura de la metadata y la elección entre el incremental y el snapshot, sin tocar las
     * barreras ni el adaptador de Kafka.
     */
    private CatalogEventResult catchUp(CatalogUpdateNotice notice, Long localVersion) {
        return switch (executeFullSyncUseCase.executeFullSync(SyncTrigger.KAFKA, notice)) {
            case FullSyncResult.Applied applied -> new CatalogEventResult.Applied(
                localVersion,
                applied.snapshotVersion(),
                SyncPath.SNAPSHOT,
                applied.attempts()
            );
            case FullSyncResult.Failed failed -> new CatalogEventResult.Failed(failed.errorType(), failed.description(), failed.attempts());
        };
    }
}
