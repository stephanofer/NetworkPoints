# NetworkPoints: diseño final del producto

| Campo | Valor |
| --- | --- |
| Estado | Aprobado |
| Versión del diseño | 1.0 |
| Fecha | 21 de julio de 2026 |
| Módulos | `networkpoints-api`, `networkpoints-paper` |
| Plataforma | Paper 26.1 |

NetworkPoints será la economía global de puntos de HERA Network. MySQL será la fuente autoritativa de saldos y transacciones; Caffeine acelerará exclusivamente las lecturas; Redis sincronizará servidores sin participar en la corrección de las mutaciones; y la API pública ofrecerá operaciones asíncronas, atómicas, idempotentes y auditables.

El proyecto administrará únicamente puntos. No incluirá leaderboards, rewards, tiendas, conversión de monedas ni otros sistemas de progreso.

## 1. Decisión arquitectónica

```text
MySQL
  Fuente autoritativa de saldos, transferencias e historial.

Caffeine
  Caché local versionada para lecturas rápidas, placeholders y UI.
  Nunca decide si una compra o un pago puede realizarse.

Redis Pub/Sub
  Invalida cachés y entrega notificaciones rápidas entre servidores.
  Nunca es la fuente del saldo ni transporta operaciones críticas.

NetworkPoints API
  Mutaciones asíncronas, atómicas, idempotentes y con resultados tipados.

Paper
  Comandos, eventos posteriores al commit, dialogs y feedback al jugador.
```

Toda mutación seguirá este flujo:

1. Validar la solicitud sin I/O.
2. Ejecutar una transacción MySQL en el executor de CraftKit.
3. Bloquear únicamente las cuentas involucradas.
4. Revalidar saldo y límites dentro de la transacción.
5. Actualizar saldo y revisión.
6. Escribir el registro de auditoría en la misma transacción.
7. Confirmar el commit.
8. Actualizar Caffeine únicamente después del commit.
9. Publicar la invalidación mediante Redis.
10. Disparar eventos Paper y feedback.

Si MySQL falla, el saldo no cambia, la caché no se modifica y no se comunica éxito.

## 2. Decisiones tomadas a partir de PlayerPoints

PlayerPoints actualiza la base de datos cada 10 ticks, aproximadamente cada `0.5 s`, mediante una cola local. También utiliza una caché local con refresh configurable, operaciones síncronas contra esa caché, mensajes de plugin para refrescar otros servidores, una cola de transacciones pendientes, un registro opcional de transacciones, cooldown de pagos y caché de nombres offline.

NetworkPoints conservará las ideas que mejoran la latencia y la experiencia, pero no su estrategia de consistencia.

| Decisión de PlayerPoints | Decisión para NetworkPoints |
| --- | --- |
| Caché local para lecturas | Se adopta |
| Precarga durante la conexión | Se adopta |
| Cooldown de pagos | Se adopta |
| Caché de nombres conocidos | Se adopta |
| Historial técnico | Se adopta y mejora |
| Invalidación entre servidores | Se adopta mediante Redis |
| API de consumo sencilla | Se adopta con mutaciones asíncronas |
| Escritura diferida | Se rechaza |
| Validación de fondos desde caché | Se rechaza |
| Transferencia como dos cambios separados | Se rechaza |
| API síncrona potencialmente bloqueante | Se rechaza |
| Plugin Messaging como sincronización | Se rechaza |
| `int` para saldos | Se rechaza |

La escritura diferida de PlayerPoints puede perder operaciones si el proceso cae antes del flush. Un pago tampoco constituye una única transacción SQL: primero descuenta, después acredita y, si falla, encola un reembolso.

NetworkPoints evitará específicamente:

- Dos débitos concurrentes aceptados desde servidores distintos.
- Desbordamientos numéricos silenciosos.
- Lecturas bloqueantes cuando la caché no contiene al jugador.
- Sincronización dependiente de un jugador conectado como transporte.
- Historial y saldo desalineados.
- Inconsistencia entre débito, crédito y reembolso.
- Pérdida de saldo por una caída antes del flush.

## 3. Alcance funcional

NetworkPoints incluirá:

- Una sola economía global: points.
- Consulta del saldo propio y de otros jugadores.
- Pagos entre jugadores.
- Administración de saldos.
- API pública para modalidades, tiendas y plugins internos.
- Integración preparada con NetworkBoosters.
- Eventos Paper posteriores al commit.
- Feedback compuesto y localizado.
- Confirmación nativa mediante Paper Dialog API.
- Formato de cantidades y atajos numéricos.
- Placeholders.
- Historial administrativo.
- Sincronización entre servidores.
- Soporte para jugadores offline conocidos.
- Identidad visual integrada con LuckPerms y NetworkPlayerSettings.

NetworkPoints no incluirá:

