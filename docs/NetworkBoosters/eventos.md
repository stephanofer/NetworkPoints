# Eventos Paper

Todos los eventos públicos se despachan en el hilo principal. Los eventos posteriores a mutación ocurren después del commit durable; cancelar uno de ellos no revierte estado. Solo `BoosterPreActivateEvent` es cancelable.

## Base común

Todos salvo `BoosterPreActivateEvent` y `BoosterTransferEvent` heredan `AbstractPlayerBoostersEvent` y exponen:

| Método | Contenido |
|---|---|
| `playerId()` | Jugador afectado. |
| `revision()` | Revisión del snapshot posterior al cambio. |
| `origin()` | `LOCAL`, `REMOTE` o `RECONCILIATION`. |
| `sourceServerId()` | Servidor que originó o reportó el cambio. |
| `snapshot()` | Estado inmutable asociado a esa revisión. |

`BoosterEventOrigin`:

- `LOCAL`: la mutación se confirmó en esta instancia.
- `REMOTE`: se recibió por Redis y se refrescó el estado desde MySQL.
- `RECONCILIATION`: el sondeo de revisiones detectó que la caché estaba atrasada.

Los consumidores deben tolerar eventos repetidos semánticamente y usar `revision`/IDs si necesitan deduplicar efectos externos. Un evento remoto solo se reconstruye para jugadores cuyo snapshot está cargado en esa instancia.

## Readiness

### `PlayerBoostersReadyEvent`

Se emite al terminar la carga inicial para un jugador que continúa online. Expone `player()` y el contrato base; su origen siempre es `LOCAL`.

No se emite por cada `refresh()` manual ni para cargas explícitas de jugadores offline.

## Antes de activar

### `BoosterPreActivateEvent`

Expone `player()`, `request()`, `definition()` y el snapshot preoperación. Implementa `Cancellable`.

```java
@EventHandler(ignoreCancelled = true)
public void onPreActivate(BoosterPreActivateEvent event) {
    if (combatService.isInCombat(event.player())) {
        event.setCancelled(true);
    }
}
```

Si se cancela, `activate(...)` devuelve `PRE_ACTIVATION_CANCELLED` y no consume inventario. El mismo status también puede aparecer si ya no es posible ejecutar el evento con el jugador online.

## Activaciones y cola

| Evento | Datos específicos | Momento |
|---|---|---|
| `BoosterActivateEvent` | `activeBooster()`, `consumedQueueEntry()` | Nueva activación o promoción desde cola. |
| `BoosterExtendEvent` | `activeBooster()` | Duración del activo extendida. |
| `BoosterQueueEvent` | `queuedBooster()`, `merged()` | Entrada creada o duración fusionada. |
| `BoosterDeactivateEvent` | `deactivatedBooster()`, `promotedBooster()` | Desactivación explícita. Si promociona, después se emite también `BoosterActivateEvent`. |
| `BoosterExpireEvent` | `expiredActiveBooster()`, `expiredQueuedBooster()` | Expiró un activo o una entrada fue consumida por avance temporal. Al menos uno está presente. |

Una activación exitosa también genera `BoosterInventoryChangeEvent` con causa `ACTIVATION_CONSUMPTION` antes del evento de activación/cola dentro del mismo despacho.

## Inventario y claims

### `BoosterInventoryChangeEvent`

Expone `boosterId()`, `previousAmount()`, `newAmount()`, `delta()`, `cause()` y `referenceId()`.

`InventoryChangeCause`: `GRANT`, `REVOKE`, `SET`, `ACTIVATION_CONSUMPTION`, `TRANSFER_DEBIT`, `TRANSFER_CREDIT`, `CLAIM`.

En eventos reconstruidos por sincronización remota, un diff genérico puede representarse como `GRANT` o `REVOKE` y omitir `referenceId`; no usar `cause` remoto como registro contable definitivo. Para auditoría autoritativa, basarse en la operación propia o en persistencia de NetworkBoosters.

### `BoosterClaimCreatedEvent`

Expone el claim pendiente mediante `claim()`.

### `BoosterClaimEvent`

Se emite al cobrar y expone `claim()` en estado `CLAIMED` e `inventoryAmount()`. El cobro local emite antes un cambio de inventario con causa `CLAIM` y referencia al claim.

## Transferencias

`BoosterTransferEvent` no hereda la base porque afecta dos jugadores. Expone:

- `transferId()`, `senderId()`, `recipientId()`, `boosterId()` y `amount()`.
- `senderRevision()` y `recipientRevision()`.
- `senderSnapshot()` y `recipientSnapshot()`, ambos opcionales.
- `origin()` y `sourceServerId()`.

En una transferencia local ambos snapshots están presentes. En observación remota, el evento se reporta de forma determinista desde el lado sender y el snapshot del receptor puede faltar si no está cargado o todavía no alcanzó la revisión requerida.

## Listener recomendado

```java
@EventHandler
public void onInventoryChanged(BoosterInventoryChangeEvent event) {
    if (event.origin() == BoosterEventOrigin.RECONCILIATION) {
        refreshUiFrom(event.snapshot());
        return;
    }
    metrics.record(event.boosterId().value(), event.delta());
}
```

No ejecutar I/O lento en listeners: están en el hilo principal. Delegar trabajo externo a un executor propio y capturar solo los valores inmutables necesarios.

## Garantías y límites

- Los listeners que lanzan `RuntimeException` son aislados y registrados; no deshacen el commit.
- Los cambios sin una reconstrucción pública exacta pueden actualizar el snapshot sin producir un evento específico de claim/activación remoto.
- Redis es señal de invalidación, no fuente de verdad. El snapshot del evento remoto se vuelve a leer desde MySQL.
- La revisión permite descartar invalidaciones antiguas y evita regresiones de caché.
