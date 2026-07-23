# Superficies operativas

Estas capacidades pertenecen al plugin Paper. Ayudan a operar y diagnosticar NetworkPoints, pero no sustituyen su API Java.

## PlaceholderAPI

La expansión se registra como `networkpoints` cuando `placeholder-api.enabled` es `true` y PlaceholderAPI está habilitado.

| Placeholder | Resultado |
|---|---|
| `%networkpoints_balance%` | Saldo con modo predeterminado. |
| `%networkpoints_balance_raw%` | Saldo `RAW`. |
| `%networkpoints_balance_grouped%` | Saldo `GROUPED`. |
| `%networkpoints_balance_compact%` | Saldo `COMPACT`. |
| `%networkpoints_balance_display%` | Componente formateado serializado como MiniMessage. |
| `%networkpoints_currency_name%` | Nombre singular solo para saldo exactamente `1.00`; plural en otro caso. |
| `%networkpoints_currency_symbol%` | Símbolo, incluso sin jugador asociado. |

Los placeholders leen exclusivamente `cachedBalance`: no bloquean ni hacen I/O. Sin UUID o sin snapshot listo devuelven `placeholder-api.unavailable-value`. Con UUID y snapshot disponible, un parámetro desconocido devuelve `null` a PlaceholderAPI.

Para lógica Java preferí `NetworkPointsService`; no evalúes placeholders como fuente económica.

## Comandos predeterminados

El root es `/points`, con aliases `/point` y `/pts`.

| Forma | Permiso predeterminado | Función |
|---|---|---|
| `/points` | `networkpoints.balance` | Saldo propio. |
| `/points balance [player]` | `networkpoints.balance.others` | Saldo propio o de otro jugador. |
| `/points pay <player> <amount>` | `networkpoints.pay` | Pago entre jugadores. |
| `/points give <player> <amount>` | `networkpoints.admin.give` | Crédito directo. |
| `/points take <player> <amount>` | `networkpoints.admin.take` | Débito directo. |
| `/points set <player> <amount>` | `networkpoints.admin.set` | Asignación absoluta. |
| `/points reset <player>` | `networkpoints.admin.reset` | Asigna cero. |
| `/points history <player> [page]` | `networkpoints.admin.history` | Auditoría reciente. |
| `/points testaward <player> <amount> [game]` | `networkpoints.admin.test-award` | Diagnóstico del cálculo de boosters. |
| `/points reload` | `networkpoints.admin.reload` | Recarga segura. |
| `/points status` | `networkpoints.admin.status` | Estado del lifecycle, Redis y servidor lógico. |

Nombres, aliases, permisos, descripciones y habilitación se definen en `commands.yml` y requieren reinicio para cambiar. Si el root está deshabilitado no se registra ningún subcomando.

## Pagos incorporados

`/points pay`:

- solo puede ejecutarlo un jugador con ajustes/localización listos;
- aplica mínimo, máximo, cooldown y política de receptores offline;
- rechaza auto-pagos;
- abre un Dialog Paper desde el umbral configurado;
- consume una sesión de confirmación una sola vez y revalida permiso, cuenta y política al confirmar;
- delega la corrección final de fondos a `transfer`, dentro de MySQL;
- entrega feedback al receptor local o remoto mediante Redis de forma best-effort.

Estas políticas de UX NO se aplican a `NetworkPointsService.transfer`. Un plugin consumidor debe implementar sus propios permisos, cooldowns, confirmaciones y límites de producto antes de llamar a la API. La API sí garantiza cuentas distintas, cantidad positiva, fondos, máximo de saldo, atomicidad e idempotencia.

## Cuentas y jugadores offline

NetworkPoints crea la cuenta al precargar el ingreso de un jugador y conserva nombre conocido para resolución administrativa. La API pública opera por UUID y no crea cuentas arbitrarias. Mutar un UUID que nunca tuvo cuenta produce `ACCOUNT_NOT_FOUND`; leerlo mediante `balance` completa excepcionalmente.

## Integraciones

| Integración | Requerida | Efecto |
|---|---|---|
| NetworkPlayerSettings | Sí | Localización, estilos y readiness de feedback. |
| LuckPerms | Sí | Prefijo de identidad. |
| NetworkBoosters | Configurable | Multiplicador de `award`. |
| PlaceholderAPI | Configurable | Expansión `%networkpoints_*%`. |

Si NetworkBoosters está configurado como habilitado pero su servicio no existe, NetworkPoints no inicia. Si está deshabilitado, los premios son neutrales con multiplicador 1.

## Configuración y reload

Cambios que requieren reinicio:

- `server-id`;
- `database`;
- `redis`;
- `commands`;
- `integrations`;
- `currency.maximum-balance`.

El resto de `currency`, `amount-input`, `amount-format`, `payments`, `cache`, `audit`, `identity`, `placeholder-api.unavailable-value` y mensajes se publica mediante `/points reload` si toda la configuración es válida. Un reload inválido conserva el snapshot anterior. Las sesiones de confirmación abiertas se cierran al aplicar un reload.

Aunque la sección `cache` es recargable en el snapshot, la instancia Caffeine se construye al arranque; sus límites y tiempos nuevos no reconstruyen esa instancia en la versión 1.0. Los consumidores no deben depender de esos valores internos.
