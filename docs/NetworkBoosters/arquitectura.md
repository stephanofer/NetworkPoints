# Arquitectura y operación

## Módulos

| Módulo | Responsabilidad |
|---|---|
| `networkboosters-api` | Contrato compilable por consumidores: servicio, requests, resultados, dominio y eventos. |
| `networkboosters-paper` | Plugin Paper: lifecycle, MySQL, Redis, cálculo, mutaciones, comandos, menús, localización y PlaceholderAPI. |

Solo los paquetes bajo `com.stephanofer.networkboosters.api` forman la API de integración. Que una clase de implementación sea `public` no la convierte en contrato soportado.

## Flujo de estado

1. `NetworkPlayerSettings` declara listo al jugador.
2. NetworkBoosters carga y reconcilia inventario, activos vencidos, cola y claims desde MySQL.
3. Publica `PlayerBoostSnapshot` en caché y emite `PlayerBoostersReadyEvent` si sigue online.
4. Las lecturas de gameplay usan la caché sin I/O.
5. Una mutación bloquea revisión/filas, valida reglas, escribe estado y auditoría en una transacción.
6. Después del commit publica el nuevo snapshot, eventos locales e invalidación Redis.
7. Otras instancias con ese jugador cargado refrescan desde MySQL y emiten eventos `REMOTE`.

## Persistencia

MySQL es la fuente de verdad. Las migraciones crean metadata, inventario, activaciones, colas, claims, transferencias, revisiones, auditoría y recibos de idempotencia.

Las operaciones usan transacciones `READ_COMMITTED`, locks por jugador y reintentos para fallos MySQL transitorios. Las cantidades y revisiones se protegen contra overflow de Java `long`.

No leer ni escribir las tablas desde plugins consumidores. Hacerlo omite invariantes, revisiones, auditoría, caché, eventos e invalidaciones.

## Caché y revisiones

Existe un snapshot inmutable por jugador listo. Las cargas concurrentes se coalescen. Una publicación solo reemplaza estado si su revisión es mayor; completar una carga después de `PlayerQuitEvent` no repuebla la caché de esa sesión.

`getCachedOrEmpty()` es una conveniencia neutral, no una consulta durable. Los métodos de cálculo nunca cargan estado. `calculateIfReady(...)` adquiere el snapshot una sola vez y devuelve vacío si no está disponible, evitando separar readiness y cálculo.

## Redis y modo degradado

Redis transporta invalidaciones compactas; no transporta el snapshot autoritativo. Si Redis está deshabilitado o degradado:

- las mutaciones MySQL continúan disponibles;
- la instancia local se actualiza inmediatamente;
- la reconciliación periódica compara revisiones para reparar otras instancias;
- la convergencia remota puede tardar hasta el intervalo configurado.

## Temporalidad

El tiempo durable de mutaciones se obtiene de MySQL (`CURRENT_TIMESTAMP(3)`), evitando diferencias de reloj entre servidores. El cálculo local y presentación usan reloj UTC de proceso sobre snapshots ya persistidos.

Una cola no consume tiempo en paralelo: cada entrada comienza al finalizar la anterior. Al detectar tiempo vencido, el sistema marca expiraciones, descarta entradas cuyo intervalo ya pasó y promueve la entrada vigente correspondiente.

## Capacidad y claims

La capacidad cuenta unidades totales del mapa de inventario. Los activos y claims pendientes no ocupan esa capacidad. Compras y compensaciones que no caben pueden convertirse automáticamente en claims; otras fuentes se rechazan salvo creación explícita de claim.

Los permisos de capacidad y transferencia dependen del jugador Bukkit online. Para automatizaciones offline, diseñar el flujo alrededor de claims en lugar de forzar una carga artificial.

## Lifecycle

Orden de arranque relevante:

1. comandos bootstrap y configuración;
2. `PlayerSettingsService`;
3. MySQL, migraciones y probe transaccional;
4. caché, mutaciones y registro de `NetworkBoostersService`;
5. Redis/reconciliación;
6. zMenu;
7. PlaceholderAPI;
8. carga de jugadores ya online.

Al apagar, el servicio se desregistra antes de cerrar coordinadores, caché, Redis y base de datos. Las operaciones iniciadas durante el cierre pueden devolver `SERVICE_UNAVAILABLE`.

## Dependencias

- `NetworkPlayerSettings` y `zMenu` son dependencias Paper requeridas y comparten classpath.
- PlaceholderAPI es opcional.
- CraftKit Database/Redis/zMenu, Flyway, Hikari, drivers y demás infraestructura están sombreados y reubicados dentro del JAR Paper.
- Los consumidores no deben declarar ni usar esas dependencias para interactuar con NetworkBoosters.

## Fallos esperables

| Situación | Comportamiento |
|---|---|
| Jugador aún no cargado | Estado tipado `PLAYER_NOT_READY`; `calculateIfReady(...)` vacío y `calculate(...)` neutral. |
| MySQL falla al iniciar | El plugin se deshabilita. |
| MySQL falla durante mutación | `SERVICE_UNAVAILABLE`; se registra causa. |
| Redis falla | Operación local continúa; reconciliación repara convergencia. |
| Listener lanza excepción | Se registra; no revierte commit ni detiene otros cambios del dispatcher. |
| Reload inválido | Se conserva configuración anterior. |
| Definición eliminada | Estado persistido permanece; nuevas operaciones que la requieren fallan. |

## Prácticas prohibidas

- Sombrear el API en el consumidor.
- Instanciar clases de `networkboosters-paper`.
- Bloquear el hilo principal con `join()`/`get()` de futures.
- Tratar un snapshot vacío sintético como estado cargado.
- Recalcular boosters a mano o aplicar multiplicadores con `double`.
- Modificar MySQL/Redis directamente.
- Asumir que todos los eventos serán locales o que snapshots de transferencia remota siempre estarán presentes.
