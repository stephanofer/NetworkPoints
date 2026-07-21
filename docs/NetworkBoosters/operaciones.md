# Operaciones y resultados

## Convenciones

- Crear requests con datos válidos; sus constructores rechazan `null` y cantidades fuera de rango.
- Esperar readiness del jugador antes de mutar. Las operaciones devuelven `PLAYER_NOT_READY` cuando corresponde.
- Interpretar `status()` primero y acceder a payloads opcionales solo para estados que los garantizan.
- Las operaciones exitosas incrementan la revisión, actualizan caché local, emiten eventos y publican invalidación Redis después del commit.
- `SERVICE_UNAVAILABLE` no confirma ningún cambio. No reintentar ciegamente operaciones no idempotentes.

## Activar

```java
ActivationRequest request = new ActivationRequest(
    playerId,
    BoosterId.of("personal_points_x2"),
    ActivationSource.SYSTEM,
    new SourceReference(
        Optional.empty(),
        Optional.of("battle-pass:season-4:tier-20"),
        Optional.of("skywars-01")
    )
);
CompletableFuture<ActivationResult> future = boosters.activate(request);
```

Cada activación exitosa consume exactamente una unidad. Antes del commit se validan definición, permisos y `BoosterPreActivateEvent`; la definición se revalida dentro de la transacción para detectar reload concurrente.

| `ActivationStatus` | Significado | Payload |
|---|---|---|
| `ACTIVATED` | Se creó un activo en grupo libre. | `activeBooster` |
| `EXTENDED` | Se sumó duración a un activo compatible. | `activeBooster` actualizado |
| `QUEUED` | Se creó entrada al final de la cola. | `queuedBooster` |
| `QUEUE_MERGED` | Se sumó duración a la última entrada compatible. | `queuedBooster` actualizado |
| `REPLACED` | La política reemplazó el activo incompatible. | nuevo `activeBooster` |
| `NOT_OWNED` | No había una unidad disponible al confirmar. | Sin booster |
| `DEFINITION_NOT_FOUND` | ID desconocido o eliminado durante la operación. | Sin booster |
| `DEFINITION_CHANGED` | La definición cambió entre prevalidación y transacción. | Sin booster |
| `DEFINITION_DISABLED` | Definición deshabilitada. | Sin booster |
| `PERMISSION_DENIED` | No cumple `ActivationRequirements`. | Sin booster |
| `PRE_ACTIVATION_CANCELLED` | Un listener canceló el evento o el jugador dejó de estar online. | Sin booster |
| `GROUP_OCCUPIED` | Conflicto con política `REJECT`. | Sin booster |
| `QUEUE_LIMIT_REACHED` | No cabe otra entrada y no pudo fusionarse. | Sin booster |
| `DURATION_LIMIT_REACHED` | La línea temporal excedería el máximo configurado. | Sin booster |
| `PLAYER_NOT_READY` | Snapshot no cargado. | Sin booster |
| `SERVICE_UNAVAILABLE` | Servicio cerrado o fallo de infraestructura. | Sin booster |

`remainingInventoryAmount` siempre es no negativo. Solo los cinco primeros estados consumen una unidad.

## Otorgar inventario

`InventoryGrantRequest(playerId, boosterId, amount, source, sourceReference, force)` exige `amount > 0`.

Un grant normal respeta capacidad total. Si no cabe y el source es `PURCHASE` o `COMPENSATION`, crea automáticamente un claim pendiente. Los demás sources devuelven límite alcanzado. `force` solo se autoriza con `MutationSource.ADMIN_COMMAND`: consola sin actor, o actor online con `networkboosters.admin.give.force`.

```java
InventoryGrantRequest request = new InventoryGrantRequest(
    playerId,
    BoosterId.of("personal_points_x2"),
    3,
    MutationSource.PURCHASE,
    new SourceReference(Optional.empty(), Optional.of(orderId), Optional.of("store")),
    false
);
```

### Idempotencia de grants

Si `externalReference` está presente, la clave lógica es operación `INVENTORY_GRANT` + `source` + referencia externa.

- Mismo jugador, booster y cantidad: `DUPLICATE_REQUEST`; no vuelve a otorgar.
- Misma clave con payload distinto: `IDEMPOTENCY_CONFLICT`.
- Sin referencia externa: no existe deduplicación automática.

Para compras y recompensas reintentables, usar una referencia globalmente estable y NO generar una nueva en cada intento.

## Revocar y fijar

`InventoryRevokeRequest` exige cantidad positiva. Si no hay suficiente devuelve `INSUFFICIENT_AMOUNT` sin modificar.

`InventorySetRequest` admite cantidad `0`, que elimina efectivamente la entrada. Aumentar exige definición existente y capacidad; disminuir puede operar aunque la definición haya sido removida. `force` sigue las mismas reglas administrativas del grant.

## Estados de inventario

