# NetworkPoints: plan secuencial de implementación

| Campo | Valor |
| --- | --- |
| Estado | Aprobado para ejecución |
| Fuente de verdad | [`networkpoints-product-design.md`](networkpoints-product-design.md) |
| Estrategia | Seis bloques secuenciales con trabajo interno en paralelo |
| Condición final | Cumplimiento completo del diseño y sus criterios de aceptación |

Este plan organiza la implementación completa de NetworkPoints sin fragmentarla en microtareas ni permitir que una feature comience antes de que existan sus dependencias. Cada bloque entrega una capacidad integrada y verificable sobre la cual puede construirse el siguiente.

El detalle funcional, técnico y de comportamiento de cada capacidad se encuentra en [`docs/networkpoints-product-design.md`](networkpoints-product-design.md). Este plan determina el orden de construcción y el contenido general de cada bloque; no reemplaza ni redefine el diseño aprobado.

## 1. Secuencia obligatoria

```text
Bloque 1: Fundaciones y contratos
    ↓
Bloque 2: Núcleo económico durable
    ↓
Bloque 3: Runtime distribuido e integraciones de dominio
    ↓
Bloque 4: Experiencia, identidad y acceso desde Paper
    ↓
Bloque 5: Pagos y confirmación nativa
    ↓
Bloque 6: Endurecimiento y cierre del producto
```

No se comienza un bloque mientras la puerta de salida del bloque anterior permanezca abierta. Dentro de un bloque sí se trabaja en paralelo, siempre que los frentes respeten los contratos definidos al inicio del propio bloque.

## 2. Reglas de ejecución

### 2.1 El diseño es la autoridad

- Toda decisión funcional o arquitectónica debe corresponder con [`docs/networkpoints-product-design.md`](networkpoints-product-design.md).
- Este plan no autoriza simplificaciones que debiliten atomicidad, idempotencia, threading, localización o sincronización.
- Si aparece una necesidad no cubierta, se detiene únicamente el frente afectado y se resuelve la decisión antes de codificar una alternativa improvisada.
- No se adelantan features de bloques posteriores mediante implementaciones temporales.

### 2.2 Paralelismo dentro del bloque

- Cada frente paralelo debe tener ownership claro sobre paquetes y archivos para evitar ediciones cruzadas constantes.
- Los contratos compartidos se congelan al comenzar el bloque.
- Los cambios de contrato se coordinan con todos los frentes afectados antes de integrarse.
- Cada frente entrega código, tests unitarios aplicables y validación de sus invariantes.
- El bloque se cierra con una integración conjunta, no con ramas independientes que solo compilan por separado.

### 2.3 No se aceptan atajos temporales

- No se usa una API incorrecta con la intención de reemplazarla después.
- No se agregan implementaciones paralelas a las dependencias ya seleccionadas.
- No se bloquea el main thread para simplificar flujos asíncronos.
- No se valida saldo desde caché, ni siquiera provisionalmente.
- No se introduce write-behind para acelerar el desarrollo inicial.
- No se muestran UUID como fallback temporal.

### 2.4 Calidad dentro de cada bloque

- Los tests unitarios se implementan junto con la capacidad, no se posponen para el último bloque.
- Solo se utilizan JUnit y dobles escritos a mano mediante interfaces o lambdas.
- No se utilizan Mockito, Testcontainers, tests de integración automatizados ni Paper smoke tests.
- Los flujos MySQL, Redis y Paper se verifican manualmente cuando el bloque correspondiente los introduce.
- Un bloque no está terminado solo porque compile; debe cumplir su puerta de salida completa.

## 3. Política obligatoria de dependencias

Las dependencias aprobadas deben utilizarse desde la primera implementación de cada capacidad. No se construirán sustitutos locales ni adaptaciones improvisadas para avanzar más rápido.

