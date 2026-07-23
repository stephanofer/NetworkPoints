# Cantidades, parseo y formato

## Dominio monetario

- Tipo Java: `BigDecimal`.
- Persistencia: `DECIMAL(30,2)`.
- Escala fija: 2.
- Máximo técnico: `MonetaryAmounts.MAX_VALUE`, equivalente a `9999999999999999999999999999.99`.
- Máximo efectivo de saldo: `currency.maximum-balance` del servidor.
- Saldos: siempre no negativos.

Construí cantidades desde texto, no desde `double`:

```java
BigDecimal correct = new BigDecimal("12.50");
BigDecimal unsafe = new BigDecimal(12.50); // No usar: incorpora la aproximación binaria.
```

`MonetaryAmounts.nonNegative`, `positive` y `signed` validan el signo, el dominio y que no haga falta redondear para escala 2. `multiplier` exige un valor positivo representable exactamente como `DECIMAL(20,8)`.

## Parsear input

`parseAmount(String)` no lanza por input de usuario malformado. Devuelve:

```java
AmountParseResult parsed = points.parseAmount(input);
if (parsed instanceof AmountParseResult.Success success) {
    BigDecimal amount = success.amount();
} else if (parsed instanceof AmountParseResult.Failure failure) {
    showLocalizedError(failure.reason());
}
```

La sintaxis soportada es una parte decimal no negativa seguida opcionalmente por un sufijo alfabético configurado:

| Entrada con configuración por defecto | Resultado |
|---|---:|
| `10` | `10.00` |
| `1.5K` | `1500.00` |
| `2m` | `2000000.00` |
| `0.1k` | `100.00` |

Los sufijos no distinguen mayúsculas. No se aceptan espacios, signos, comas, notación científica, `.5`, `1.` ni más de dos decimales en la parte escrita.

| `Reason` | Caso |
|---|---|
| `EMPTY` | `null` o cadena vacía. |
| `INVALID_FORMAT` | Sintaxis no admitida, incluidos espacios o signos. |
| `TOO_MANY_DECIMALS` | Tres o más decimales con el resto de la sintaxis válido. |
| `UNKNOWN_SUFFIX` | Sufijo alfabético no configurado. |
| `OUT_OF_RANGE` | Expansión no representable o superior a los límites configurados. |

El parser público admite cero porque también sirve para `setBalance`. `CreditRequest`, `DebitRequest`, `AwardRequest` y `TransferRequest` lo rechazan posteriormente por exigir una cantidad positiva.

## Formato plain text

`formatAmountPlain(amount, mode)` valida que el valor sea no negativo y tenga como máximo dos decimales.

| Modo | Ejemplo para `12500.00` | Contrato |
|---|---|---|
| `RAW` | `12500` | Sin agrupación ni ceros finales insignificantes. |
| `GROUPED` | `12,500` | Patrón y separadores configurados. |
| `COMPACT` | `12.5K` | Tier, patrón y sufijo configurados. |

Los formatos agrupado y compacto redondean visualmente con `HALF_UP`; no modifican el valor económico. En fronteras compactas, el render puede promocionar al tier siguiente, por ejemplo `999999` a `1.0M` con la configuración por defecto.

## Formato Adventure

`formatAmount(amount)` devuelve un `Component` usando:

- el modo predeterminado configurado;
- el estilo MiniMessage del tier compacto, si corresponde;
- `currency.display-format` con `<amount>` y `<symbol>`.

Usalo para mensajes Adventure. Para persistencia, logs, comparaciones o protocolos, conservá el `BigDecimal` o elegí explícitamente `RAW`; no analices de vuelta el texto presentado.

El parser y los formatters reflejan cambios aplicados por `/points reload` de forma atómica. Un consumidor no debe cachear indefinidamente resultados de presentación si espera seguir la configuración activa.