- Leaderboards.
- Rewards ni definición de recompensas.
- Tiendas.
- Gestión de boosters.
- Conversión de monedas.
- Cuentas no asociadas a jugadores.
- Integración con Votifier.
- Sistemas de progreso adicionales.
- Vault en la primera versión.
- Treasury en la primera versión.
- Escritura diferida de saldos.
- UUID visibles al usuario.

Vault no se incluirá porque obliga a presentar una API económica síncrona. Esto requeriría bloquear el hilo principal o responder antes de confirmar la persistencia. Las integraciones HERA utilizarán la API asíncrona nativa de NetworkPoints.

## 4. Modelo monetario

### 4.1 Representación

```text
Java: BigDecimal
MySQL: DECIMAL(30, 2)
Escala: 2 decimales
Rango: desde 0.00 hasta el máximo configurado
```

El modelo soportará correctamente cantidades como:

```text
0.10
1.50K
12.5M
999,999,999,999,999.00
```

Dos decimales permiten boosters y pagos fraccionarios sin introducir errores binarios de precisión. No se utilizarán `double`, `float`, `int` ni multiplicaciones que puedan desbordarse silenciosamente.

La escala no será configurable. Cambiarla después de persistir datos sería una migración del modelo monetario, no una preferencia visual.

### 4.2 Invariantes

- Los saldos nunca pueden ser negativos.
- El input con más de dos decimales se rechaza.
- Las cantidades de comandos deben ser positivas, excepto `set`, que admite cero.
- No se acepta notación científica como `1e9`.
- No se aceptan comas en el input porque `1,000` es ambiguo entre idiomas.
- El separador decimal de entrada siempre será `.`.
- El valor expandido de un sufijo se valida contra `minimum-amount`, `maximum-amount` y `maximum-balance`.
- Los boosters se calculan con `BigDecimal`.
- El resultado de boosters se redondea una sola vez al final.
- El modo de redondeo predeterminado será `HALF_UP`.

### 4.3 Atajos numéricos

La configuración utilizará un nombre propio del dominio económico, no `number-format-sell-multiplication`:

```yaml
amount-input:
  suffixes:
    k: 1000
    m: 1000000
    b: 1000000000
    t: 1000000000000
    q: 1000000000000000
```

Los sufijos no distinguirán mayúsculas de minúsculas:

```text
10k  -> 10,000
1.5K -> 1,500
0.1k -> 100
2M   -> 2,000,000
```

No se incluirán por defecto sufijos hasta `10^42`. Se podrán agregar multiplicadores adicionales mientras estén dentro del dominio monetario soportado.

## 5. Formato de cantidades

Los modos públicos serán:

```text
RAW
GROUPED
COMPACT
```

| Modo | Resultado para 12500 |
| --- | --- |
| `RAW` | `12500` |
| `GROUPED` | `12,500` |
| `COMPACT` | `12.5K` |

Configuración propuesta:

```yaml
currency:
  display-name:
    singular: "Point"
    plural: "Points"
  symbol: "✦"
  display-format: "<amount> <symbol>"
  maximum-balance: 999999999999999.00

amount-format:
  default-mode: COMPACT
  grouped-pattern: "#,##0.##"
  grouping-separator: ","
  decimal-separator: "."

  compact:
    - threshold: 1000
      pattern: "0.#"
      suffix: "K"
      display: "<#a3d14d><amount>"

    - threshold: 1000000
      pattern: "0.#"
      suffix: "M"
      display: "<#ebbc23><amount>"

    - threshold: 1000000000
      pattern: "0.#"
      suffix: "B"
      display: "<#eb4b23><amount>"

    - threshold: 1000000000000
      pattern: "0.##"
      suffix: "T"
      display: "<#ff9999><amount>"

    - threshold: 1000000000000000
      pattern: "0.##"
      suffix: "Q"
      display: "<#ff3535><amount>"
```

Cada reducción declarará la cantidad mínima desde la que aplica. Este enfoque es más claro que usar `maxAmount` y evita rangos solapados.

Se corregirán las fronteras de redondeo:

```text
999,949 -> 999.9K
999,999 -> 1.0M
```

No se mostrará `1000.0K`.

Cuando se use formato compacto, los componentes de chat podrán incluir un hover con la cantidad exacta. Los dialogs de confirmación siempre mostrarán ambas representaciones:

```text
12.5M ✦
12,500,000.00 Points
```

## 6. Persistencia y concurrencia

### 6.1 Tabla de cuentas

Tabla: `networkpoints_accounts`.

```text
player_uuid          BINARY(16) PRIMARY KEY
last_known_name      VARCHAR(16)
normalized_name      VARCHAR(16) UNIQUE
balance              DECIMAL(30,2) NOT NULL
revision             BIGINT UNSIGNED NOT NULL
created_at           TIMESTAMP(3)
updated_at           TIMESTAMP(3)
```

