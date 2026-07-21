# NetworkBoosters 1.0.0

Documentación de integración y referencia de la primera versión estable de NetworkBoosters.

NetworkBoosters administra inventarios de boosters personales, activaciones, colas, claims, transferencias y cálculo de recompensas. Los plugins consumidores se integran exclusivamente mediante `networkboosters-api` y el servicio Paper `NetworkBoostersService`; no deben depender de clases de `networkboosters-paper` ni acceder directamente a MySQL o Redis.

## Empezar

1. [Instalación e integración](integracion.md): dependencia Gradle, `paper-plugin.yml`, obtención del servicio y ciclo de vida.
2. [Servicio público](servicio.md): los 16 métodos de `NetworkBoostersService`, caché, asincronía y cálculo.
3. [Operaciones y resultados](operaciones.md): activar, otorgar, revocar, fijar, transferir, crear/cobrar claims y desactivar.
4. [Eventos Paper](eventos.md): eventos disponibles, orden, origen y sincronización entre servidores.

## Referencia

- [Modelo de dominio](dominio.md): definiciones, snapshots, IDs, scopes, inventario, activos, colas y claims.
- [Índice completo de la API](referencia-api.md): todas las clases públicas organizadas por paquete.
- [Configuración](configuracion.md): `config.yml`, definiciones de boosters, reload y restricciones.
- [Comandos y PlaceholderAPI](superficies-operativas.md): comandos, permisos y placeholders incorporados.
- [Arquitectura y operación](arquitectura.md): módulos, persistencia, consistencia, lifecycle y garantías.

## Contrato de versión

| Elemento | Valor |
|---|---|
| Versión | `1.0.0` |
| Coordenadas API | `com.stephanofer:networkboosters-api:1.0.0` |
| Java | `25` |
| Paper API compilada | `26.1.2.build.74-stable` |
| `api-version` del plugin | `26.1` |
| Servicio Paper | `com.stephanofer.networkboosters.api.NetworkBoostersService` |

## Reglas esenciales

- Declarar NetworkBoosters como dependencia requerida y cargar la API con `compileOnly`.
- Obtener `NetworkBoostersService` desde `ServicesManager`; no instanciar implementaciones.
- Esperar `PlayerBoostersReadyEvent` o comprobar `isReady(playerId)` antes de usar estado o mutaciones.
- Tratar todos los snapshots y modelos como valores inmutables.
- Evaluar siempre el `status()` de los resultados; un `CompletableFuture` completado normalmente no implica éxito de negocio.
- No asumir el hilo de finalización de un future. Volver al hilo principal antes de usar API Bukkit no thread-safe.
- Usar `BigDecimal`, no `double`, para recompensas y multiplicadores.
- Usar `SourceReference.externalReference()` estable en grants externos que necesiten idempotencia.

## Alcance

La versión 1 solo expone boosters de scope `PERSONAL`. El único target con consumidor incorporado es `network_progression:points`; otros targets son válidos, pero requieren que otro plugin invoque `calculate(...)` al conceder su recurso.

Los documentos [Diseño final](../diseno-final-networkboosters.md) y [Plan de trabajo](../plan-de-trabajo-networkboosters.md) conservan el contexto de diseño e implementación. Esta carpeta describe el comportamiento efectivo de la versión terminada y prevalece para integraciones.