| Capacidad | Dependencia obligatoria | Regla |
| --- | --- | --- |
| Configuración YAML | BoostedYAML | No usar Bukkit Configuration para copiar defaults, leer sections, actualizar archivos ni versionar configuración. |
| Comandos | Cloud Paper | No registrar ni parsear comandos directamente con Bukkit, Paper o Brigadier. |
| Persistencia MySQL | CraftKit Database | No crear pools, executors JDBC, migradores o transacciones por fuera de CraftKit. |
| Migraciones | Flyway mediante CraftKit Database | No ejecutar DDL manual durante el runtime normal. |
| Sincronización Redis | CraftKit Redis | No utilizar Lettuce directamente ni crear clientes Redis alternativos. |
| Caché local | Caffeine | No reemplazarla con mapas concurrentes y schedulers de expiración propios. |
| Componentes y texto | Adventure y MiniMessage provistos por Paper | No usar strings coloreados, legacy APIs o concatenación para identidades y feedback. |
| Idioma, nick y bandera | NetworkPlayerSettings | No duplicar resolución de idioma, estilos, países ni player head components. |
| Prefix y permisos | LuckPerms API | No leer metadata mediante comandos, placeholders o acceso interno al plugin. |
| Multiplicadores | NetworkBoosters API | No recalcular ni replicar lógica de boosters dentro de NetworkPoints. |
| Placeholders | PlaceholderAPI | Implementar una expansión interna oficial y libre de I/O. |
| Confirmación de pagos | Paper Dialog API | No usar inventarios, comandos de confirmación ni librerías externas para este flujo. |
| Tests unitarios | JUnit | No introducir frameworks de mocking ni infraestructura de integración automatizada. |

Antes de utilizar una API se debe revisar su código fuente local y el material de referencia disponible bajo `docs/`. Los proyectos fuente de las dependencias presentes en el workspace permiten verificar contratos, lifecycle, threading y comportamiento real.

Si una capacidad de infraestructura corresponde a CraftKit pero su API no cubre correctamente el caso, se evalúa y mejora el módulo de CraftKit correspondiente. No se implementan hacks dentro de NetworkPoints ni se duplica infraestructura compartida.

## 4. Bloque 1: fundaciones y contratos

### Objetivo

Construir la base compilable y estable sobre la que trabajarán todos los bloques posteriores: módulos, lifecycle inicial, configuración tipada, modelo monetario y contrato público de la API.

Este bloque debe cerrar las decisiones que afectarían a toda la implementación. Persistencia, Redis, comandos funcionales y UX todavía no se implementan.

### Contenido

- Configuración Gradle raíz y módulos `networkpoints-api` y `networkpoints-paper`.
- Dependencias con scopes correctos, publicación del API, Shadow y relocations privadas.
- Metadata de Paper, bootstrapper, loader y clase principal.
- Estructura feature-first aprobada.
- Lifecycle base y estados operativos iniciales.
- Carga de `config.yml`, `commands.yml` y mensajes mediante BoostedYAML.
- Versionado, defaults, actualización y validación atómica de configuración.
- Separación entre valores recargables y valores que requieren reinicio.
- Modelo monetario basado en `BigDecimal` con escala fija de dos decimales.
- Invariantes de cantidades y saldos.
- Parser de cantidades raw y con sufijos.
- Formatters `RAW`, `GROUPED` y `COMPACT`.
- Promoción correcta entre tiers compactos.
- Contratos públicos de snapshots, requests, contexts, results y statuses.
- Interfaz `NetworkPointsService`.
- Clases públicas de eventos sin despacho runtime todavía.
- Contrato de threading y nulabilidad del API.
- Tests unitarios del modelo monetario, parser, formatter y validación de configuración.

### Frentes paralelos