`last_known_name` será el nombre visible conocido más reciente. `normalized_name` permitirá búsquedas exactas case-insensitive sin cargar todos los jugadores en memoria.

### 6.2 Tabla de transacciones

Tabla: `networkpoints_transactions`.

```text
id                    BIGINT UNSIGNED AUTO_INCREMENT
operation_id          BINARY(16)
entry_index           TINYINT UNSIGNED
account_uuid          BINARY(16)
counterparty_uuid     BINARY(16) NULL
transaction_type      VARCHAR(32)
delta                 DECIMAL(30,2)
base_amount           DECIMAL(30,2) NULL
multiplier            DECIMAL(20,8) NULL
balance_before        DECIMAL(30,2)
balance_after         DECIMAL(30,2)
actor_uuid            BINARY(16) NULL
source                 VARCHAR(128)
source_reference       VARCHAR(255) NULL
source_server_id       VARCHAR(64)
created_at             TIMESTAMP(3)
```

Índices:

```text
UNIQUE (operation_id, entry_index)
INDEX (account_uuid, created_at)
INDEX (operation_id)
INDEX (created_at)
```

Una transferencia generará dos entradas con el mismo `operation_id`: una para el débito y otra para el crédito. Ambas se insertarán junto con los dos cambios de saldo en una sola transacción.

### 6.3 Registro de operaciones idempotentes

Tabla técnica: `networkpoints_operations`.

El historial y la idempotencia tienen ciclos de vida diferentes. El historial se elimina según su retención, pero una operación confirmada no puede volver a ser ejecutable después de esa limpieza. Por eso, cada mutación confirmada persistirá en la misma transacción un registro técnico compacto con:

- `operation_id` como clave primaria global.
- Identidad completa de la solicitud, incluidos `gameId` y `serverId` para `award`.
- Snapshots y desglose necesarios para reconstruir el resultado confirmado.
- Sin retención automática en la primera versión.
- Los boosters aplicados por un `award` se normalizan en `networkpoints_operation_boosters`, ordenados por operación, para conservar activation ID, booster ID, grupo y multiplicador sin serializaciones opacas.

Esta tabla no es historial social ni una tercera economía. No participa en consultas de jugadores ni en el hot path de lectura; existe únicamente para conservar la garantía de idempotencia durante toda la vida del dato.

### 6.4 Atomicidad

Las transferencias bloquearán ambas cuentas mediante `SELECT ... FOR UPDATE`, ordenadas siempre por UUID binario para reducir deadlocks.

CraftKit Database proporcionará:

- Transacciones asíncronas.
- Una conexión por transacción.
- Commit y rollback.
- Isolation `READ_COMMITTED`.
- Retry opt-in para deadlocks y lock timeouts.
- Backoff fuera del executor de base de datos.

Se utilizará `TransactionRetryPolicy.mysqlTransient()`. El callback SQL no ejecutará feedback, eventos, Redis ni ningún otro side effect externo porque puede ejecutarse más de una vez.

### 6.5 Idempotencia

Cada mutación pública tendrá un `operationId`.

Si una modalidad procesa dos veces el mismo evento o pierde la respuesta de un commit, podrá repetir la solicitud con el mismo ID. NetworkPoints devolverá el resultado persistido en lugar de duplicar la operación.

Si el mismo ID llega con información diferente, responderá `IDEMPOTENCY_CONFLICT`.

## 7. Historial técnico

El historial será una capacidad de auditoría, no una feature social.

- Se escribe en la misma transacción que el saldo.
- Solo registra operaciones confirmadas.
- Permite diagnosticar pérdidas, duplicaciones y reclamos.
- No participa en el hot path de lecturas.
- Tiene retención configurable.
- Se elimina por lotes pequeños para evitar bloqueos extensos.

```yaml
audit:
  retention-days: 90
  cleanup-interval-hours: 24
  cleanup-batch-size: 1000
  command-page-size: 10
  maximum-command-page: 100
```

Los administradores podrán consultar operaciones recientes mediante:

```text
/points history <jugador> [página]
```

Las consultas operativas avanzadas podrán realizarse directamente en MySQL.

## 8. Caché local

Configuración inicial:

```yaml
cache:
  maximum-size: 100000
  refresh-after-write-seconds: 30
  expire-after-access-minutes: 10
```

Cada entrada será un snapshot versionado:

```java
BalanceSnapshot(
    UUID playerId,
    BigDecimal balance,
    long revision
)
```

La revisión impedirá que una carga vieja sobrescriba una mutación más reciente.

Caffeine se utilizará para:

- `/points`.
- Placeholders.
- UI.
- Lecturas frecuentes.
- Precarga del jugador.
- Deduplicación de cargas simultáneas.

