# NetworkPoints: validación de cierre

Esta matriz es la puerta operativa del Bloque 6. Un artefacto compilable no cierra el producto: cada escenario manual debe conservar evidencia, resultado y responsable antes de aprobar despliegue.

## Estado actual

| Área | Estado | Evidencia |
| --- | --- | --- |
| Suite JUnit | Automatizada | `./gradlew.bat clean test` |
| Shadow JAR | Automatizada | `./gradlew.bat verifyShadowJar` |
| MySQL real | Pendiente | Ejecutar matriz de persistencia |
| Redis real | Pendiente | Ejecutar matriz de network |
| Dos servidores Paper | Pendiente | Ejecutar matriz distribuida y UX |
| NetworkBoosters | Integración actualizada | Pendiente validación manual de readiness, neutral y boosted |

## Artefacto candidato

Ejecutar desde PowerShell:

```powershell
.\gradlew.bat clean test verifyShadowJar
Get-FileHash -Algorithm SHA256 .\networkpoints-paper\build\libs\networkpoints-paper-1.0.0.jar
```

Registrar antes de probar:

| Campo | Valor |
| --- | --- |
| Commit | |
| SHA-256 del JAR | |
| Java | |
| Paper | `26.1` |
| MySQL | |
| Redis | |
| NetworkPlayerSettings | |
| NetworkBoosters | |
| LuckPerms | |
| PlaceholderAPI | |
| Fecha | |
| Responsable | |

No reutilizar evidencia entre JAR distintos.

## Persistencia

Usar cuentas desechables y conservar consultas SQL antes y después de cada escenario.

| ID | Escenario | Resultado obligatorio | Estado |
| --- | --- | --- | --- |
| DB-01 | Migración sobre schema vacío | Se crean cuentas, transacciones y registro técnico de operaciones | [ ] |
| DB-02 | Credit, debit y set | Saldo, revisión, historial y operación confirman juntos | [ ] |
| DB-03 | Transferencia | Ambas cuentas y dos entradas confirman en una transacción | [ ] |
| DB-04 | Fondos insuficientes | No cambia saldo, revisión, historial ni operación confirmada | [ ] |
| DB-05 | Máximo de saldo | El límite exacto confirma; excederlo no modifica datos | [ ] |
| DB-06 | Dos débitos concurrentes | Solo confirman los que el saldo autoritativo permite | [ ] |
| DB-07 | Pago y compra simultáneos | No hay saldo negativo ni aceptación basada en caché | [ ] |
| DB-08 | Dos mutaciones administrativas | Revisiones monotónicas e historial alineado | [ ] |
| DB-09 | Mismo `operationId`, misma solicitud | Devuelve replay sin nueva revisión ni historial | [ ] |
| DB-10 | Mismo `operationId`, solicitud distinta | Devuelve `IDEMPOTENCY_CONFLICT` | [ ] |
| DB-11 | Respuesta perdida después de commit | El retry recupera el resultado confirmado | [ ] |
| DB-12 | Cleanup por lotes | Nunca borra más del batch configurado | [ ] |
| DB-13 | Replay después de cleanup | El registro técnico impide ejecutar nuevamente la operación | [ ] |
| DB-14 | MySQL desconectado | Future/comando falla sin cambiar caché ni comunicar éxito | [ ] |
| DB-15 | Reinicio con operación activa | Commit completo o rollback; nunca estado parcial | [ ] |

Consultas mínimas de evidencia:

```sql
SELECT player_uuid, balance, revision FROM networkpoints_accounts;
SELECT operation_id, entry_index, account_uuid, transaction_type, delta,
       balance_before, balance_after, revision_before, revision_after
FROM networkpoints_transactions ORDER BY id;
SELECT operation_id, mutation_type, account_uuid, counterparty_uuid,
       request_amount, account_revision_before, account_revision_after
FROM networkpoints_operations ORDER BY created_at;
SELECT operation_id, entry_index, activation_id, booster_id, activation_group, multiplier
FROM networkpoints_operation_boosters ORDER BY operation_id, entry_index;
```

## Redis Y Network

| ID | Escenario | Resultado obligatorio | Estado |
| --- | --- | --- | --- |
| NET-01 | Dos servidores y una mutación | El remoto refresca desde MySQL al recibir revisión nueva | [ ] |
| NET-02 | Evento propio | El servidor emisor no recarga su propia invalidación | [ ] |
| NET-03 | Invalidación antigua o duplicada | No reemplaza revisión nueva ni duplica trabajo exitoso | [ ] |
| NET-04 | Falla MySQL durante refresh | La misma invalidación puede reintentarse | [ ] |
| NET-05 | Redis caído durante mutación | MySQL confirma y la economía sigue correcta | [ ] |
| NET-06 | Recuperación de Redis | Subscription vuelve a estado operativo | [ ] |
| NET-07 | Pago cross-server | Destinatario recibe feedback best-effort una sola vez | [ ] |
| NET-08 | Cambio rápido de servidor | Quit no permite que una precarga vieja recachee al jugador | [ ] |

