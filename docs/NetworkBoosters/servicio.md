# Servicio público

`NetworkBoostersService` es el único punto de entrada público. Agrupa catálogo, estado, cálculo y mutaciones.

## Catálogo

### `Optional<BoosterDefinition> definition(BoosterId boosterId)`

Busca una definición por ID en la configuración actualmente publicada. Puede devolver vacío si fue eliminada o nunca existió.

### `Collection<BoosterDefinition> definitions()`

Devuelve todas las definiciones cargadas, incluidas las deshabilitadas. Consultar `BoosterDefinition.enabled()` antes de ofrecer una activación o transferencia.

El catálogo puede cambiar tras `/boosters admin reload`; no conservar una definición indefinidamente si una operación depende de su configuración actual.

## Estado del jugador

| Método | I/O | Comportamiento |
|---|---:|---|
| `cached(UUID)` | No | `Optional` con el snapshot local, si existe. |
| `getCachedOrEmpty(UUID)` | No | Snapshot local o `PlayerBoostSnapshot.empty(playerId)`. |
| `load(UUID)` | Sí, si falta | Reutiliza caché/carga en curso y publica la versión obtenida. |
| `refresh(UUID)` | Sí | Lee durablemente aunque ya exista caché. |
| `isReady(UUID)` | No | `true` si existe snapshot local publicado. |

Los snapshots contienen una `revision` monotónica por jugador. NetworkBoosters evita reemplazar un snapshot por otro con revisión menor o igual. Las colecciones internas son inmutables.

## Cálculo

### Cálculo con fallback neutral

```java
BoostCalculation calculate(BoostRequest request)
```

Es síncrono, thread-safe y sin I/O. Solo usa el snapshot ya cacheado. Devuelve cálculo neutral si:

- no existe snapshot en caché;
- `baseAmount <= 0`;
- no hay activos vigentes con el mismo target;
- el scope no coincide con `gameId` y `serverId`.

Los multiplicadores aplicables se multiplican entre sí en orden determinista por grupo e ID de activación. El producto se limita a `limits.maximum-multiplier`; `capped()` indica si se aplicó ese límite.

```java
BoostCalculation calculation = boosters.calculate(BoostRequest.of(
    player.getUniqueId(),
    BoosterTarget.NETWORK_POINTS,
    BigDecimal.valueOf(points),
    "skywars",
    "skywars-01"
));

BigDecimal finalPoints = calculation.finalAmount();
```

NetworkBoosters no redondea `finalAmount`. El plugin dueño del recurso decide cómo convertir el `BigDecimal` al tipo final y debe definir explícitamente su `RoundingMode`.

`appliedBoosts()` enumera los activos realmente considerados, incluso cuando el producto fue limitado. `multiplier()` contiene el multiplicador efectivo después del límite.

### Cálculo que distingue readiness

```java
Optional<BoostCalculation> calculateIfReady(BoostRequest request)
```

Esta es la operación recomendada para conceder recursos cuando el consumidor necesita reintentar si los boosters todavía no están listos. Adquiere el snapshot una sola vez y devuelve:

| Resultado | Significado |
|---|---|
| `Optional.empty()` | No había snapshot listo al adquirirlo; no conceder y permitir reintento. |
| `Optional.of(calculation)` | El cálculo usó un snapshot listo. Puede ser neutral si ningún booster aplicaba. |

No usar `isReady(playerId)` seguido de `calculate(request)` para este caso: el jugador puede descargarse entre ambas llamadas. Si la descarga sucede después de que `calculateIfReady` adquirió el snapshot, el cálculo continúa correctamente porque el snapshot es inmutable.

### Cálculo contra un snapshot conocido

```java
BoostCalculation calculate(BoostRequest request, PlayerBoostSnapshot snapshot)
```

Calcula contra un snapshot proporcionado por el consumidor, sin consultar la caché. El `playerId` del request y del snapshot debe coincidir; de lo contrario se lanza `IllegalArgumentException`.

## Mutaciones

| Método | Request | Resultado |
|---|---|---|
| `activate` | `ActivationRequest` | `ActivationResult` |
| `grant` | `InventoryGrantRequest` | `InventoryMutationResult` |
| `revoke` | `InventoryRevokeRequest` | `InventoryMutationResult` |
| `setInventoryAmount` | `InventorySetRequest` | `InventoryMutationResult` |
| `transfer` | `BoosterTransferRequest` | `TransferResult` |
| `claim` | `ClaimRequest` | `ClaimResult` |
| `createClaim` | `ClaimCreationRequest` | `InventoryMutationResult` |
| `deactivate` | `DeactivationRequest` | `DeactivationResult` |

Todas devuelven `CompletableFuture`. Los rechazos de negocio son resultados normales tipados; `SERVICE_UNAVAILABLE` representa fallos internos capturados. Aun así, el consumidor debe manejar completado excepcional para errores ajenos al flujo normal.

Consultar [Operaciones y resultados](operaciones.md) para contratos, estados e idempotencia.

## Ejemplo completo: conceder puntos

```java
public Optional<BigDecimal> applyPointsBoost(Player player, BigDecimal basePoints) {
    BoostRequest request = BoostRequest.of(
        player.getUniqueId(),
        BoosterTarget.NETWORK_POINTS,
        basePoints,
        "skywars",
        "skywars-01"
    );
    return boosters.calculateIfReady(request).map(BoostCalculation::finalAmount);
}
```

Un resultado vacío significa que el otorgamiento debe posponerse o reintentarse. Un resultado presente con multiplicador `1` es definitivo y puede concederse como cantidad base.

No llamar `load(...).join()` en el hilo principal. La integración de gameplay debe trabajar sobre el snapshot listo y mantener el cálculo en la ruta síncrona sin I/O.