Caffeine no se utilizará para:

- Verificar fondos.
- Ejecutar pagos.
- Decidir compras.
- Persistir cambios posteriormente.
- Mantener una cola write-behind.

## 9. Sincronización Redis

Redis se iniciará con `RedisStartupMode.RECOVER`.

Después de un commit se publicará un evento versionado con:

```text
operationId
sourceServerId
account UUID
revision
transaction type
notification data mínima
```

El servidor receptor:

1. Ignora eventos originados por sí mismo.
2. Descarta revisiones antiguas.
3. Solo refresca si la cuenta está actualmente cacheada.
4. Carga el estado autoritativo desde MySQL.
5. Publica el snapshot únicamente si es más nuevo.

Si Redis no está disponible:

- Los pagos siguen siendo correctos.
- Las compras siguen siendo correctas.
- MySQL continúa siendo autoritativo.
- La caché puede tardar hasta el refresh configurado en reflejar cambios externos.
- Una notificación remota puede perderse porque Pub/Sub no es durable.
- El saldo no se pierde.

No se utilizarán leases Redis. Los row locks de MySQL coordinan las mutaciones.

## 10. API pública

La API estará en `networkpoints-api` y se obtendrá mediante Bukkit `ServicesManager`:

```java
NetworkPointsService points = Bukkit.getServicesManager()
    .load(NetworkPointsService.class);
```

Contrato principal:

```java
public interface NetworkPointsService {

    Optional<BalanceSnapshot> cachedBalance(UUID playerId);

    CompletableFuture<BalanceSnapshot> balance(UUID playerId);

    CompletableFuture<BalanceSnapshot> refreshBalance(UUID playerId);

    CompletableFuture<MutationResult> award(AwardRequest request);

    CompletableFuture<MutationResult> credit(CreditRequest request);

    CompletableFuture<MutationResult> debit(DebitRequest request);

    CompletableFuture<TransferResult> transfer(TransferRequest request);

    CompletableFuture<MutationResult> setBalance(SetBalanceRequest request);

    Component formatAmount(BigDecimal amount);

    String formatAmountPlain(BigDecimal amount, AmountDisplayMode mode);

    AmountParseResult parseAmount(String input);
}
```

### 10.1 Semántica de operaciones

| Operación | Aplica boosters |
| --- | --- |
| `award` | Sí |
| `credit` | No |
| `debit` | No |
| `transfer` | No |
| `setBalance` | No |

Esta separación evita que comandos administrativos, reembolsos o transferencias sean multiplicados accidentalmente.

### 10.2 Contexto de mutación

Cada solicitud incluirá:

```java
MutationContext(
    UUID operationId,
    Key source,
    Optional<UUID> actorId,
    Optional<String> sourceReference
)
```

Ejemplo conceptual:

```java
AwardRequest request = new AwardRequest(
    playerId,
    new BigDecimal("100"),
    "skywars",
    "skywars-1",
    MutationContext.create(
        operationId,
        Key.key("hera_skywars:match_win"),
        playerId,
        matchId
    )
);

points.award(request).thenAccept(result -> {
    if (result.success()) {
        // The operation is already committed in MySQL.
    }
});
```

### 10.3 Resultados tipados

No se devolverán simples valores booleanos.

Estados principales:

```text
SUCCESS
INSUFFICIENT_FUNDS
BALANCE_LIMIT_EXCEEDED
INVALID_AMOUNT
ACCOUNT_NOT_FOUND
BOOSTER_STATE_NOT_READY
IDEMPOTENCY_CONFLICT
SERVICE_UNAVAILABLE
```

Un resultado exitoso incluirá:

- Operation ID.
- Saldo anterior.
- Saldo nuevo.
- Delta aplicado.
- Cantidad base.
- Multiplicador.
- Cantidad final.
- Indicador de repetición idempotente.

Los errores de negocio se representarán mediante resultados tipados. Los fallos de infraestructura completarán el future excepcionalmente.

### 10.4 Contrato de threading

Solo `cachedBalance` y los formatters serán síncronos, thread-safe y libres de I/O.

Todas las mutaciones serán asíncronas. Los callbacks de los futures no tendrán garantía de ejecutarse en el main thread. Los consumidores deberán volver al scheduler apropiado antes de utilizar APIs Paper.

## 11. Integración con NetworkBoosters

NetworkBoosters ya define el target requerido:

```java
BoosterTarget.NETWORK_POINTS
```

El flujo de `award` será:

