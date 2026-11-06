package ar.edu.um.programacion2.catalogo.sync.infrastructure.messaging;

import ar.edu.um.programacion2.catalogo.sync.application.service.SyncService;
import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogEventResult;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Adaptador de entrada de CU-03: traduce cada mensaje de {@code catedra.catalog.{groupId}} y llama a {@link SyncService}.
 * <p>
 * Recibe el {@link ConsumerRecord} y no un {@code @Payload}: un tombstone (valor {@code null}) con {@code @Payload String}
 * falla antes de llegar acá, y no pasaría por el flujo 2a. Además da la partición y el offset para el log.
 * <p>
 * <strong>El offset se confirma cuando este método vuelve bien</strong>, después de persistir (contrato §16). Por eso todos
 * los flujos esperados vuelven bien, también los que fallaron: el aviso no trae datos, así que descartarlo no pierde
 * información (DP-08). Solo sale una excepción si pasó algo inesperado, y la toma {@code DefaultErrorHandler}.
 * <p>
 * En el log van la partición y el offset, nunca el contenido del mensaje.
 */
@Component
@RequiredArgsConstructor
public class CatalogUpdatedListener {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogUpdatedListener.class);

    private final SyncService syncService;

    @KafkaListener(topics = "${catedra.kafka.catalog-topic}")
    public void onCatalogUpdated(ConsumerRecord<String, String> message) {
        CatalogEventResult result = CatalogUpdatedMessageMapper.toNotice(message.value())
            .map(syncService::handleCatalogUpdated)
            .orElseGet(syncService::rejectInvalidCatalogEvent);
        logResult(result, message.partition(), message.offset());
    }

    /** El {@code switch} sin {@code default}: un resultado nuevo no compila hasta que se decida cómo informarlo. */
    private static void logResult(CatalogEventResult result, int partition, long offset) {
        switch (result) {
            case CatalogEventResult.Applied applied -> LOG.info(
                "Aviso de catálogo (partición {}, offset {}): versión local {} -> {} por {}",
                partition,
                offset,
                applied.localVersionBefore(),
                applied.localVersionAfter(),
                applied.path()
            );
            case CatalogEventResult.UpToDate upToDate -> LOG.info(
                "Aviso de catálogo (partición {}, offset {}): versión {} ya aplicada, la local es {}",
                partition,
                offset,
                upToDate.newVersion(),
                upToDate.localVersion()
            );
            case CatalogEventResult.Duplicate duplicate -> LOG.info(
                "Aviso de catálogo (partición {}, offset {}): evento {} duplicado, no se aplica",
                partition,
                offset,
                duplicate.eventId()
            );
            case CatalogEventResult.Failed failed -> LOG.warn(
                "Aviso de catálogo (partición {}, offset {}): no se aplicó, {} en {} intentos; se confirma el offset",
                partition,
                offset,
                failed.errorType(),
                failed.attempts()
            );
            case CatalogEventResult.NotExecuted notExecuted -> LOG.warn(
                "Aviso de catálogo (partición {}, offset {}): no se ejecutó, seguía otra sincronización; se confirma el offset",
                partition,
                offset
            );
            case CatalogEventResult.Invalid invalid -> LOG.warn(
                "Aviso de catálogo (partición {}, offset {}): mensaje ilegible o incompleto, se descarta y se confirma el offset",
                partition,
                offset
            );
        }
    }
}
