# Eventos públicos

Todos los eventos de NetworkPoints son Paper/Bukkit, inmutables, no cancelables y se despachan en el hilo principal. Registralos con un `Listener` normal.

## `PlayerPointsReadyEvent`

Se dispara cuando la precarga de un jugador online publica su snapshot local. Garantiza que `cachedBalance(snapshot.playerId())` puede observar el saldo en ese servidor.

```java
@EventHandler
public void onReady(PlayerPointsReadyEvent event) {
    BalanceSnapshot snapshot = event.snapshot();
    initializeHud(snapshot.playerId(), snapshot.balance());
}
```

No se dispara por una llamada explícita a `balance` o `refreshBalance`, ni por jugadores offline. Una ausencia previa de caché no equivale a saldo cero.

## `PointsBalanceChangeEvent`

Se emite después de un commit local nuevo de `AWARD`, `CREDIT`, `DEBIT` o `SET_BALANCE`.

| Miembro | Contenido |
|---|---|
| `operationId()` | ID idempotente confirmado. |
| `type()` | `MutationType` de la operación. |
| `before()` | Snapshot anterior. |
| `after()` | Snapshot confirmado. |
| `delta()` | `after.balance - before.balance`. |

Un `SET_BALANCE` al valor actual puede tener delta cero, pero incrementa la revisión y emite el evento.

## `PointsAwardEvent`

Se emite además de `PointsBalanceChangeEvent` para un premio `AWARD` local nuevo.

| Miembro | Contenido |
|---|---|
| `operationId()` | ID idempotente confirmado. |
| `before()` / `after()` | Snapshots del receptor. |
| `baseAmount()` | Cantidad antes de boosters. |
| `multiplier()` | Multiplicador aplicado. |
| `finalAmount()` | Crédito efectivo redondeado. |

## `PointsTransferEvent`

Se emite después de una transferencia local nueva y atómica. Contiene `operationId()`, los snapshots anterior y posterior de emisor y receptor, y `amount()`.

Una transferencia NO genera dos `PointsBalanceChangeEvent`; su notificación pública es únicamente `PointsTransferEvent`.

## Cuándo no hay evento

No se emiten eventos públicos por:

- rechazos de negocio;
- replays idempotentes, incluso si reproducen un éxito;
- `IDEMPOTENCY_CONFLICT`;
- refreshes de caché originados por Redis;
- llamadas de lectura;
- cuentas offline cargadas explícitamente.

Los eventos representan commits originados en la instancia local, no un stream global durable. Para reaccionar exactamente una vez a una operación propia, el plugin originador debe usar su resultado durable y `operationId`; no debe depender de recibir un evento distribuido.

## Orden y callbacks

NetworkPoints publica primero la caché local y luego agenda los eventos. Cuando la mutación completa fuera del hilo principal, el future puede completar antes de que el scheduler ejecute el evento. No dependas de un orden entre tu callback y tu listener.

Los eventos son notificaciones posteriores al commit: no pueden cancelar ni modificar la economía. Si necesitás validar una compra, hacelo antes de solicitarla o interpretando el resultado de la mutación.