1. Verificar que NetworkBoosters esté disponible y listo.
2. Construir `BoostRequest`.
3. Utilizar `NETWORK_POINTS` (`network_points:points`).
4. Proporcionar `playerId`, `baseAmount`, `gameId` y `serverId`.
5. Ejecutar `calculateIfReady`, que captura el snapshot una sola vez y es síncrono, thread-safe y libre de I/O.
6. Redondear el resultado final una vez.
7. Persistir cantidad base, multiplicador, cantidad final y boosters aplicados.
8. Devolver el desglose al consumidor.

Si `calculateIfReady` devuelve vacío, el snapshot del jugador no está listo: no se concederá silenciosamente la cantidad neutral y se devolverá `BOOSTER_STATE_NOT_READY` para permitir un reintento seguro. Un resultado presente con multiplicador `1` es un cálculo neutral legítimo.

Los comandos administrativos, pagos, compensaciones y reembolsos nunca aplicarán boosters.

La integración vivirá detrás de un adaptador interno. NetworkPoints podrá funcionar sin NetworkBoosters cuando la integración esté desactivada.

## 12. Eventos Paper

Eventos públicos:

```text
PlayerPointsReadyEvent
PointsBalanceChangeEvent
PointsTransferEvent
PointsAwardEvent
```

Características:

- Se disparan después del commit.
- Son inmutables.
- Se disparan en el main thread.
- Incluyen operation ID y snapshots relevantes.
- No son cancelables.
- No reemplazan el resultado del future.

No se introducirán eventos pre-cancelables. Las reglas de negocio pertenecen al plugin que solicita la operación y al pipeline de NetworkPoints. Mezclar cancelación Paper, ejecución asíncrona, reintentos e idempotencia produciría un contrato innecesariamente frágil.

## 13. Comandos

| Comando | Permiso | Audiencia |
| --- | --- | --- |
| `/points` | `networkpoints.balance` | Jugador |
| `/points balance [jugador]` | `networkpoints.balance.others` | Jugador o administrador |
| `/points pay <jugador> <cantidad>` | `networkpoints.pay` | Jugador |
| `/points give <jugador> <cantidad>` | `networkpoints.admin.give` | Administrador o consola |
| `/points take <jugador> <cantidad>` | `networkpoints.admin.take` | Administrador o consola |
| `/points set <jugador> <cantidad>` | `networkpoints.admin.set` | Administrador o consola |
| `/points reset <jugador>` | `networkpoints.admin.reset` | Administrador o consola |
| `/points history <jugador> [página]` | `networkpoints.admin.history` | Administrador o consola |
| `/points reload` | `networkpoints.admin.reload` | Administrador o consola |
| `/points status` | `networkpoints.admin.status` | Administrador o consola |

`giveall` no se incluirá inicialmente. Su semántica en una network es ambigua y una operación masiva incorrecta puede afectar toda la economía.

Cloud Paper se utilizará desde bootstrap. `cloud-minecraft-extras` no se utilizará inicialmente porque el feedback y los errores deben pasar por el sistema localizado de NetworkPoints.

Los nombres, aliases, permisos y activación de subcomandos serán configurables durante el arranque. Los cambios estructurales requerirán reinicio porque los comandos se registran en bootstrap. Los mensajes, límites y formatos podrán recargarse.

Las sugerencias mostrarán jugadores online visibles. Los jugadores offline conocidos podrán escribirse manualmente sin cargar todos los nombres de MySQL en memoria.

## 14. Pagos

Configuración:

```yaml
payments:
  enabled: true
  allow-offline-recipients: true
  minimum-amount: 0.10
  maximum-amount: 999999999999999.00
  cooldown-millis: 500

  confirmation:
    enabled: true
    minimum-amount: 10000000.00
    expires-after-seconds: 30
```

La confirmación se activará cuando:

```text
amount >= minimum-amount
```

Exactamente `10,000,000` requerirá confirmación.

### 14.1 Dialog de confirmación

Se utilizará directamente Paper Dialog API:

- `Dialog.create`.
- `DialogBase.builder`.
- `DialogBody.plainMessage`.
- `DialogType.confirmation`.
- `ActionButton`.
- `DialogAction.customClick(callback, options)`.
- `ClickCallback.Options.uses(1)`.
- Lifetime igual a la expiración de la sesión.
- `DialogAfterAction.CLOSE`.
- `canCloseWithEscape(true)`.
- `pause(false)`.

Contenido conceptual:

```text
Confirmar pago

Remitente:
[prefix] Nick [flag]

Destinatario:
[prefix] Nick [flag]

Monto:
12.5M ✦
12,500,000.00 Points

Verifique cuidadosamente el jugador y el monto.
```

Botones:

```text
Confirmar
Cancelar
```

Garantías:

- Una sola sesión activa por jugador.
- Una nueva solicitud reemplaza la anterior.
- Callback utilizable una sola vez.
- Token y operation ID únicos.
- Expiración automática.
- Limpieza en quit, reload y disable.
- Doble clic ignorado.
- Callback antiguo ignorado.
- Fondos revalidados al confirmar.
- Límites revalidados al confirmar.
- Target UUID capturado como identidad autoritativa.
- El cambio de nombre del destinatario no cambia la cuenta.
- Escape equivale a cancelación visual.
- El saldo no se reserva mientras el dialog está abierto.

No se utilizarán comandos como `/points confirm`.

## 15. Identidad visual de jugadores

Todo lugar visible renderizará un `Component`. Nunca se mostrará un UUID ni se reconstruirá la identidad mediante concatenación de strings.

```text
LuckPerms prefix Component
+ NetworkPlayerSettings formatted nick Component
+ CountryFlagService flag Component
= PlayerIdentity Component
```

Configuración:

```yaml
identity:
  format: "<prefix><nick><country_flag>"
  prefix-suffix: " "
  flag-prefix: " "

  luckperms-prefix-format: MINIMESSAGE
```

Se podrá soportar explícitamente `LEGACY_AMPERSAND` si la configuración de rangos lo requiere. No se utilizará autodetección heurística.

Para jugadores online se utilizará:

- LuckPerms `PlayerAdapter#getMetaData`.
- `PlayerStyleService#formattedNick(Player)`.
- `CountryFlagService#flag(UUID)`.

Para jugadores offline se utilizará:

- `LuckPerms UserManager#loadUser(UUID)` de forma asíncrona.
- Metadata con query options apropiadas.
- `PlayerStyleService#formattedNick(NickStyleRenderRequest)` de forma asíncrona.
- Permisos resueltos desde cached permission data de LuckPerms.
- `CountryFlagService#flagAsync(UUID)`.
- Nombre conocido almacenado en `networkpoints_accounts`.

La caché de identidad se invalidará cuando ocurra:

- `PlayerSettingChangeEvent`.
- `UserDataRecalculateEvent` de LuckPerms.
- Cambio de nombre al entrar.
- Quit.
- Expiración por tiempo.

Si no existe un nombre conocido, se mostrará un texto localizado como `Jugador desconocido`. Nunca se utilizará el UUID como fallback visual.

## 16. Localización y feedback

NetworkPlayerSettings será una dependencia obligatoria.

Servicios utilizados:

```text
PlayerSettingsService
PlayerStyleService
CountryFlagService
```

Se esperará `PlayerSettingsReadyEvent` o se comprobará `isReady(UUID)` antes de emitir contenido sensible al idioma.

Archivos:

```text
messages/es.yml
messages/en.yml
```

Cada mensaje será una lista de acciones:

```yaml
pay-sent:
  - type: CHAT
    message: "<green>Enviaste <amount> a <recipient>.</green>"

  - type: ACTION_BAR
    message: "<gray>Saldo restante: <balance></gray>"

  - type: SOUND
    sound: "minecraft:block.note_block.pling"
    source: "MASTER"
    volume: 0.8
    pitch: 1.2
```

Tipos soportados:

```text
CHAT
ACTION_BAR
TITLE
SOUND
BOSS_BAR
```

Ejemplo ampliado:

```yaml
award-received:
  - type: CHAT
    message: "<green>+<amount></green> <gray>por <reason></gray>"

  - type: TITLE
    title: "<green>+<amount></green>"
    subtitle: "<gray><reason></gray>"
    fade-in-millis: 150
    stay-millis: 1200
    fade-out-millis: 300

  - type: BOSS_BAR
    message: "<green>Ganaste <amount></green>"
    color: GREEN
    overlay: PROGRESS
    progress: 1.0
    duration-millis: 2500
```

Reglas:

- Una lista vacía desactiva el feedback de esa key.
- Tipos desconocidos invalidan la recarga.
- Sonidos, tiempos, progress y rangos inválidos se detectan al cargar.
- Los valores dinámicos se insertan como `Component` con `Placeholder.component`.
- Los nombres no se interpolan como MiniMessage raw.
- Un idioma faltante utiliza el idioma predeterminado.
- Una recarga inválida conserva el snapshot anterior.
- La lectura y validación de archivos durante `/points reload` se ejecuta fuera del main thread; la publicación del snapshot validado se aplica en main thread.
- Las bossbars temporales se limpian en quit y disable.
- Dialog no será una acción genérica porque requiere comportamiento y seguridad específicos.

No se incluirán partículas, comandos ejecutables ni acciones arbitrarias.

## 17. PlaceholderAPI

Expansión interna persistente:

```text
%networkpoints_balance%
%networkpoints_balance_grouped%
%networkpoints_balance_compact%
%networkpoints_balance_display%
%networkpoints_currency_symbol%
%networkpoints_currency_name%
```

Reglas:

