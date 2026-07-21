# Comandos y PlaceholderAPI

Estas superficies son útiles para administradores y para plugins que consumen placeholders sin integrar Java.

## Comandos

Root por defecto: `/boosters`. Alias: `/booster`, `/boosts`. Son configurables, pero cambiarlos requiere reinicio.

| Comando | Permiso |
|---|---|
| `/boosters`, `help`, `menu` | `networkboosters.command.open` |
| `/boosters list [page]` | `networkboosters.command.list` |
| `/boosters active` | `networkboosters.command.list` |
| `/boosters queue [group]` | `networkboosters.command.list` |
| `/boosters claims [page]` | `networkboosters.command.claims` |
| `/boosters claim <uuid|all>` | `networkboosters.command.claims` |
| `/boosters activate <booster>` | `networkboosters.command.activate` |
| `/boosters transfer <player> <booster> [amount]` | `networkboosters.command.transfer` |
| `/boosters admin give <player> <booster> [amount]` | `networkboosters.admin.give` |
| `/boosters admin give <player> <booster> <amount> --force` | `networkboosters.admin.give.force` |
| `/boosters admin take <player> <booster> [amount]` | `networkboosters.admin.take` |
| `/boosters admin set <player> <booster> <amount> [--force]` | `networkboosters.admin.set`; force exige `networkboosters.admin.give.force` |
| `/boosters admin claim <player> <booster> [amount]` | `networkboosters.admin.claim` |
| `/boosters admin activate <player> <booster>` | `networkboosters.admin.activate` |
| `/boosters admin deactivate <player> <activation|all>` | `networkboosters.admin.deactivate` |
| `/boosters admin inspect <player>` | `networkboosters.admin.inspect` |
| `/boosters admin reload` | `networkboosters.admin.reload` |

Los comandos de jugador requieren snapshot ready. Los targets administrativos se resuelven entre jugadores online. El amount omitido en transferencia usa el mínimo de su política; en give/take/claim usa `1`.

## PlaceholderAPI

Identificador: `networkboosters`. La expansión se registra solo si `placeholderapi.enabled: true` y PlaceholderAPI está instalado. Es persistente durante reload de PlaceholderAPI.

| Placeholder | Salida |
|---|---|
| `%networkboosters_ready%` | `true` / `false`. |
| `%networkboosters_capacity%` | `usado/máximo`; offline listo devuelve `usado/0`. |
| `%networkboosters_owned_total%` | Total de unidades. |
| `%networkboosters_owned_<booster-id>%` | Cantidad del booster. |
| `%networkboosters_active_count%` | Activos vigentes. |
| `%networkboosters_active_ids%` | IDs activos separados por coma. |
| `%networkboosters_active_<target>%` | Si existe activo para target. |
| `%networkboosters_multiplier_<target>%` | Multiplicador efectivo en game/server actual. |
| `%networkboosters_time_remaining_<booster-id>%` | Tiempo localizado; vacío si no está activo. |
| `%networkboosters_seconds_remaining_<booster-id>%` | Segundos restantes o `0`. |
| `%networkboosters_queue_size_<group>%` | Entradas en cola del grupo. |
| `%networkboosters_claims_count%` | Claims pendientes. |

Para codificar un target namespaced en un placeholder, reemplazar `:` por `__`:

```text
%networkboosters_multiplier_network_points__points%
%networkboosters_active_network_points__points%
```

Antes del readiness se devuelven valores neutrales:

- multiplier: `1`
- booleano active: `false`
- tiempo y `active_ids`: string vacío
- capacity: `0/0`
- contadores: `0`

Parámetros desconocidos devuelven `null` a PlaceholderAPI. Un `OfflinePlayer` nulo, sin UUID o parámetro vacío devuelve string vacío.

## Cuándo preferir Java

Usar placeholders para presentación. Para cálculo de economía, decisiones de gameplay, mutaciones o sincronización, usar `NetworkBoostersService`: conserva tipos, `BigDecimal`, estados de error y revisiones.