| `InventoryMutationStatus` | Significado |
|---|---|
| `GRANTED` | Grant aplicado respetando capacidad. |
| `GRANTED_FORCED` | Grant administrativo que omitió capacidad. |
| `REVOKED` | Cantidad descontada. |
| `SET` | Cantidad reemplazada. |
| `UNCHANGED` | `set` pidió el valor ya existente. |
| `CLAIM_CREATED` | Se creó claim; inventario sin cambio. `claim()` está presente. |
| `DUPLICATE_REQUEST` | Grant idempotente ya procesado. |
| `IDEMPOTENCY_CONFLICT` | La referencia ya corresponde a otro payload. |
| `DEFINITION_NOT_FOUND` | Definición requerida inexistente. |
| `INSUFFICIENT_AMOUNT` | No alcanza para revocar. |
| `INVENTORY_LIMIT_REACHED` | La operación excedería capacidad o `long`. |
| `PLAYER_NOT_READY` | Snapshot no cargado. |
| `PERMISSION_DENIED` | Uso no autorizado de `force`. |
| `SERVICE_UNAVAILABLE` | Fallo de infraestructura. |

`InventoryMutationResult` siempre expone `boosterId`, `previousAmount` y `newAmount`. En rechazos tempranos algunos montos son `0`; no usarlos como lectura autoritativa. Para `UNCHANGED`, ambos son iguales.

## Claims

`createClaim(ClaimCreationRequest)` crea siempre un claim pendiente para una definición existente; no intenta primero llenar inventario. Devuelve `InventoryMutationResult` con `CLAIM_CREATED`.

`claim(new ClaimRequest(playerId, claimId))` mueve atómicamente el claim pendiente al inventario si cabe.

| `ClaimResultStatus` | Significado |
|---|---|
| `CLAIMED` | Claim cobrado; `claim` contiene versión `CLAIMED`. |
| `NOT_FOUND` | No existe o pertenece a otro jugador. |
| `ALREADY_CLAIMED` | Ya fue cobrado. |
| `INVENTORY_LIMIT_REACHED` | Sigue pendiente porque no cabe. |
| `PLAYER_NOT_READY` | Snapshot no cargado. |
| `SERVICE_UNAVAILABLE` | Fallo de infraestructura. |

`inventoryAmount` es la cantidad resultante del booster al cobrar; en otros estados puede ser la actual o `0`, según cuánto pudo verificarse.

## Transferir

`BoosterTransferRequest` exige IDs distintos y cantidad positiva a nivel de negocio. Ambos jugadores deben estar online y ready. Se valida definición habilitada, política, rango, permiso, cooldown, saldo y capacidad del receptor.

Para sources `PLAYER_COMMAND` y `PLAYER_MENU`, `sourceReference.actorId` debe existir, ser igual al sender y cumplir el permiso opcional de la definición. Sources administrativos/sistema omiten ese permiso específico.

| `TransferStatus` | Significado |
|---|---|
| `TRANSFERRED` | Débito y crédito atómicos; incluye `transferId` y montos resultantes. |
| `SAME_PLAYER` | Emisor y receptor iguales. |
| `RECIPIENT_NOT_ONLINE` | Receptor offline. |
| `NOT_TRANSFERABLE` | Definición ausente, deshabilitada o transferencia deshabilitada. |
| `INVALID_AMOUNT` | Fuera de mínimo/máximo configurado. |
| `INSUFFICIENT_AMOUNT` | Saldo insuficiente del emisor. |
| `RECIPIENT_LIMIT_REACHED` | Capacidad/overflow en receptor. |
| `COOLDOWN` | Todavía no puede repetir; `retryAt` está presente. |
| `PERMISSION_DENIED` | Actor o permiso inválido. |
| `PLAYER_NOT_READY` | Sender offline o algún snapshot no listo. |
| `SERVICE_UNAVAILABLE` | Servicio cerrado o fallo de infraestructura. |

El cooldown se calcula por sender + booster desde la última transferencia exitosa. Los bloqueos de ambos jugadores se toman en orden binario de UUID para evitar deadlocks.

## Desactivar

`DeactivationRequest(activationId, reason, sourceReference)` opera por ID de activación. No devuelve unidades al inventario. Si existe cola, promueve inmediatamente la siguiente entrada.

| `DeactivationStatus` | Significado |
|---|---|
| `DEACTIVATED` | Activo desactivado; puede incluir `promotedBooster`. |
| `EXPIRED` | Ya estaba temporalmente vencido y se reconcilió. |
| `NOT_FOUND` | ID inexistente. |
| `ALREADY_INACTIVE` | Existía pero ya no estaba activo. |
| `PLAYER_NOT_READY` | Propietario sin snapshot cargado. |
| `SERVICE_UNAVAILABLE` | Fallo de infraestructura. |

Para `DEACTIVATED` y `EXPIRED`, `deactivatedBooster` está presente. `promotedBooster` es opcional.
