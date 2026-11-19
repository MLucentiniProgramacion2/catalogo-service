package ar.edu.um.programacion2.catalogo.sync.infrastructure.messaging;

import ar.edu.um.programacion2.catalogo.sync.application.service.SyncService;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

/**
 * Lo último que pasa con un aviso cuando se agotan los reintentos: deja el error en el log y en el estado de
 * sincronización (CU-09), y {@code DefaultErrorHandler} confirma el offset.
 * <p>
 * <strong>Nunca lanza una excepción.</strong> Si lo hiciera, el manejador tomaría el aviso como no recuperado y lo
 * volvería a entregar sin fin (medido: 18 entregas en 9 s y el offset sin moverse), justo cuando lo más probable es que
 * la base esté caída. Registrar es un extra; soltar la partición, no.
 */
@RequiredArgsConstructor
class CatalogEventRecoverer implements ConsumerRecordRecoverer {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogEventRecoverer.class);

    private final SyncService syncService;

    @Override
    public void accept(ConsumerRecord<?, ?> message, Exception failure) {
        LOG.error(
            "Aviso de catálogo descartado después de los reintentos (partición {}, offset {}); se confirma el offset",
            message.partition(),
            message.offset(),
            failure
        );
        try {
            syncService.reportCatalogEventFailure();
        } catch (RuntimeException e) {
            LOG.error("No se pudo registrar el aviso descartado en el estado de sincronización", e);
        }
    }
}