| Frente | Responsabilidad |
| --- | --- |
| Plataforma base | Gradle, módulos, empaquetado, metadata Paper y lifecycle base. |
| Configuración | Modelos tipados, loaders BoostedYAML, validaciones y snapshots atómicos. |
| Dominio monetario | Cantidades, límites, parser, sufijos, formatter y redondeo. |
| API pública | Servicio, requests, results, sources, snapshots y eventos públicos. |

Los frentes de dominio monetario y API deben acordar primero la representación de cantidades y resultados. Configuración consume esas invariantes, pero no las redefine.

### Dependencias habilitadas en este bloque

- Paper API.
- BoostedYAML.
- Adventure y MiniMessage provistos por Paper.
- JUnit.
- Shadow para el artefacto Paper.

### Puerta de salida

- Los dos módulos compilan y sus límites de dependencia son correctos.
- El API puede publicarse y ser consumido sin exponer clases internas del módulo Paper.
- El JAR Paper contiene y relocaliza únicamente dependencias privadas.
- Toda configuración se carga con BoostedYAML.
- Una configuración inválida falla de forma explícita o conserva el snapshot anterior durante reload.
- Todas las invariantes monetarias están implementadas y cubiertas por tests.
- Los formatters resuelven correctamente las fronteras entre tiers.
- Los contratos públicos necesarios para persistencia, boosters, comandos y pagos están congelados.
- No existe lógica económica dependiente de Paper dentro del módulo API.

### Referencia de diseño

Véanse las secciones 1, 3, 4, 5, 10, 18, 19, 20 y 21 de [`docs/networkpoints-product-design.md`](networkpoints-product-design.md).

## 5. Bloque 2: núcleo económico durable

### Objetivo

Implementar la fuente autoritativa de la economía y completar todas las mutaciones locales contra MySQL con atomicidad, auditoría e idempotencia. Al terminar este bloque, NetworkPoints debe poder administrar correctamente una cuenta sin depender todavía de caché o Redis.

### Contenido

- Configuración y lifecycle de CraftKit Database.
- Migración Flyway inicial.
- Tabla `networkpoints_accounts`.
- Tabla `networkpoints_transactions` e índices aprobados.
- Conversión JDBC de UUID mediante `BINARY(16)`.
- Repositorio de cuentas y snapshots.
- Creación de cuentas y saldo inicial.
- Actualización segura del nombre conocido y nombre normalizado.
- Búsqueda exacta case-insensitive de jugadores conocidos.
- Repositorio de transacciones e historial.
- `credit`, `debit`, `setBalance` y `transfer`.
- Transferencia con ambas cuentas dentro de una única transacción.
- Orden determinista de row locks.
- Validación de fondos dentro de MySQL.
- Validación de saldo máximo dentro de la transacción.
- Revisiones monotónicas por cuenta.
- Idempotencia mediante `operationId`.
- Detección de `IDEMPOTENCY_CONFLICT`.
- Retry de deadlocks y lock timeouts mediante CraftKit.
- Escritura del historial dentro de la misma transacción que el saldo.
- Consulta paginada del historial para uso posterior.
- Limpieza por lotes según retención.
- Implementación del servicio público sobre el núcleo durable para las operaciones disponibles.
- Tests unitarios de decisiones, invariantes, orden de locks, idempotencia y mapeos puros.
- Verificación manual de migraciones y transacciones con MySQL real.

### Frentes paralelos

| Frente | Responsabilidad |
| --- | --- |
| Schema y repositorios | Flyway, tablas, índices, JDBC UUID, cuentas y transacciones. |
| Motor de mutaciones | Credit, debit, set, transfer, locks, límites y retries. |
| Idempotencia y auditoría | Operation IDs, replay, conflictos, historial, paginación y retención. |
| Adaptador de API | Implementación de requests/results sobre el núcleo y manejo de fallos. |

Los repositorios deben exponer operaciones orientadas al dominio, no abstracciones JDBC genéricas. El motor de mutaciones debe utilizar siempre la `Connection` recibida por la transacción CraftKit.

### Dependencias habilitadas en este bloque