- Nunca realizan I/O.
- Nunca llaman `.join()`.
- Solo utilizan caché.
- No incluyen leaderboards.
- No resuelven jugadores arbitrarios por nombre.
- Si la cuenta no está lista, devuelven un valor configurable como `""`.
- `balance_display` puede entregar MiniMessage para pipelines que lo interpreten después.
- Los placeholders plain no incluyen colores.

La expansión será opcional y solo se registrará si PlaceholderAPI está instalada.

## 18. Configuración

Archivos:

```text
config.yml
commands.yml
messages/es.yml
messages/en.yml
```

`config.yml` contendrá:

```text
server-id
database
redis
currency
amount-input
amount-format
payments
cache
audit
integrations
identity
placeholder-api
```

BoostedYAML administrará:

- Creación desde defaults.
- `config-version`.
- Auto-update.
- Relocaciones de keys.
- Preservación de rutas personalizadas.
- Migraciones controladas.
- Validación antes de publicar el nuevo snapshot.

Configuraciones que requieren reinicio:

```text
database
redis
server-id
command roots y aliases
activación de integraciones
```

Configuraciones recargables:

```text
mensajes
feedback
formatos
sufijos
límites de pay
cooldowns
confirmación
retención
identidad visual
```

Todas las configuraciones compartidas deberán ser iguales en la network, excepto `server-id`.

## 19. Arquitectura de módulos y paquetes

```text
NetworkPoints/
├── networkpoints-api/
│   └── src/main/java/com/stephanofer/networkpoints/api/
│       ├── NetworkPointsService.java
│       ├── amount/
│       ├── balance/
│       ├── event/
│       ├── request/
│       ├── result/
│       └── source/
│
└── networkpoints-paper/
    └── src/main/
        ├── java/com/stephanofer/networkpoints/
        │   ├── NetworkPointsBootstrap.java
        │   ├── NetworkPointsLoader.java
        │   ├── NetworkPointsPlugin.java
        │   ├── account/
        │   ├── amount/
        │   ├── award/
        │   ├── command/
        │   ├── config/
        │   ├── feedback/
        │   ├── identity/
        │   ├── lifecycle/
        │   ├── localization/
        │   ├── payment/
        │   ├── persistence/
        │   ├── placeholder/
        │   ├── service/
        │   └── synchronization/
        │
        └── resources/
            ├── config.yml
            ├── commands.yml
            ├── messages/
            │   ├── es.yml
            │   └── en.yml
            └── db/migration/
                ├── V1__create_networkpoints.sql
                └── V2__create_networkpoints_operations.sql
```

La estructura será feature-first. No se crearán carpetas genéricas como `manager`, `util`, `model` o `handler` que mezclen responsabilidades no relacionadas.

## 20. Dependencias

| Dependencia | Uso |
| --- | --- |
| Paper API | Plataforma, Dialog API, Adventure y eventos |
| CraftKit Database | MySQL, Hikari, Flyway y transacciones |
| CraftKit Redis | Pub/Sub y estado operativo |
| Caffeine | Caché local |
| BoostedYAML | Configuración y migraciones |
| Cloud Paper | Comandos Brigadier |
| NetworkPlayerSettings | Idioma, nick styles y bandera |
| LuckPerms | Prefix y permisos offline |
| NetworkBoosters API | Cálculo de awards |
| PlaceholderAPI | Expansión opcional |
| JUnit | Tests unitarios |

Adventure será provisto por Paper y no se empaquetará por separado.

`cloud-minecraft-extras` no se utilizará inicialmente. El sistema localizado de NetworkPoints cubrirá los captions y errores de comandos.

## 21. Lifecycle

### 21.1 Startup

1. Bootstrap carga la estructura de comandos.
2. El plugin carga y valida la configuración.
3. Obtiene los servicios obligatorios.
4. Abre MySQL.
5. Ejecuta Flyway.
6. Crea repositorios y servicios.
7. Inicia Redis en modo recuperable.
8. Registra `NetworkPointsService`.
9. Registra listeners y PlaceholderAPI.
10. Precarga jugadores conectados si existiera alguno.

MySQL será obligatorio y fail-fast. Redis podrá iniciar degradado.

### 21.2 Shutdown

1. Rechazar nuevas mutaciones.
2. Invalidar sesiones de confirmación.
3. Ocultar bossbars.
4. Desregistrar expansión y servicio.
5. Esperar operaciones DB en curso dentro del timeout de CraftKit.
6. Cerrar Redis.
7. Cerrar MySQL.
8. Limpiar cachés.

No existirá una cola de puntos que deba ser flusheada al apagar.

## 22. Estrategia de pruebas

Se utilizarán exclusivamente tests unitarios con JUnit y dobles escritos a mano mediante interfaces o lambdas. No se utilizarán Mockito, Testcontainers, tests de integración automatizados ni Paper smoke tests.

La suite unitaria cubrirá:

