# Mutaciones e idempotencia

## Operaciones

| Método | Efecto | Boosters | Reglas principales |
|---|---|---|---|
| `award(AwardRequest)` | Acredita una recompensa calculada | Sí, si la integración está habilitada | Base positiva; exige `gameId` y `serverId`. |
| `credit(CreditRequest)` | Acredita directamente | No | Cantidad positiva. |
| `debit(DebitRequest)` | Debita directamente | No | Cantidad positiva; no permite sobregiro. |
| `setBalance(SetBalanceRequest)` | Asigna un saldo absoluto | No | Admite cero; respeta el máximo configurado. |
| `transfer(TransferRequest)` | Debita y acredita dos cuentas atómicamente | No | Cuentas distintas y cantidad positiva. |

Las solicitudes validan al construirse y lanzan `NullPointerException` o `IllegalArgumentException` ante datos inválidos. Las cantidades válidas tienen como máximo dos decimales y quedan normalizadas a escala 2.

Una mutación exitosa confirma en una única transacción MySQL el saldo, la revisión, la auditoría y el resultado idempotente. Una transferencia confirma ambos saldos y sus dos entradas de auditoría de forma atómica.

## `MutationContext`

Cada mutación exige:

```java
public record MutationContext(
        UUID operationId,
        Key source,
        Optional<UUID> actorId,
        Optional<String> sourceReference)
```

| Campo | Finalidad | Restricción |
|---|---|---|
| `operationId` | Clave global de idempotencia | No nula; generada y administrada por el consumidor. |
| `source` | Sistema/acción originadora | `Key` namespaced; representación máxima de 128 caracteres. |
| `actorId` | Jugador que inició la acción | Vacío para procesos de sistema. |
| `sourceReference` | Correlación externa auditable | Vacío o texto no blanco de hasta 255 caracteres. |

`MutationContext.create(...)` es un atajo cuando actor y referencia están presentes.

Usá fuentes estables y específicas, por ejemplo `shop:purchase`, `quests:completion` o `rewards:match_reward`. La referencia debería apuntar al ID durable del pedido, partida o recompensa.

## Regla de idempotencia

El primer resultado terminal persistible de una solicitud reclama `operationId`. Un retry compatible devuelve el resultado original con `replayed() == true`, sin nueva revisión, auditoría, invalidación Redis ni evento. `INVALID_AMOUNT` se evita mediante validación previa e `IDEMPOTENCY_CONFLICT` informa que el ID ya estaba reclamado por otra solicitud; ninguno crea un registro nuevo para esa solicitud incompatible.

La compatibilidad exige igualdad de:

- tipo de operación;
- cuenta y contraparte;
- cantidad solicitada por valor numérico;
- `operationId`, `source`, `actorId` y `sourceReference`;
- `gameId` y `serverId` para premios.

Reutilizar el mismo ID con datos distintos produce `IDEMPOTENCY_CONFLICT`. NO generes un ID nuevo al reintentar una operación cuyo commit es incierto: eso puede ejecutar el efecto dos veces.

```java
void submit(UUID operationId) {
    MutationContext context = new MutationContext(
            operationId,
            Key.key("shop:purchase"),
            Optional.of(playerId),
            Optional.of(orderId));
    points.debit(new DebitRequest(playerId, price, context));
}
```

Persistí `operationId` junto a la operación de negocio antes de enviarla. Ante timeout o respuesta perdida, repetí exactamente la misma solicitud.

## Estados

| Estado | Significado | Terminal |
|---|---|---|
| `SUCCESS` | Commit confirmado. | Sí |
| `INSUFFICIENT_FUNDS` | Débito o transferencia excede el saldo autoritativo. | Sí |
| `BALANCE_LIMIT_EXCEEDED` | El saldo resultante supera el máximo configurado. | Sí |
| `INVALID_AMOUNT` | Categoría pública para cantidad inválida. La implementación actual rechaza esos datos al construir la solicitud y no la emite desde el motor durable. | Sí |
| `ACCOUNT_NOT_FOUND` | Falta una cuenta requerida. | Sí |
| `BOOSTER_STATE_NOT_READY` | NetworkBoosters todavía no puede calcular el premio. | No |
| `IDEMPOTENCY_CONFLICT` | El ID ya pertenece a otra solicitud. | Sí |
| `SERVICE_UNAVAILABLE` | El servicio ya no acepta trabajo, normalmente durante apagado. | No |

`status.terminal()` devuelve `false` solo para `BOOSTER_STATE_NOT_READY` y `SERVICE_UNAVAILABLE`. Los estados no terminales no reclaman el ID y permiten repetir la misma solicitud cuando desaparezca la condición temporal.

Los rechazos durables que actualmente pueden reaparecer con `replayed == true` son `INSUFFICIENT_FUNDS`, `BALANCE_LIMIT_EXCEEDED` y `ACCOUNT_NOT_FOUND`. Un conflicto no representa un replay compatible.

## Resultados exitosos

`MutationResult` incluye `before`, `after`, `delta`, `baseAmount`, `multiplier` y `finalAmount`. Todos están presentes en `SUCCESS`.

| Tipo | `delta` | `baseAmount` | `multiplier` | `finalAmount` |
|---|---:|---:|---:|---:|
| `AWARD` | crédito efectivo | base solicitada | multiplicador aplicado | base por multiplicador, `HALF_UP` a 2 decimales |
| `CREDIT` | positivo | cantidad solicitada | 1 | cantidad solicitada |
| `DEBIT` | negativo | cantidad solicitada positiva | 1 | cantidad solicitada positiva |
| `SET_BALANCE` | nuevo menos anterior | saldo nuevo | 1 | saldo nuevo |

`TransferResult` contiene snapshots y deltas de ambos participantes. En éxito, `senderDelta == -amount`, `recipientDelta == amount`, y base, final y `amount` coinciden con multiplicador 1.

En un rechazo, los `Optional` pueden estar vacíos. Inspeccioná primero `status()` o `success()` y no llames `orElseThrow()` sobre payloads de rechazo.

## Fallos de infraestructura

Un resultado normal expresa una decisión de negocio. Errores JDBC, fallos del executor u otros problemas de infraestructura completan el future excepcionalmente si no existe un resultado durable recuperable.

```java
operation.whenComplete((result, failure) -> {
    if (failure != null) {
        // Estado incierto para el consumidor: conservar operationId y reintentar la misma solicitud.
        scheduleRetry();
        return;
    }
    if (!result.success()) {
        handleBusinessStatus(result.status());
    }
});
```

Un future exitoso representa un resultado posterior al commit. La caché local ya fue publicada antes de completarlo. Redis y los eventos son efectos posteriores best-effort y no revierten la transacción.

## Premios con boosters

`AwardRequest` contiene cantidad base, `gameId` y `serverId`. Ambos IDs deben ser texto ASCII imprimible, no blanco y de hasta 64 caracteres.

Con NetworkBoosters habilitado, NetworkPoints usa una única captura de cálculo. Si no está lista devuelve `BOOSTER_STATE_NOT_READY`; un multiplicador 1 presente es un premio neutral válido. El valor final es `baseAmount * multiplier`, redondeado una sola vez a dos decimales con `HALF_UP`.

Con la integración deshabilitada, `award` aplica multiplicador 1. Usá `credit`, no `award`, cuando el crédito deliberadamente no deba participar en boosters.