- CraftKit Database.
- Flyway encapsulado por CraftKit.
- Configuración tipada del bloque 1.
- API pública y dominio monetario del bloque 1.

### Puerta de salida

- MySQL es la única fuente autoritativa del saldo.
- Ninguna mutación devuelve éxito antes del commit.
- Debit nunca permite saldo negativo.
- Transfer actualiza débito, crédito e historial en una sola transacción.
- Una caída o excepción revierte toda la operación.
- Repetir un `operationId` compatible devuelve el resultado persistido.
- Reutilizar un `operationId` con datos diferentes devuelve conflicto.
- Las revisiones aumentan de forma monotónica.
- El historial representa exactamente las operaciones confirmadas.
- Las búsquedas por nombre no realizan scans completos ni exponen UUID.
- El main thread no ejecuta JDBC ni espera futures.
- Las verificaciones manuales de concurrencia local y rollback son satisfactorias.

### Referencia de diseño

Véanse las secciones 1, 6, 7, 10 y 21 de [`docs/networkpoints-product-design.md`](networkpoints-product-design.md).

## 6. Bloque 3: runtime distribuido e integraciones de dominio

### Objetivo

Transformar el núcleo durable local en un servicio de network: lecturas rápidas, sincronización entre servidores, lifecycle completo, eventos posteriores al commit e integración de awards con NetworkBoosters.

### Contenido

- Caché Caffeine de `BalanceSnapshot` versionados.
- Maximum size, refresh y expiración configurables.
- Deduplicación de cargas simultáneas.
- Protección contra cargas viejas que intenten sobrescribir snapshots nuevos.
- `cachedBalance`, `balance` y `refreshBalance`.
- Precarga durante la conexión del jugador.
- Descarga y limpieza de estado en quit.
- `PlayerPointsReadyEvent` cuando el snapshot queda disponible.
- Coordinador posterior al commit.
- Orden obligatorio: commit, caché, Redis, eventos y efectos locales.
- Despacho en main thread de eventos Paper públicos.
- Configuración y lifecycle de CraftKit Redis.
- Inicio Redis con `RedisStartupMode.RECOVER`.
- Payload versionado de invalidación.
- Codec estricto con límites de tamaño y validación.
- Publisher y subscriber Pub/Sub.
- Rechazo de eventos propios, antiguos o duplicados.
- Refresh desde MySQL al recibir una revisión nueva.
- Operación degradada cuando Redis no está disponible.
- Recuperación de suscripciones y estado operativo.
- Integración opcional con `NetworkBoostersService`.
- Pipeline `award` como única mutación boosteable.
- Uso de `BoosterTarget.NETWORK_PROGRESSION_POINTS`.
- Manejo explícito de `BOOSTER_STATE_NOT_READY`.
- Persistencia de cantidad base, multiplicador y cantidad final.
- `PointsBalanceChangeEvent`, `PointsTransferEvent` y `PointsAwardEvent` posteriores al commit.
- Cierre coordinado de caché, Redis, suscripciones y operaciones en curso.
- Tests unitarios de revisión, carreras de carga, codec, invalidaciones y cálculo de awards.
- Verificación manual con Redis real y dos servidores Paper.

### Frentes paralelos

| Frente | Responsabilidad |
| --- | --- |
| Caché y jugadores | Caffeine, in-flight loads, revisiones, preload, ready y unload. |
| Sincronización | CraftKit Redis, codec, publisher, subscriber y recuperación. |
| Post-commit y eventos | Orden de publicación, scheduling Paper y eventos públicos. |
| Awards | Adaptador NetworkBoosters, cálculo, estados y auditoría del multiplicador. |

Los frentes comparten el snapshot versionado y el resultado durable del bloque 2. Redis nunca modifica saldos directamente y NetworkBoosters nunca interviene en credit, debit, set o transfer.

### Dependencias habilitadas en este bloque