Registrar timestamps correlacionados de ambos servidores y estado Redis antes y después de cada escenario.

## Paper Y UX

Ejecutar cada flujo con idioma `es` y `en` cuando produzca contenido visible.

| ID | Escenario | Resultado obligatorio | Estado |
| --- | --- | --- | --- |
| UX-01 | Jugador no listo en NetworkPlayerSettings | No se ejecuta pay y recibe feedback inmediato localizado | [ ] |
| UX-02 | Balance propio y ajeno | Identidad usa prefix, nick style y bandera | [ ] |
| UX-03 | Jugador sin prefix, país o bandera | Render válido, sin UUID | [ ] |
| UX-04 | Identidad offline falla después de commit | Se confirma éxito con último nombre conocido | [ ] |
| UX-05 | Pay debajo del umbral | Ejecuta directamente | [ ] |
| UX-06 | Pay exactamente en el umbral | Abre Dialog nativo | [ ] |
| UX-07 | Confirmar dos veces | Solo una transferencia | [ ] |
| UX-08 | Dialog expirado, reemplazado o cerrado | No modifica saldo | [ ] |
| UX-09 | Saldo cambia con Dialog abierto | Confirmación revalida y responde correctamente | [ ] |
| UX-10 | Destinatario se desconecta | Respeta `allow-offline-recipients` al ejecutar | [ ] |
| UX-11 | Reload válido | Se aplica sin I/O de archivos en main thread | [ ] |
| UX-12 | Reload inválido | Conserva snapshot anterior | [ ] |
| UX-13 | Quit, reload y disable | Limpia bossbars, dialogs, cachés y subscriptions | [ ] |
| UX-14 | Placeholders con caché lista/no lista | No hacen I/O ni esperan futures | [ ] |
| UX-15 | Comandos deshabilitados | Root y subcomandos configurados no se registran | [ ] |

## Carga Y Threading

| ID | Escenario | Resultado obligatorio | Estado |
| --- | --- | --- | --- |
| PERF-01 | Placeholders en frecuencia alta | Sin JDBC, Redis, waits ni crecimiento no acotado | [ ] |
| PERF-02 | Lecturas concurrentes de caché | Loads deduplicados y revisiones sin regresión | [ ] |
| PERF-03 | Mutaciones controladas bajo carga | Pool estable, sin starvation ni cola creciente | [ ] |
| PERF-04 | Shutdown bajo carga | Rechaza trabajo nuevo y respeta timeout configurado | [ ] |
| PERF-05 | Reload durante lecturas | Parser y formatter observan snapshots completos | [ ] |

Capturar profiler, timings de tick, métricas del pool y logs. No aprobar únicamente por ausencia de excepciones.

## Artefacto

| Comprobación | Estado |
| --- | --- |
| `paper-plugin.yml` contiene versión expandida | [ ] |
| API de NetworkPoints incluida | [ ] |
| Dependencias privadas relocalizadas | [ ] |
| Paper, Adventure y APIs de otros plugins ausentes | [ ] |
| Service descriptors presentes y funcionales | [ ] |
| Migración incluida | [ ] |
| No existen clases de leaderboard | [ ] |

El task `verifyShadowJar` automatiza estructura y namespaces conocidos. El arranque real valida además providers JDBC/Flyway y metadata Paper.

## NetworkBoosters

NetworkPoints utiliza `calculateIfReady(BoostRequest)`, que captura el snapshot una sola vez. La integración interpreta vacío como `BOOSTER_STATE_NOT_READY` y un resultado presente con multiplicador `1` como cálculo neutral legítimo. No se utiliza la secuencia vulnerable `isReady()` seguida de `calculate()`.

## Aprobación

El producto queda listo para despliegue únicamente cuando:

- [ ] Todos los escenarios obligatorios tienen evidencia satisfactoria.
- [ ] No hay errores o warnings sin explicar en logs.
- [ ] Los casos NetworkBoosters no listo, neutral y boosted están probados manualmente.
- [ ] El hash aprobado coincide con el JAR desplegado.
- [ ] Dos responsables revisaron atomicidad, idempotencia y rollback.
