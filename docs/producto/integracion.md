# Instalación e integración

## Dependencia de compilación

El módulo API se publica con la coordenada `com.stephanofer:networkpoints-api:1.0.0`. El repositorio actual permite instalarlo en Maven local:

```powershell
.\gradlew.bat :networkpoints-api:publishToMavenLocal
```

Configuración Gradle del consumidor:

```kotlin
repositories {
    mavenLocal()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("com.stephanofer:networkpoints-api:1.0.0")
}
```

Usá `compileOnly`: NetworkPoints aporta sus clases API en runtime. NO sombrees, reubiques ni copies `networkpoints-api`; hacerlo crea identidades de clase distintas y rompe `ServicesManager`, los casts y los eventos Bukkit.

## Dependencia Paper

Declarala en el `paper-plugin.yml` del consumidor:

```yaml
dependencies:
  server:
    NetworkPoints:
      load: BEFORE
      required: true
      join-classpath: true
```

- El nombre debe ser exactamente `NetworkPoints`.
- `required: true` evita iniciar el consumidor sin la dependencia.
- `load: BEFORE` garantiza que NetworkPoints se habilite primero.
- `join-classpath: true` permite acceder a las clases API aportadas por su JAR.

## Obtener el servicio

NetworkPoints registra `NetworkPointsService` durante su `onEnable()` con prioridad `Normal` y lo elimina durante el apagado.

```java
import com.stephanofer.networkpoints.api.NetworkPointsService;
import org.bukkit.plugin.java.JavaPlugin;

public final class RewardsPlugin extends JavaPlugin {
    private NetworkPointsService points;

    @Override
    public void onEnable() {
        this.points = getServer().getServicesManager().load(NetworkPointsService.class);
        if (this.points == null) {
            throw new IllegalStateException("NetworkPointsService is not registered");
        }
    }

    @Override
    public void onDisable() {
        this.points = null;
    }
}
```

No construyas `DurableNetworkPointsService` ni consumas repositorios, cachés o configuración del módulo Paper. Son detalles internos sin compatibilidad pública.

## Primer crédito

```java
import com.stephanofer.networkpoints.api.request.CreditRequest;
import com.stephanofer.networkpoints.api.source.MutationContext;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.key.Key;

UUID operationId = UUID.randomUUID();
MutationContext context = new MutationContext(
        operationId,
        Key.key("rewards:match_reward"),
        Optional.empty(),
        Optional.of("match:8f51"));

points.credit(new CreditRequest(playerId, new BigDecimal("25.00"), context))
        .whenComplete((result, failure) -> {
            if (failure != null) {
                getLogger().severe("Credit infrastructure failure: " + failure.getMessage());
                return;
            }
            if (!result.success()) {
                getLogger().warning("Credit rejected: " + result.status());
                return;
            }
            getLogger().info("Committed balance: "
                    + result.after().orElseThrow().balance().toPlainString());
        });
```

El callback no tiene garantía de hilo principal. Si toca Bukkit, entidades o UI, reprogramalo con el scheduler de tu plugin. Consultá [Mutaciones e idempotencia](mutaciones-idempotencia.md) antes de definir cómo persistir y reutilizar `operationId`.

## Dependencias runtime de NetworkPoints

El servidor necesita `NetworkPlayerSettings` y `LuckPerms`; son dependencias obligatorias del propio NetworkPoints. `NetworkBoosters` y `PlaceholderAPI` son opcionales según la configuración. El consumidor no necesita declararlas salvo que use directamente sus APIs.