- Caffeine.
- CraftKit Redis.
- NetworkBoosters API.
- Paper Events y schedulers apropiados.
- Núcleo durable del bloque 2.

### Puerta de salida

- Las lecturas frecuentes no consultan MySQL mientras exista un snapshot válido.
- La caché nunca decide si hay fondos suficientes.
- Un snapshot con revisión antigua nunca reemplaza uno más nuevo.
- Una mutación confirmada actualiza la caché local después del commit.
- Otro servidor detecta la revisión nueva y refresca desde MySQL.
- Redis puede caer y recuperarse sin corromper el saldo ni detener MySQL.
- Los eventos Paper se disparan en main thread y solo después del commit.
- `award` aplica exclusivamente los boosters de NetworkBoosters.
- Un estado de boosters no listo no concede silenciosamente una cantidad neutral.
- Shutdown rechaza trabajo nuevo y cierra recursos en el orden establecido.
- Las pruebas manuales entre dos servidores confirman invalidación y recuperación.

### Referencia de diseño

Véanse las secciones 8, 9, 10, 11, 12 y 21 de [`docs/networkpoints-product-design.md`](networkpoints-product-design.md).

## 7. Bloque 4: experiencia, identidad y acceso desde Paper

### Objetivo

Construir la capa completa de interacción excepto el flujo de `pay`: identidad visual, idioma, feedback, comandos de consulta y administración, historial, status, reload y PlaceholderAPI.

Este bloque consume un servicio económico ya durable y distribuido. No debe introducir reglas monetarias nuevas en handlers Paper.

### Contenido

- Lookup obligatorio de `PlayerSettingsService`, `PlayerStyleService` y `CountryFlagService`.
- Respeto de `PlayerSettingsReadyEvent` e `isReady(UUID)`.
- Catálogos `messages/es.yml` y `messages/en.yml`.
- Fallback de idioma y snapshots de localización.
- Compilación y validación de acciones de feedback.
- Acciones `CHAT`, `ACTION_BAR`, `TITLE`, `SOUND` y `BOSS_BAR`.
- Timings, sonidos, progress y duraciones validados.
- Limpieza de bossbars en quit, reload y disable.
- Inserción segura de valores dinámicos mediante `Placeholder.component`.
- Renderer de identidad con prefix, nick style y bandera.
- LuckPerms metadata para jugadores online.
- LuckPerms `loadUser` y query options para jugadores offline.
- NetworkPlayerSettings formatted nick y country flag para online y offline.
- Separadores condicionales y formatos MiniMessage o legacy configurados explícitamente.
- Caché de identidad e invalidación por settings, LuckPerms, cambio de nombre y quit.
- Bootstrap y registro de comandos mediante Cloud Paper.
- Parser Cloud que reutiliza el parser monetario del bloque 1.
- Excepciones y errores de comandos conectados al feedback localizado.
- `/points`.
- `/points balance [jugador]`.
- `/points give`.
- `/points take`.
- `/points set`.
- `/points reset`.
- `/points history`.
- `/points reload`.
- `/points status`.
- Permisos, aliases, habilitación y descripciones configurables.
- Suggestions limitadas a jugadores online visibles.
- Resolución asíncrona de jugadores offline conocidos.
- Expansión interna de PlaceholderAPI.
- Placeholders raw, grouped, compact, display, symbol y currency name.
- Garantía de que PlaceholderAPI solo consulta caché y nunca realiza I/O.
- Tests unitarios de localización, feedback, identidad componible, parsers y outputs de placeholders.
- Verificación manual de comandos, idiomas, estilos, prefixes, banderas y placeholders.

### Frentes paralelos

| Frente | Responsabilidad |
| --- | --- |
| Localización y feedback | Catálogos, resolvers, acciones, validación y lifecycle visual. |
| Identidad | NetworkPlayerSettings, LuckPerms, render online/offline e invalidaciones. |
| Comandos | Cloud bootstrap, handlers, permisos, suggestions, status, reload e historial. |
| PlaceholderAPI | Expansión interna, outputs de formato y garantía libre de I/O. |

