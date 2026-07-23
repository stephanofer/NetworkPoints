# Garantías y límites

## Flujo durable

Una mutación nueva sigue este orden:

1. La solicitud valida su estructura localmente.
2. MySQL abre una transacción `READ_COMMITTED` con retry para errores transitorios.
3. Se bloquean las cuentas involucradas en orden estable.
4. Se busca y valida `operationId`.
5. Se reevalúan fondos, máximo de saldo y boosters dentro de la operación.
6. Se actualizan saldo y revisión.
7. Se escriben auditoría y resultado idempotente en la misma transacción.
8. Tras el commit, se publica la caché local.
9. Se intentan invalidación Redis y eventos Paper.

Si MySQL no confirma, NetworkPoints no publica un éxito ni altera la caché como si lo hubiera hecho. Si la respuesta se pierde después del commit, el retry compatible recupera el resultado durable.

## Auditoría e idempotencia

Cada éxito guarda actor opcional, fuente, referencia, servidor de origen, snapshots, delta y desglose. Una transferencia produce dos entradas de auditoría bajo el mismo `operationId`.

La limpieza por retención afecta el historial de transacciones, no la tabla técnica de operaciones idempotentes. Por eso un retry continúa protegido aunque su entrada visible de historial haya expirado.

La API pública no expone consultas de auditoría ni de operaciones. Esas superficies están disponibles solo mediante comandos administrativos en 1.0.

## Redis no decide la economía

Redis se usa para:

- avisar que una revisión nueva debe refrescarse desde MySQL;
- entregar feedback de pagos y acciones administrativas entre servidores.

Redis no almacena el saldo autoritativo, no transporta comandos económicos y no participa en el commit. Su indisponibilidad puede retrasar cachés o feedback remotos, pero no vuelve parcial una mutación MySQL.

## Semántica de entrega

- Los eventos API son locales y posteriores al commit.
- Las notificaciones visuales cross-server son best-effort y deduplicadas en memoria, no una cola durable.
- Las invalidaciones duplicadas, propias o antiguas se ignoran.
- Una caché remota ausente no se carga solo por recibir una invalidación.
- La expiración/refresh de caché y una lectura futura permiten convergencia si se perdió Pub/Sub.

No uses eventos o mensajes al jugador para confirmar una operación de negocio. La confirmación es el resultado normal del future y su `operationId`.

## Responsabilidades del consumidor

- Persistir y reutilizar el mismo `operationId` mientras el resultado sea incierto.
- Mantener idénticos todos los campos en un retry.
- Distinguir rechazo normal de future excepcional.
- No bloquear el hilo principal esperando I/O.
- Reprogramar callbacks antes de tocar APIs de Bukkit no thread-safe.
- No decidir fondos mediante snapshots de caché.
- Aplicar reglas propias de producto: permisos, precios, stock, cooldowns y confirmaciones.
- Usar `award` solo para recompensas elegibles para boosters y `credit` para créditos directos.
- Tratar tipos internos del módulo Paper como no soportados.

## Límites de la versión 1.0

- Una única moneda global de puntos.
- Solo cuentas asociadas a jugadores.
- Sin creación pública de cuentas ni resolución pública por nombre.
- Sin consulta pública de historial, transacciones o detalles de boosters aplicados.
- Sin eventos distribuidos ni bus durable para consumidores.
- Sin Vault, Treasury, leaderboards, tiendas, rewards o conversión de monedas.
- Los montos no pueden superar dos decimales ni el dominio `DECIMAL(30,2)`.
- `gameId`, `serverId`, source y referencias tienen los límites documentados en la API.