- Parsing raw y con sufijos.
- Mayúsculas y minúsculas.
- Rechazo de notación científica.
- Escala y redondeo.
- Límites sobre el valor expandido.
- Formato raw, grouped y compact.
- Promoción `999,999 -> 1.0M`.
- Colores por tier.
- Límites de saldo.
- Decisión de débito.
- Transferencia a uno mismo.
- Orden determinista de locks.
- Conflictos de idempotencia.
- Revisión de caché y carreras de loads.
- Invalidaciones Redis antiguas o duplicadas.
- Codec de sincronización.
- Expiración y reemplazo de confirmaciones.
- Doble confirmación.
- Validación completa de configuración.
- Fallback de idiomas.
- Compilación de acciones de feedback.
- Redondeo de awards.
- NetworkBoosters no listo.
- Composición de identidad Adventure.
- Limpieza de lifecycle.

Las siguientes verificaciones serán manuales:

- MySQL real.
- Redis real y caída/reconexión.
- Dos servidores Paper.
- Transferencias concurrentes.
- Compras simultáneas.
- Cambio rápido de servidor.
- Dialog localizado.
- Prefix, nick style y bandera.
- Placeholders.
- Reinicio durante operaciones.
- Redis desconectado.
- MySQL desconectado.
- Historial y limpieza.
- Limpieza del historial seguida de replay del mismo `operationId`.

## 23. Edge cases obligatorios

- Dos compras concurrentes sobre el mismo saldo.
- Pago y compra simultáneos.
- Dos comandos administrativos sobre una cuenta.
- Jugador pasando entre servidores.
- Redis perdiendo una invalidación.
- Commit confirmado cuya respuesta se pierde.
- Repetición del mismo evento de modalidad.
- Doble clic en confirmar.
- Confirmación expirada.
- Saldo cambiado mientras el dialog está abierto.
- Destinatario desconectado.
- Cambio de nombre.
- Prefix inexistente o inválido.
- Nick style sin permiso vigente.
- Bandera desactivada.
- Jugador sin país conocido.
- NetworkPlayerSettings todavía no listo.
- NetworkBoosters todavía no listo.
- Resultado de booster con fracciones.
- Sufijo cuyo resultado supera el máximo.
- Cantidad exactamente igual al umbral de confirmación.
- Recarga de configuración inválida.
- Shutdown con operaciones en curso.
- Cuenta desconocida.
- Búsquedas de nombres case-insensitive.
- Ausencia absoluta de fallbacks visuales a UUID.

## 24. Orden de implementación

1. Base Gradle, módulos, configuración tipada y lifecycle.
2. Modelo monetario, parser, formatter y tests.
3. Migraciones, repositorios y transacciones atómicas.
4. Caché versionada y sincronización Redis.
5. API pública, resultados, idempotencia y eventos.
6. Integración con NetworkBoosters.
7. Integración con NetworkPlayerSettings, LuckPerms e identidad.
8. Localización y feedback compuesto.
9. Comandos Cloud.
10. Dialog de confirmación.
11. PlaceholderAPI.
12. Historial administrativo.
13. Suite unitaria y validación manual.

## 25. Criterios de aceptación

- [ ] MySQL es la única fuente autoritativa del saldo.
- [ ] Ninguna mutación responde con éxito antes del commit.
- [ ] Una transferencia actualiza ambas cuentas atómicamente.
- [ ] Todas las mutaciones importantes son idempotentes.
- [ ] La caché nunca valida fondos.
- [ ] Redis puede fallar sin corromper la economía.
- [ ] Ninguna operación JDBC bloquea el main thread durante gameplay.
- [ ] La API pública no expone implementaciones internas.
- [ ] `award` es la única operación que aplica boosters.
- [ ] Todo contenido dirigido al jugador utiliza su idioma resuelto.
- [ ] Toda identidad visible utiliza prefix, nick style y bandera configurados.
- [ ] Nunca se presenta un UUID al usuario.
- [ ] Los pagos grandes utilizan un dialog nativo y seguro.
- [ ] Los placeholders nunca hacen I/O.
- [ ] El historial se escribe en la misma transacción que el saldo.
- [ ] La retención del historial no elimina la memoria de idempotencia.
- [ ] No existe ninguna funcionalidad de leaderboard.
- [ ] La suite automatizada contiene únicamente tests unitarios JUnit.

## 26. Resultado final

NetworkPoints tendrá dos tablas de negocio, un registro técnico de idempotencia con el desglose de boosters aplicado, una caché local versionada, transacciones MySQL pequeñas, Redis como optimización y una API explícita. La complejidad estará concentrada únicamente donde evita pérdida, duplicación o corrupción de puntos.

Este documento constituye la especificación aprobada para iniciar la implementación formal del proyecto.