Cloud handlers deben limitarse a validar contexto Paper, invocar el API y presentar el resultado. No deben acceder directamente a repositorios ni duplicar reglas de negocio.

### Dependencias habilitadas en este bloque

- Cloud Paper.
- NetworkPlayerSettings.
- LuckPerms API.
- PlaceholderAPI.
- Adventure y MiniMessage provistos por Paper.
- API y runtime distribuido de los bloques anteriores.

### Puerta de salida

- Todo contenido para jugadores usa el idioma resuelto por NetworkPlayerSettings.
- Toda identidad visible se construye con Component y respeta prefix, nick style y bandera.
- Los jugadores offline se resuelven de forma asíncrona.
- Nunca se muestra un UUID como fallback.
- Todos los comandos del bloque están registrados exclusivamente con Cloud Paper.
- Los comandos administrativos usan las mutaciones públicas y no repositorios internos.
- Reload conserva el estado anterior cuando la nueva configuración es inválida.
- Las acciones de feedback se validan antes de entrar en servicio.
- Los placeholders nunca hacen I/O ni esperan futures.
- No existe ningún placeholder o comando de leaderboard.
- La UX funciona manualmente en español e inglés.

### Referencia de diseño

Véanse las secciones 13, 15, 16, 17, 18 y 20 de [`docs/networkpoints-product-design.md`](networkpoints-product-design.md).

## 8. Bloque 5: pagos y confirmación nativa

### Objetivo

Completar el flujo de pagos entre jugadores utilizando el núcleo `transfer`, la identidad, la localización, el feedback y Cloud Paper ya disponibles. Este bloque cierra la última feature funcional del producto.

### Contenido

- Registro de `/points pay` mediante Cloud Paper.
- Activación configurable del subcomando.
- Resolución de destinatarios online y offline conocidos.
- Exclusión del propio jugador.
- Parsing de atajos numéricos mediante el parser común.
- Validación del valor final expandido.
- `minimum-amount` y `maximum-amount`.
- `allow-offline-recipients`.
- Cooldown por jugador.
- Decisión de confirmación con comparación `>=`.
- Ejecución directa por debajo del umbral.
- Sesión efímera de confirmación por jugador.
- Reemplazo de una sesión anterior al crear una nueva.
- Expiración y limpieza en quit, reload y disable.
- Dialog dinámico y localizado mediante Paper Dialog API.
- Identidad Component del remitente y destinatario.
- Cantidad compacta y exacta dentro del dialog.
- Botones de confirmar y cancelar con tooltips.
- Callbacks de un solo uso y lifetime acotado.
- Rechazo de callbacks antiguos, duplicados o expirados.
- Revalidación completa al confirmar.
- Generación y reutilización correcta del `operationId`.
- Transferencia atómica sin reserva previa de saldo.
- Feedback localizado al remitente.
- Feedback al destinatario cuando está en el mismo servidor.
- Notificación best-effort al destinatario en otro servidor mediante el runtime Redis existente.
- Manejo de destinatario desconectado, cambio de nombre y saldo modificado durante la confirmación.
- Tests unitarios de decisiones, sesiones, expiración, reemplazo, doble confirmación y umbrales.
- Verificación manual completa del dialog y pagos entre servidores.

### Frentes paralelos

| Frente | Responsabilidad |
| --- | --- |
| Dominio de payment | Políticas, límites, cooldown, sesiones, expiración y resultados. |
| Dialog Paper | Construcción visual, callbacks, lifetime, confirmación y cancelación. |
| Comando y feedback | Cloud handler, resolución de target, identidad y mensajes localizados. |
| Notificación cross-server | Payload post-commit y entrega best-effort al destinatario remoto. |

