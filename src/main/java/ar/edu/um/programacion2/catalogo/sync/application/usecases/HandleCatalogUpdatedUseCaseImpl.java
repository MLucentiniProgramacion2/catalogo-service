package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogEventResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogUpdateNotice;
import ar.edu.um.programacion2.catalogo.sync.domain.model.IncorporationResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.GetSyncStatusUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.HandleCatalogUpdatedUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.IncorporateChangesUseCase;
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
 * <li>Si hace falta, {@link #catchUp} pone la copia al día con {@link IncorporateChangesUseCase} (Redis: incremental o
 * snapshot) y el {@code eventId} queda registrado junto con los datos.</li>
 * </ol>
 * El 7a de la segunda barrera no lee Redis ni escribe {@code sync_status}: el aviso ya no aporta nada. El "al día" que
 * se descubre recién con la metadata sí escribe la versión remota (lo hace {@link IncorporateChangesUseCase}).
 * <p>
 * Las fallas de CU-02, CU-04 y de la lectura de Redis vuelven como resultado. Una falla de la base en las barreras <strong>sale como excepción</strong>,
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
    private final IncorporateChangesUseCase incorporateChangesUseCase;
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
     * CU-03 con Redis: la metadata, la versión remota y el camino (incremental o snapshot) los decide
     * {@link IncorporateChangesUseCase}, que es el mismo para todos los disparadores. Acá solo se traduce su resultado al
     * de un aviso.
     */
    private CatalogEventResult catchUp(CatalogUpdateNotice notice, Long localVersion) {
        return switch (incorporateChangesUseCase.incorporate(SyncTrigger.KAFKA, localVersion, notice)) {
            case IncorporationResult.Applied applied -> new CatalogEventResult.Applied(
                applied.localVersionBefore(),
                applied.localVersionAfter(),
                applied.path(),
                applied.attempts()
            );
            case IncorporationResult.UpToDate upToDate -> new CatalogEventResult.UpToDate(upToDate.localVersion(), notice.newVersion());
            case IncorporationResult.Failed failed -> new CatalogEventResult.Failed(failed.errorType(), failed.description(), failed.attempts());
        };
    }
}
