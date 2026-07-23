# Referencia completa de la API

Esta es la superficie publicada por `networkpoints-api` 1.0.0. Todos los records validan sus invariantes en el constructor.

## `NetworkPointsService`

| Método | Retorno | Contrato |
|---|---|---|
| `cachedBalance(UUID)` | `Optional<BalanceSnapshot>` | Síncrono, thread-safe, sin I/O. |
| `balance(UUID)` | `CompletableFuture<BalanceSnapshot>` | Resuelve mediante caché y carga cuando corresponde. |
| `refreshBalance(UUID)` | `CompletableFuture<BalanceSnapshot>` | Fuerza recarga autoritativa. |
| `award(AwardRequest)` | `CompletableFuture<MutationResult>` | Premio elegible para boosters. |
| `credit(CreditRequest)` | `CompletableFuture<MutationResult>` | Crédito directo. |
| `debit(DebitRequest)` | `CompletableFuture<MutationResult>` | Débito directo. |
| `transfer(TransferRequest)` | `CompletableFuture<TransferResult>` | Transferencia atómica. |
| `setBalance(SetBalanceRequest)` | `CompletableFuture<MutationResult>` | Asignación absoluta. |
| `formatAmount(BigDecimal)` | `Component` | Presentación Adventure configurada. |
| `formatAmountPlain(BigDecimal, AmountDisplayMode)` | `String` | Presentación plain text explícita. |
| `parseAmount(String)` | `AmountParseResult` | Parseo tipado sin excepciones por input malformado. |

## `api.amount`

### `AmountDisplayMode`

`RAW`, `GROUPED`, `COMPACT`.

### `AmountParseResult`

Sealed interface con:

- `Success(BigDecimal amount)`.
- `Failure(Reason reason)`.
- `Reason`: `EMPTY`, `INVALID_FORMAT`, `TOO_MANY_DECIMALS`, `UNKNOWN_SUFFIX`, `OUT_OF_RANGE`.

### `MonetaryAmounts`

| Miembro | Función |
|---|---|
| `SCALE` | Constante `2`. |
| `MAX_VALUE` | Máximo absoluto de `DECIMAL(30,2)`. |
| `nonNegative(amount, name)` | Normaliza escala y exige `>= 0`. |
| `positive(amount, name)` | Normaliza escala y exige `> 0`. |
| `signed(amount, name)` | Normaliza cualquier signo. |
| `multiplier(value, name)` | Exige positivo y exacto para `DECIMAL(20,8)`. |

## `api.balance`

`BalanceSnapshot(UUID playerId, BigDecimal balance, long revision)`: vista inmutable, no negativa y revisionada de una cuenta.

## `api.source`

`MutationContext(UUID operationId, Key source, Optional<UUID> actorId, Optional<String> sourceReference)`.

Método estático:

```java
MutationContext.create(operationId, source, actorId, sourceReference)
```

La fábrica crea ambos opcionales como presentes; el constructor canónico permite omitirlos.

## `api.request`

| Tipo | Campos en orden | Validación específica |
|---|---|---|
| `AwardRequest` | `playerId`, `amount`, `gameId`, `serverId`, `context` | Cantidad positiva; IDs ASCII imprimibles no blancos, máximo 64. |
| `CreditRequest` | `playerId`, `amount`, `context` | Cantidad positiva. |
| `DebitRequest` | `playerId`, `amount`, `context` | Cantidad positiva. |
| `SetBalanceRequest` | `playerId`, `amount`, `context` | Cantidad no negativa. |
| `TransferRequest` | `senderId`, `recipientId`, `amount`, `context` | IDs distintos; cantidad positiva. |

## `api.result`

### Enums

- `MutationType`: `AWARD`, `CREDIT`, `DEBIT`, `SET_BALANCE`.
- `MutationStatus`: `SUCCESS`, `INSUFFICIENT_FUNDS`, `BALANCE_LIMIT_EXCEEDED`, `INVALID_AMOUNT`, `ACCOUNT_NOT_FOUND`, `BOOSTER_STATE_NOT_READY`, `IDEMPOTENCY_CONFLICT`, `SERVICE_UNAVAILABLE`.
- `MutationStatus.terminal()`: indica si la misma operación no debe reevaluarse como trabajo nuevo.

### `MutationResult`

Campos en orden:

```text
status, type, operationId, playerId, before, after, delta,
baseAmount, multiplier, finalAmount, replayed
```

Método `success()` equivale a `status() == SUCCESS`. Un éxito exige todos los opcionales y consistencia entre snapshots, delta y desglose monetario.

### `TransferResult`

Campos en orden:

```text
status, operationId, senderId, recipientId,
senderBefore, senderAfter, recipientBefore, recipientAfter,
amount, senderDelta, recipientDelta, baseAmount, multiplier,
finalAmount, replayed
```

Método `success()` equivale a `status() == SUCCESS`. Un éxito exige todos los opcionales, revisiones crecientes y deltas simétricos.

## `api.event`

| Tipo | Getters propios |
|---|---|
| `PlayerPointsReadyEvent` | `snapshot()` |
| `PointsBalanceChangeEvent` | `operationId()`, `type()`, `before()`, `after()`, `delta()` |
| `PointsAwardEvent` | `operationId()`, `before()`, `after()`, `baseAmount()`, `multiplier()`, `finalAmount()` |
| `PointsTransferEvent` | `operationId()`, `senderBefore()`, `senderAfter()`, `recipientBefore()`, `recipientAfter()`, `amount()` |

Todos extienden `Event` e incluyen los métodos Bukkit `getHandlers()` y `getHandlerList()`.

## Qué no es API pública

Solo el namespace `com.stephanofer.networkpoints.api` y sus subpaquetes constituye el contrato para consumidores. No uses tipos de `com.stephanofer.networkpoints.account`, `amount`, `award`, `command`, `config`, `feedback`, `identity`, `lifecycle`, `payment`, `persistence`, `placeholder`, `service` o `synchronization`.