El frente de Dialog no ejecuta SQL ni modifica saldo. Solo confirma una sesión válida y delega la transferencia al API público.

### Dependencias habilitadas en este bloque

- Cloud Paper.
- Paper Dialog API directamente.
- Identidad, localización y feedback del bloque 4.
- Transferencia atómica del bloque 2.
- Caché y sincronización del bloque 3.

### Puerta de salida

- Un pago por debajo del umbral se ejecuta directamente.
- Una cantidad exactamente igual al umbral abre el dialog.
- El dialog muestra remitente, destinatario y monto sin UUID.
- Confirmar revalida fondos, límites, destinatario y sesión.
- Cancelar, cerrar, expirar o desconectarse no modifica saldos.
- Un callback no puede utilizarse dos veces.
- Una confirmación antigua no puede ejecutar un pago nuevo.
- El cambio de saldo mientras el dialog está abierto se maneja correctamente.
- La transferencia sigue siendo atómica en pagos locales y cross-server.
- El remitente recibe feedback únicamente después del commit.
- El destinatario online recibe feedback localizado cuando la señal está disponible.
- Los tests y la matriz manual de pagos quedan satisfechos.

### Referencia de diseño

Véanse las secciones 13 y 14 de [`docs/networkpoints-product-design.md`](networkpoints-product-design.md), además de las garantías de identidad, feedback y sincronización de las secciones 9, 15 y 16.

## 9. Bloque 6: endurecimiento y cierre del producto

### Objetivo

Validar el sistema completo como una sola unidad, cerrar edge cases transversales, medir los caminos críticos y comprobar todos los criterios de aceptación antes de considerar NetworkPoints terminado.

Este bloque no es el lugar para implementar features omitidas. Cualquier feature incompleta devuelve el trabajo al bloque que la posee antes de continuar con el cierre.

### Contenido

- Ejecución completa de la suite JUnit de ambos módulos.
- Revisión de cobertura de invariantes y edge cases.
- Pruebas unitarias adicionales descubiertas durante la integración.
- Verificación manual con MySQL real.
- Verificación manual con Redis real, caída y recuperación.
- Verificación manual con dos servidores Paper.
- Débitos concurrentes sobre la misma cuenta.
- Pago y compra simultáneos.
- Mutaciones administrativas concurrentes.
- Reintentos con el mismo `operationId`.
- Commit confirmado cuya respuesta se pierde.
- Cambio rápido de servidor.
- Reinicio durante operaciones en curso.
- Redis desconectado durante mutaciones.
- MySQL desconectado durante comandos y API.
- NetworkBoosters no listo.
- NetworkPlayerSettings no listo.
- Reload válido e inválido.
- Limpieza de cachés, bossbars, dialogs y subscriptions.
- Retención y borrado por lotes del historial.
- Revisión del main thread para confirmar ausencia de JDBC, Redis bloqueante y waits.
- Revisión de allocations y paths calientes de placeholders, caché y formatters.
- Verificación de límites del pool y executors bajo carga manual controlada.
- Revisión del JAR sombreado, relocations y service files.
- Verificación de que las APIs de plugins externos no fueron empaquetadas.
- Revisión de logs, mensajes de fallo y estado degradado.
- Recorrido final de todos los criterios de aceptación del diseño.

### Frentes paralelos

| Frente | Responsabilidad |
| --- | --- |
| Concurrencia y persistencia | Carreras, rollback, idempotencia, auditoría y fallos MySQL. |
| Network y lifecycle | Redis, dos servidores, conexión, reload, shutdown y recuperación. |
| UX completa | Comandos, idiomas, feedback, identidad, dialog y placeholders. |
| Build y rendimiento | Suite, JAR, relocations, threading, caché y paths calientes. |

Los hallazgos se corrigen en la feature propietaria sin introducir parches transversales que rompan la arquitectura.

### Dependencias habilitadas en este bloque

