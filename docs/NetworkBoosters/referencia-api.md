# Índice completo de la API

Referencia de todos los tipos publicados por `networkboosters-api` en la versión `1.0.0`. Los contratos detallados están en [Servicio](servicio.md), [Dominio](dominio.md), [Operaciones](operaciones.md) y [Eventos](eventos.md).

## Paquete raíz

| Tipo | Responsabilidad |
|---|---|
| `NetworkBoostersService` | Punto de entrada de catálogo, caché, cálculo y mutaciones. |

## `api.booster`

| Tipo | Forma | Responsabilidad |
|---|---|---|
| `ActivationGroup` | record | ID del grupo exclusivo de activación/cola. |
| `ActivationRequirements` | record | Permisos y modo `ALL`/`ANY`; incluye `NONE` y `satisfiedBy`. |
| `ActiveBooster` | record | Snapshot de una activación vigente; incluye `isActiveAt`. |
| `BoosterCategory` | record | Categoría normalizada de presentación. |
| `BoosterDefinition` | record | Configuración completa de un tipo de booster. |
| `BoosterId` | record | ID normalizado; incluye `of`. |
| `BoosterScope` | record | Scope por tipo, juegos y servidores; incluye `WILDCARD`, `personalGlobal` y `appliesTo`. |
| `BoosterScopeType` | enum | `PERSONAL`. |
| `BoosterTarget` | record | Target namespaced; incluye `NETWORK_PROGRESSION_POINTS` y `of`. |
| `ConflictPolicy` | enum | `QUEUE`, `REJECT`, `REPLACE`. |
| `OwnedBooster` | record | Cantidad positiva de un booster perteneciente a un jugador. |
| `PermissionMode` | enum | `ALL`, `ANY`. |
| `QueuedBooster` | record | Snapshot de una entrada en cola y su posición. |
| `TransferPolicy` | record | Habilitación, límites, cooldown y permiso; incluye `DISABLED`. |

## `api.calculation`

| Tipo | Forma | Responsabilidad |
|---|---|---|
| `BoostRequest` | record | Jugador, target, monto base y contexto; incluye `of`. |
| `BoostCalculation` | record | Base, multiplicador efectivo, resultado, activos aplicados y cap; incluye `neutral` y `boosted`. |
| `AppliedBoost` | record | Identidad y multiplicador de un activo considerado. |

## `api.player`

| Tipo | Forma | Responsabilidad |
|---|---|---|
| `PlayerBoostSnapshot` | record | Estado completo, inmutable y revisionado; incluye `empty`, `ownedAmount` y `ownedTotal`. |
| `BoosterClaim` | record | Recompensa pendiente o cobrada. |
| `ClaimStatus` | enum | `PENDING`, `CLAIMED`. |
| `PlayerReadiness` | enum | `NOT_READY`, `READY`; vocabulario de dominio, no retorno actual del servicio. |

## `api.request`

| Tipo | Campos en orden |
|---|---|
| `ActivationRequest` | `playerId`, `boosterId`, `source`, `sourceReference` |
| `InventoryGrantRequest` | `playerId`, `boosterId`, `amount`, `source`, `sourceReference`, `force` |
| `InventoryRevokeRequest` | `playerId`, `boosterId`, `amount`, `source`, `sourceReference` |
| `InventorySetRequest` | `playerId`, `boosterId`, `amount`, `source`, `sourceReference`, `force` |
| `BoosterTransferRequest` | `senderId`, `recipientId`, `boosterId`, `amount`, `source`, `sourceReference` |
| `ClaimRequest` | `playerId`, `claimId` |
| `ClaimCreationRequest` | `playerId`, `boosterId`, `amount`, `source`, `sourceReference` |
| `DeactivationRequest` | `activationId`, `reason`, `sourceReference` |

Todos los `sourceReference` nulos se normalizan a `SourceReference.none()`. Grants, revokes, transferencias y creación de claims exigen cantidad positiva; `InventorySetRequest` admite cero.

## `api.result`

