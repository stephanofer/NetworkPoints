# Configuración

NetworkBoosters crea y actualiza `plugins/NetworkBoosters/config.yml`, prepara `boosters/`, `messages/`, inventarios y patterns. Las claves duplicadas se rechazan.

## `config.yml`

La versión soportada es `config-version: 3`.

| Ruta | Tipo | Regla / efecto |
|---|---|---|
| `server.id` | string | ID único, minúsculas, hasta 64; admite `.`, `_`, `-`. |
| `server.game-id` | string | Modalidad de esta instancia, mismo formato. |
| `storage.host` | string | Host MySQL, obligatorio. |
| `storage.port` | int | `1..65535`; default `3306`. |
| `storage.database` | string | Base MySQL. |
| `storage.username` | string | Usuario no vacío. |
| `storage.password` | string | Puede estar vacío. |
| `storage.table-prefix` | string | Prefijo; puede estar vacío. |
| `storage.pool.maximum-size` | int | Positivo; default `10`. |
| `storage.pool.minimum-idle` | int | `0..maximum-size`; default `2`. |
| `storage.pool.connection-timeout` | duración | Positiva; default `10s`. |
| `storage.pool.validation-timeout` | duración | Positiva; default `5s`. |
| `storage.pool.shutdown-timeout` | duración | Positiva; default `10s`. |
| `redis.enabled` | boolean | Default `true`; si es false usa reconciliación MySQL sin invalidación inmediata. |
| `redis.host`, `port`, `database` | conexión | Puerto válido, DB no negativa. |
| `redis.username`, `password` | string | Pueden estar vacíos. |
| `redis.ssl`, `verify-peer` | boolean | Defaults `false` / `true`. |
| `redis.environment`, `key-prefix` | string | Namespacing no vacío. |
| `redis.command-timeout`, `connect-timeout`, `shutdown-timeout` | duración | Positivas. |
| `redis.auto-reconnect` | boolean | Default `true`. |
| `redis.reconciliation-interval` | duración | Positiva; default `30s`. |
| `redis.degraded-reconciliation-interval` | duración | Positiva, no mayor a la normal; default `5s`. |
| `limits.maximum-multiplier` | decimal | Mínimo `1`; cap del cálculo y máximo de definiciones. |
| `activation.maximum-total-duration` | duración | Positiva; límite por línea temporal de grupo. |
| `activation.maximum-queued-entries` | int | No negativo. |
| `activation.expiry-check-interval` | duración | Positiva; default `1s`. |
| `activation.expiry-batch-size` | int | Positivo; default `100`. |
| `activation.expiry-warnings` | lista | Duraciones positivas, únicas y menores al máximo total. |
| `inventory-limits.fallback` | long | Capacidad total no negativa. |
| `inventory-limits.tiers.*` | sección | `permission`, `maximum >= 0`, `priority`. |
| `scope-display.games.*` | string | Nombre visible requerido para cada game ID explícito usado por un booster. |
| `localization.fallback-language` | enum | `es` o `en`. |
| `localization.console-language` | enum | `es` o `en`. |
| `commands.root` | ID | Comando raíz. |
| `commands.aliases` | lista | IDs únicos, distintos del root. |
| `placeholderapi.enabled` | boolean | Default `true`. |

La capacidad se mide en unidades totales, no en tipos distintos. Si un jugador cumple varias reglas de tier, se elige la de mayor prioridad; el resolvedor usa reglas deterministas. Un grant forzado puede superar la capacidad.

## Duraciones

Formato: entero sin espacios seguido por `ms`, `s`, `m`, `h` o `d`. Ejemplos: `500ms`, `30s`, `2h`, `7d`. No se admiten formatos compuestos como `1h30m`.

El cooldown de transferencia admite cero (`0s`); el resto de duraciones documentadas como positivas no.

## Definiciones de boosters

Se cargan únicamente archivos `.yml` o `.yaml` directamente dentro de `boosters/`; los subdirectorios se ignoran. `config-version` soportada: `1`.

```yaml
config-version: 1

id: personal_points_x2
enabled: true
target: network_progression:points
multiplier: 2.0
duration: 2h

scope:
  type: PERSONAL
  games: ["*"]
  servers: ["*"]

activation:
  group: personal-points
  conflict-policy: QUEUE
  requirements:
    permissions: []
    mode: ALL

transfer:
  enabled: true
  minimum-amount: 1
  maximum-amount: 5
  cooldown: 30s
  permission: ""

display:
  order: 100
  category: points
  material: TRIAL_KEY
  locked-material: OMINOUS_TRIAL_KEY
  active-material: HEAVY_CORE
  custom-model-data: 0
  glow: false
```

Restricciones adicionales:

- IDs duplicados entre archivos son error.
- `multiplier` admite hasta 6 decimales, debe ser `>= 1` y no superar el máximo global.
- `duration` no puede superar `activation.maximum-total-duration`.
- En 1.0.0 `scope.servers` debe ser exactamente `["*"]`.
- `ANY` requiere al menos un permiso; sección de requirements ausente equivale a ninguno.
- Transferencia y sus límites son obligatorios aunque esté deshabilitada.
- Materiales usan IDs mayúsculos con `_`; los tres tienen defaults si se omiten.
- `custom-model-data <= 0` significa ausente.
- Un target distinto de `network_progression:points` genera warning, no error.

## Reload

`/boosters admin reload` prepara la configuración fuera del hilo principal y solo publica un candidato completo y válido. Ante error, mantiene el estado anterior.

Requieren reinicio y hacen rechazar el reload:

- `server.id`
- `server.game-id`
- cualquier valor de `storage`
- cualquier valor de `redis`
- `commands`
- `placeholderapi.enabled`

Límites, activación, capacidad, displays, localización y definiciones pueden recargarse. Una definición removida no borra inventario ni altera los snapshots persistidos de activos/colas existentes. Una activación iniciada mientras cambia su definición puede devolver `DEFINITION_CHANGED`.

## Dependencias de runtime

- Requeridas: `NetworkPlayerSettings`, `zMenu`, MySQL.
- Opcional: `PlaceholderAPI`.
- Redis puede deshabilitarse o iniciar degradado; MySQL sigue siendo la fuente de verdad.

El plugin migra y verifica MySQL al iniciar. Un fallo de configuración, dependencia requerida o base de datos impide habilitar NetworkBoosters.