Todas las dependencias aprobadas y todas las capacidades integradas en los bloques anteriores.

### Puerta de salida

- Todos los tests unitarios pasan.
- Todas las verificaciones manuales obligatorias han sido completadas.
- MySQL sigue siendo la única fuente autoritativa.
- Ninguna mutación comunica éxito antes del commit.
- Transferencias y pagos son atómicos e idempotentes.
- Caffeine no valida fondos.
- Redis puede fallar sin corromper la economía.
- No existe I/O bloqueante en el main thread.
- `award` es la única operación que aplica boosters.
- Todo contenido visible respeta idioma e identidad.
- Ningún flujo muestra UUID al usuario.
- Los placeholders son libres de I/O.
- El historial coincide con las mutaciones confirmadas.
- No existe funcionalidad de leaderboard.
- El artefacto final contiene únicamente las dependencias privadas esperadas.
- Se cumplen todos los criterios de aceptación de la sección 25 del diseño.

### Referencia de diseño

Véanse las secciones 21, 22, 23, 25 y 26 de [`docs/networkpoints-product-design.md`](networkpoints-product-design.md).

## 10. Matriz de cobertura del diseño

| Sección del diseño | Bloque responsable |
| --- | --- |
| 1. Decisión arquitectónica | Bloques 1, 2 y 3 |
| 2. Decisiones tomadas a partir de PlayerPoints | Reglas globales y bloques 2 y 3 |
| 3. Alcance funcional | Todos los bloques; validación final en bloque 6 |
| 4. Modelo monetario | Bloque 1 |
| 5. Formato de cantidades | Bloque 1 |
| 6. Persistencia y concurrencia | Bloque 2 |
| 7. Historial técnico | Bloque 2; acceso Paper en bloque 4 |
| 8. Caché local | Bloque 3 |
| 9. Sincronización Redis | Bloque 3 |
| 10. API pública | Contratos en bloque 1; implementación en bloques 2 y 3 |
| 11. NetworkBoosters | Bloque 3 |
| 12. Eventos Paper | Bloque 3 |
| 13. Comandos | Bloque 4; `pay` en bloque 5 |
| 14. Pagos | Bloque 5 |
| 15. Identidad visual | Bloque 4 |
| 16. Localización y feedback | Bloque 4; feedback de pay en bloque 5 |
| 17. PlaceholderAPI | Bloque 4 |
| 18. Configuración | Base en bloque 1; integración y reload en bloque 4 |
| 19. Arquitectura de módulos | Bloque 1 |
| 20. Dependencias | Política global y bloque correspondiente a cada integración |
| 21. Lifecycle | Base en bloque 1; recursos completos en bloque 3; validación en bloque 6 |
| 22. Estrategia de pruebas | Todos los bloques; cierre en bloque 6 |
| 23. Edge cases | Bloque propietario; recorrido completo en bloque 6 |
| 24. Orden de implementación | Sustituido por este plan agrupado |
| 25. Criterios de aceptación | Bloque 6 |
| 26. Resultado final | Bloque 6 |

## 11. Resumen operativo

| Bloque | Entrega integrada | Habilita |
| --- | --- | --- |
| 1 | Contratos, configuración y dominio monetario | Persistencia estable sin redefinir modelos |
| 2 | Economía MySQL atómica e idempotente | Caché, Redis e integraciones seguras |
| 3 | Runtime distribuido, eventos y awards | Capas Paper sin acceso directo a infraestructura |
| 4 | Identidad, feedback, comandos y placeholders | Payment con todas sus dependencias visuales listas |
| 5 | Pago completo con Dialog API | Producto funcional completo |
| 6 | Validación transversal y artefacto final | Cierre del producto |

La regla de avance es simple: cada bloque debe dejar una capacidad terminada, integrada y verificable. El siguiente bloque consume contratos estables y nunca vuelve hacia atrás para construir una dependencia olvidada.
