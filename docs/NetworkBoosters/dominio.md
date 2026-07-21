# Modelo de dominio

Todos los records públicos validan sus invariantes al construirse y copian sus colecciones. Los argumentos obligatorios rechazan `null`.

## Identificadores

| Tipo | Campo | Formato y normalización |
|---|---|---|
| `BoosterId` | `value` | Minúsculas; `[a-z0-9][a-z0-9_-]{0,63}`. Fábrica `of(String)`. |
| `BoosterCategory` | `value` | Igual que `BoosterId`. Fábrica `of(String)`. |
| `ActivationGroup` | `value` | Igual que `BoosterId`. Fábrica `of(String)`. |
| `BoosterTarget` | `key` | Namespaced key; namespace máx. 64 y valor máx. 128. Fábrica `of(String)`. |

Los cuatro recortan espacios y normalizan a minúsculas. `BoosterTarget.NETWORK_POINTS` representa `network_points:points`.

## `BoosterDefinition`

Snapshot de configuración de un tipo de booster:

| Campo | Significado |
|---|---|
| `id` | ID estable del booster. |
| `target` | Recurso que multiplica. |
| `multiplier` | Multiplicador `BigDecimal`, mínimo `1`. |
| `duration` | Duración positiva por unidad activada. |
| `scope` | Contextos donde aplica. |
| `activationGroup` | Slot lógico de conflicto y cola. |
| `conflictPolicy` | `QUEUE`, `REJECT` o `REPLACE`. |
| `requirements` | Permisos necesarios al activar. |
| `transferPolicy` | Reglas de transferencia. |
| `enabled` | Si admite nuevas activaciones/transferencias. |
| `displayOrder` | Orden de presentación. |
| `category` | Categoría de UI/filtrado. |

Los activos y colas guardan snapshots de los valores relevantes. Un reload no reescribe boosters ya activados o encolados.

## Scope

`BoosterScope` contiene `type`, `gameIds` y `serverIds`. En 1.0.0 el único `BoosterScopeType` es `PERSONAL` y la configuración solo admite `serverIds: ["*"]`.

Cada set debe ser no vacío. `*` coincide con cualquier valor, incluso contexto ausente, y no puede combinarse con IDs explícitos. Los IDs explícitos usan `[a-z0-9][a-z0-9._-]{0,63}`.

`appliesTo(Optional<String> gameId, Optional<String> serverId)` exige que ambos ejes coincidan. Un scope de juego explícito no aplica si el request omite `gameId`.

## Activación y conflicto

`ActivationRequirements` contiene un set inmutable de permisos y `PermissionMode.ALL` o `ANY`. Sin permisos siempre se satisface. `satisfiedBy(Predicate<String>)` permite evaluar la regla sin Bukkit.

`ConflictPolicy` se aplica cuando el grupo ya está ocupado por un booster incompatible:

| Política | Efecto |
|---|---|
| `QUEUE` | Crea una entrada o fusiona duración con la última compatible. |
| `REJECT` | Devuelve `GROUP_OCCUPIED`; no consume inventario. |
| `REPLACE` | Desactiva el activo y crea uno nuevo; el tiempo restante reemplazado se pierde. |

Dos boosters son compatibles para extender/fusionar cuando sus propiedades persistidas de cola coinciden. Un booster compatible extiende el activo aunque su política sea otra.

`TransferPolicy` contiene `enabled`, `minimumAmount > 0`, `maximumAmount >= minimumAmount`, cooldown no negativo y permiso opcional. `DISABLED` es una constante utilitaria.

## Estado persistido

`PlayerReadiness` expone los valores `NOT_READY` y `READY`. El servicio público representa actualmente este estado mediante `isReady(UUID)`; el enum queda disponible como vocabulario tipado del dominio, pero ningún método de `NetworkBoostersService` lo devuelve en 1.0.0.

### `ActiveBooster`

Campos: `activationId`, `playerId`, `boosterId`, `target`, `multiplier`, `activationGroup`, `conflictPolicy`, `scope`, `requirements`, `activatedAt`, `expiresAt`, `source` y `sourceReference`.

`expiresAt` debe ser posterior a `activatedAt`. `isActiveAt(now)` usa intervalo abierto al final: es activo solo si `expiresAt.isAfter(now)`.

### `QueuedBooster`

Campos equivalentes al activo, más `queueId`, `duration`, `queuedAt` y `position`. La posición es no negativa; la duración es positiva. La cola de cada grupo aparece en posiciones estrictamente crecientes.

### `OwnedBooster`

Valor auxiliar con `playerId`, `boosterId` y `amount > 0`. El inventario principal se expone como mapa dentro del snapshot.

## `PlayerBoostSnapshot`

| Campo | Contrato |
|---|---|
| `playerId` | Propietario del estado. |
| `revision` | Revisión durable no negativa. |
| `inventory` | `Map<BoosterId, Long>`; solo entradas positivas. Ausencia equivale a cero. |
| `activeBoosters` | Máximo un activo por `ActivationGroup`. |
| `queuedBoosters` | Cola ordenada por grupo. |
| `pendingClaims` | Solo claims `PENDING`, sin IDs duplicados. |

Métodos auxiliares:

- `empty(UUID)`: snapshot vacío con revisión `0`.
- `ownedAmount(BoosterId)`: cantidad o `0`.
- `ownedTotal()`: suma exacta de todas las unidades; puede lanzar `ArithmeticException` ante overflow de `long`.

Las estructuras son copias inmutables. No se actualiza una instancia existente: una mutación publica otro snapshot con revisión mayor.

## Claims

`BoosterClaim` contiene `claimId`, `playerId`, `boosterId`, cantidad positiva, `ClaimSource`, `SourceReference`, `createdAt`, `claimedAt` y `ClaimStatus`.

- `PENDING` exige `claimedAt` vacío.
- `CLAIMED` exige `claimedAt` presente.
- El snapshot público solo contiene claims pendientes.

## Cálculo

`BoostRequest` contiene jugador, target, monto base y contexto opcional. Strings vacíos de contexto se normalizan a ausencia.

`BoostCalculation` contiene monto base, multiplicador efectivo positivo, monto final, lista inmutable de `AppliedBoost` y `capped`. `boosted()` equivale a `!appliedBoosts().isEmpty()`.

Cada `AppliedBoost` identifica `activationId`, `boosterId`, `activationGroup` y multiplicador original.

## Procedencia

`SourceReference` aporta metadatos de auditoría:

| Campo | Uso recomendado |
|---|---|
| `actorId` | UUID del jugador/admin que inició la acción. |
| `externalReference` | ID estable de compra, reward o transacción externa. |
| `serverId` | Servidor que originó la solicitud. |

Dispone de `none()` y `actor(UUID)`. Los textos se recortan y valores vacíos se convierten en `Optional.empty()`.

Enums de procedencia:

- `ActivationSource`: `PLAYER_COMMAND`, `PLAYER_MENU`, `ADMIN_COMMAND`, `SYSTEM`.
- `TransferSource`: `PLAYER_COMMAND`, `PLAYER_MENU`, `ADMIN_COMMAND`, `SYSTEM`.
- `MutationSource`: `ADMIN_COMMAND`, `PURCHASE`, `CRATE`, `BATTLE_PASS`, `EVENT`, `DAILY_REWARD`, `COMPENSATION`, `SYSTEM`.
- `ClaimSource`: los mismos valores de recompensa que `MutationSource`, sin contexto de activación.
- `DeactivationReason`: `EXPIRED`, `REPLACED`, `ADMIN`, `SYSTEM`.
