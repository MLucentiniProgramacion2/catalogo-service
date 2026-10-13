# CU-08: Disparar la sincronización manualmente

## Objetivo

Que un administrador pueda forzar una sincronización (por ejemplo, durante la demo) sin esperar a
Kafka ni a la comprobación periódica, y sin reemplazar la sincronización automática.

## Actores

- **Principal:** Administrador (usuario con rol `ROLE_ADMIN`).
- **Secundarios:** Redis de la cátedra (modo normal, a través de CU-03) y API REST de la cátedra
  (modo snapshot, a través de CU-02).

## Disparador

El administrador pide una sincronización manual.

## Precondiciones

- El pedido trae un JWT de usuario válido con el rol `ROLE_ADMIN`. El rol lo emite el servicio de
  turnos en el token. No se acepta el token técnico de servicio (DP-06).

## Flujo principal

1. El administrador pide la sincronización indicando el modo:
   - **normal** (por defecto): incorporar los cambios pendientes;
   - **snapshot**: reconstruir la copia completa aunque la versión esté al día.
2. El catálogo valida el token y que el usuario tenga `ROLE_ADMIN`.
3. Según el modo:
   - **normal:** ejecuta **CU-03 Incorporar cambios del catálogo** con el disparador "manual"
     (compara versiones y aplica incremental o snapshot según corresponda);
   - **snapshot:** toma la exclusión mutua (DP-09) y ejecuta **CU-02 Ejecutar la sincronización
     completa**.
4. El catálogo responde con el resultado: versión local antes y después, camino aplicado
   (incremental, snapshot o sin cambios) y, si falló, el error (sin datos sensibles).

## Flujos alternativos

- **2a. Sin token, token inválido o vencido:** `401`.
- **2b. Usuario sin `ROLE_ADMIN`, o token técnico de servicio:** `403`.
- **2c. Modo desconocido:** `400` en `application/problem+json`.
- **3a. Se supera la espera máxima de la exclusión mutua:** no se aplica nada, queda registrado en
  el estado de sincronización y se informa al administrador que no pudo ejecutarse porque hay otra
  sincronización en curso.
- **3b. La sincronización falla** (Redis, cátedra o base): se informa el error; la copia queda en
  la última versión aplicada completa (DP-08).

## Postcondiciones

- **De éxito:** la copia local queda en la versión vigente de la cátedra (o reconstruida, en modo
  snapshot) y el resultado queda en el estado de sincronización.
- **De fallo:** la copia local queda consistente en la última versión aplicada completa y el error
  queda registrado.

## Decisiones del proyecto

- **DP-05 (permisos de operación):** la sincronización manual requiere `ROLE_ADMIN`. Depende de que
  turnos incluya el rol en el JWT que emite. El estado de sincronización (CU-09), en cambio, lo
  puede consultar cualquier usuario autenticado.
- **DP-10 (modos de la sincronización manual):** por defecto es un disparador más de CU-03 (misma
  lógica e idempotencia). Con un parámetro explícito fuerza un snapshot (CU-02), útil para mostrar
  la reconstrucción en la demo. Los dos modos respetan la exclusión mutua (DP-09).
- Ya estaba decidido que la sincronización es **automática** al recibir `CatalogUpdated`: la manual
  se suma, no la reemplaza.

## Respaldo en las consignas

- Enunciado §6.1: snapshot "cuando necesite reconstruirla".
- Enunciado §9: rol administrador opcional con permisos documentados y autorización correcta.
- Enunciado §11: evidencias mínimas de "una inicialización desde snapshot" y "recuperación mediante
  snapshot luego de una discontinuidad".
- Contrato §16: el flujo automático por `CatalogUpdated` sigue siendo el principal.