| Tipo | Campos en orden |
|---|---|
| `ActivationResult` | `status`, `activeBooster`, `queuedBooster`, `remainingInventoryAmount` |
| `ActivationStatus` | Los 17 estados de activación documentados en [Operaciones](operaciones.md#activar). |
| `InventoryMutationResult` | `status`, `boosterId`, `previousAmount`, `newAmount`, `claim` |
| `InventoryMutationStatus` | Los 14 estados de inventario documentados en [Operaciones](operaciones.md#estados-de-inventario). |
| `TransferResult` | `status`, IDs de participantes/booster, `amount`, `transferId`, `retryAt` y montos resultantes opcionales |
| `TransferStatus` | Los 11 estados documentados en [Operaciones](operaciones.md#transferir). |
| `ClaimResult` | `status`, `claim`, `inventoryAmount` |
| `ClaimResultStatus` | Los 6 estados documentados en [Operaciones](operaciones.md#claims). |
| `DeactivationResult` | `status`, `deactivatedBooster`, `promotedBooster` |
| `DeactivationStatus` | Los 6 estados documentados en [Operaciones](operaciones.md#desactivar). |

Los records de resultado validan que los payloads coincidan con el status. Por ejemplo, `TRANSFERRED` exige ID y ambos montos; `COOLDOWN` exige `retryAt`; `CLAIMED` exige un claim cobrado.

## `api.source`

| Tipo | Valores / contenido |
|---|---|
| `SourceReference` | `actorId`, `externalReference`, `serverId`; fábricas `none` y `actor`. |
| `ActivationSource` | `PLAYER_COMMAND`, `PLAYER_MENU`, `ADMIN_COMMAND`, `SYSTEM`. |
| `MutationSource` | `ADMIN_COMMAND`, `PURCHASE`, `CRATE`, `BATTLE_PASS`, `EVENT`, `DAILY_REWARD`, `COMPENSATION`, `SYSTEM`. |
| `TransferSource` | `PLAYER_COMMAND`, `PLAYER_MENU`, `ADMIN_COMMAND`, `SYSTEM`. |
| `ClaimSource` | `ADMIN_COMMAND`, `PURCHASE`, `CRATE`, `BATTLE_PASS`, `EVENT`, `DAILY_REWARD`, `COMPENSATION`, `SYSTEM`. |
| `DeactivationReason` | `EXPIRED`, `REPLACED`, `ADMIN`, `SYSTEM`. |

## `api.event`

| Tipo | Responsabilidad |
|---|---|
| `AbstractPlayerBoostersEvent` | Base con jugador, revisión, origen, servidor y snapshot. |
| `PlayerBoostersReadyEvent` | Snapshot inicial disponible para un jugador online. |
| `BoosterPreActivateEvent` | Precondición cancelable antes de consumir una unidad. |
| `BoosterActivateEvent` | Activación nueva o promoción. |
| `BoosterExtendEvent` | Extensión de activo compatible. |
| `BoosterQueueEvent` | Alta o fusión de cola. |
| `BoosterDeactivateEvent` | Desactivación y posible promoción. |
| `BoosterExpireEvent` | Expiración activa o de cola. |
| `BoosterInventoryChangeEvent` | Cambio de cantidad con delta, causa y referencia. |
| `BoosterClaimCreatedEvent` | Nuevo claim pendiente. |
| `BoosterClaimEvent` | Claim cobrado. |
| `BoosterTransferEvent` | Transferencia entre dos jugadores y sus revisiones/snapshots opcionales. |
| `BoosterEventOrigin` | `LOCAL`, `REMOTE`, `RECONCILIATION`. |
| `InventoryChangeCause` | `GRANT`, `REVOKE`, `SET`, `ACTIVATION_CONSUMPTION`, `TRANSFER_DEBIT`, `TRANSFER_CREDIT`, `CLAIM`. |

## Qué no es API pública

No forman parte del contrato soportado los paquetes `com.stephanofer.networkboosters` del módulo Paper: servicios concretos, repositorios, configuración interna, coordinadores, menús, loaders, codecs y clases de sincronización. Consumirlos acopla el plugin a detalles que pueden cambiar sin compatibilidad binaria.
