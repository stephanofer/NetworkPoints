# NetworkPoints 1.0

NetworkPoints es la economía global de puntos de HERA Network. Este directorio documenta el contrato soportado por plugins consumidores de `networkpoints-api` versión `1.0.0` y las superficies operativas que afectan su integración.

## Qué ofrece

- Lecturas asíncronas desde una caché local respaldada por MySQL.
- Lectura síncrona de caché sin I/O para interfaces y placeholders.
- Créditos, débitos, asignaciones, premios con boosters y transferencias atómicas.
- Idempotencia durable mediante un `operationId` aportado por el consumidor.
- Resultados de negocio tipados y fallos de infraestructura excepcionales.
- Eventos Paper posteriores al commit y ejecutados en el hilo principal.
- Parseo y formato monetario coherentes con la configuración del servidor.
- Sincronización de caché entre servidores mediante Redis.

MySQL es la fuente autoritativa. Caffeine solo acelera lecturas y Redis solo propaga invalidaciones y notificaciones best-effort. Una decisión económica nunca debe tomarse desde `cachedBalance`.

## Recorrido recomendado

1. [Instalación e integración](integracion.md): dependencia, metadata Paper y obtención del servicio.
2. [Lecturas, caché e hilos](lecturas-cache-hilos.md): elección de la lectura correcta y manejo de futures.
3. [Mutaciones e idempotencia](mutaciones-idempotencia.md): operaciones, resultados, retries y errores.
4. [Eventos](eventos.md): readiness y notificaciones posteriores al commit.
5. [Cantidades](cantidades.md): `BigDecimal`, parseo y presentación.
6. [Referencia completa de la API](referencia-api.md): todos los tipos y miembros públicos.
7. [Superficies operativas](superficies-operativas.md): comandos, placeholders, integraciones y límites distribuidos.
8. [Garantías y límites](garantias-limites.md): persistencia, sincronización y responsabilidades del consumidor.

## Alcance

NetworkPoints administra exclusivamente cuentas de jugadores y sus puntos. No proporciona leaderboards, rewards, tiendas, conversión de monedas, Vault, Treasury ni gestión de boosters. Los paquetes internos de `networkpoints-paper` tampoco son API pública.

## Compatibilidad

| Elemento | Versión o contrato |
|---|---|
| Artefacto | `com.stephanofer:networkpoints-api:1.0.0` |
| Java | 25 |
| Paper API | 26.1 |
| Plugin runtime | `NetworkPoints` |
| Punto de entrada | `NetworkPointsService` mediante `ServicesManager` |
