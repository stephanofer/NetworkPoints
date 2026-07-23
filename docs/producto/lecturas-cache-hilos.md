# Lecturas, caché e hilos

## Elegir una lectura

| Método | I/O | Resultado | Uso correcto |
|---|---|---|---|
| `cachedBalance(UUID)` | Nunca | `Optional<BalanceSnapshot>` inmediato | Render frecuente que puede tolerar “no disponible”. |
| `balance(UUID)` | Solo si la caché lo requiere | `CompletableFuture<BalanceSnapshot>` | Lectura normal de un saldo existente. |
| `refreshBalance(UUID)` | Fuerza MySQL | `CompletableFuture<BalanceSnapshot>` | Revalidación explícita o diagnóstico. |

Los tres métodos rechazan un UUID nulo. `balance` y `refreshBalance` completan excepcionalmente si la cuenta no existe o falla la infraestructura; no devuelven un snapshot sintético con cero.

## `BalanceSnapshot`

```java
public record BalanceSnapshot(UUID playerId, BigDecimal balance, long revision)
```

- `balance` es no negativo y se normaliza a escala 2.
- `revision` es no negativa y aumenta en cada mutación confirmada, incluso si `setBalance` asigna el mismo valor.
- El record es inmutable y seguro para compartir entre hilos.
- Compará revisiones, no tiempos: una revisión mayor representa estado más nuevo de esa cuenta.

## Caché y readiness

Al entrar un jugador, NetworkPoints crea o actualiza su cuenta y publica el snapshot local. Después dispara `PlayerPointsReadyEvent` en el hilo principal. Desde ese evento, `cachedBalance(playerId)` ya puede observar el snapshot.

```java
@EventHandler
public void onPointsReady(PlayerPointsReadyEvent event) {
    BalanceSnapshot snapshot = event.snapshot();
    renderSidebar(snapshot.playerId(), snapshot.balance());
}
```

Una ausencia en `cachedBalance` significa únicamente “este servidor no tiene ahora un snapshot completado”. NO significa saldo cero ni cuenta inexistente.

La caché puede desaparecer por salida del jugador, expiración, límite de tamaño o apagado. `balance` vuelve a cargarla cuando corresponde. Las cargas concurrentes para la misma cuenta se deduplican y la publicación de un snapshot nunca reemplaza una revisión superior ya completada.

## Consistencia entre servidores

Después de un commit local, NetworkPoints publica por Redis la revisión nueva. Un servidor remoto solo refresca desde MySQL si ya tenía esa cuenta en caché y la revisión recibida es superior. Si Redis no está operativo, el commit sigue siendo correcto; la caché remota converge por sus políticas de refresh/expiración o por una lectura forzada.

Por eso:

- Usá una mutación durable para compras, cobros y verificaciones de fondos.
- No implementes “leer caché y después debitar” como garantía de saldo.
- Usá `refreshBalance` cuando necesites una lectura autoritativa explícita, aceptando su costo de I/O.

## Hilos y futures

Los métodos síncronos `cachedBalance`, `parseAmount`, `formatAmount` y `formatAmountPlain` son thread-safe y no hacen I/O. Todos los métodos que devuelven `CompletableFuture` son asíncronos y sus callbacks no garantizan el hilo principal.

```java
points.balance(playerId).whenComplete((snapshot, failure) ->
        getServer().getScheduler().runTask(this, () -> {
            if (failure != null) {
                player.sendMessage("Points are temporarily unavailable");
                return;
            }
            player.sendMessage(points.formatAmount(snapshot.balance()));
        }));
```

No uses `join()`, `get()` ni esperas activas en el hilo principal. Los eventos públicos, a diferencia de los callbacks de futures, sí se despachan en el hilo principal.
