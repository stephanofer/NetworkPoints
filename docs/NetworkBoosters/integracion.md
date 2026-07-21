# Instalación e integración

## Dependencia de compilación

El artefacto API se publica como `com.stephanofer:networkboosters-api:1.0.0`. El build actual permite publicarlo en Maven local:

```powershell
.\gradlew.bat :networkboosters-api:publishToMavenLocal
```

En el plugin consumidor:

```kotlin
repositories {
    mavenLocal()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("com.stephanofer:networkboosters-api:1.0.0")
}
```

Usar `compileOnly`: el JAR de NetworkBoosters ya aporta las clases API en runtime. NO sombrear ni reubicar `networkboosters-api`, porque eso crea clases distintas y rompe `ServicesManager` y los eventos.

## Dependencia Paper

Declarar la dependencia en el `paper-plugin.yml` del consumidor:

```yaml
dependencies:
  server:
    NetworkBoosters:
      load: BEFORE
      required: true
      join-classpath: true
```

`required: true` impide iniciar el consumidor sin NetworkBoosters. `join-classpath: true` hace visible su API. El nombre debe ser exactamente `NetworkBoosters`.

## Obtener el servicio

El servicio se registra durante `onEnable()` con prioridad `Normal` y se elimina durante el apagado.

```java
import com.stephanofer.networkboosters.api.NetworkBoostersService;
import org.bukkit.plugin.java.JavaPlugin;

public final class RewardsPlugin extends JavaPlugin {
    private NetworkBoostersService boosters;

    @Override
    public void onEnable() {
        this.boosters = getServer().getServicesManager().load(NetworkBoostersService.class);
        if (this.boosters == null) {
            throw new IllegalStateException("NetworkBoostersService is not registered");
        }
    }

    @Override
    public void onDisable() {
        this.boosters = null;
    }
}
```

No conservar referencias después de que el consumidor se deshabilite y no construir `NetworkBoostersServiceImpl`: pertenece al módulo interno Paper.

## Estado listo del jugador

NetworkBoosters carga el snapshot después de que `NetworkPlayerSettings` emite su evento de readiness. Al completar la carga para un jugador online emite `PlayerBoostersReadyEvent` en el hilo principal.

```java
@EventHandler
public void onBoostersReady(PlayerBoostersReadyEvent event) {
    PlayerBoostSnapshot snapshot = event.snapshot();
    // El snapshot ya puede leerse y calculate(...) puede aplicar sus activos.
}
```

Para código que puede ejecutarse después del evento:

```java
UUID playerId = player.getUniqueId();
if (!boosters.isReady(playerId)) {
    return;
}
PlayerBoostSnapshot snapshot = boosters.getCachedOrEmpty(playerId);
```

`getCachedOrEmpty()` NO demuestra readiness: devuelve un snapshot vacío sintético cuando no hay caché. Comprobar `isReady()` primero cuando la diferencia entre “vacío” y “todavía no cargado” importa.

## Carga explícita

- `load(playerId)` reutiliza el snapshot en caché o una carga ya en curso.
- `refresh(playerId)` fuerza lectura durable y publica el resultado si corresponde.
- Ambos devuelven `CompletableFuture<PlayerBoostSnapshot>` y pueden completarse excepcionalmente.
- NetworkBoosters descarga automáticamente la caché al salir un jugador.

La carga explícita de UUID offline es técnicamente posible, pero no emite `PlayerBoostersReadyEvent` y las reglas de capacidad basadas en permisos usan el fallback si el jugador no está online. Preferir el lifecycle normal para jugadores.

## Hilos y futures

Las lecturas de caché, definiciones y `calculate(...)` son síncronas, thread-safe y sin I/O. Las mutaciones y cargas son asíncronas.

No hay una garantía única sobre el hilo que completa un future: un rechazo temprano puede completarse inmediatamente y una operación durable normalmente completa en infraestructura asíncrona. Para tocar Bukkit:

```java
boosters.activate(request).whenComplete((result, failure) ->
    getServer().getScheduler().runTask(this, () -> {
        if (failure != null) {
            getLogger().severe("Activation future failed: " + failure.getMessage());
            return;
        }
        player.sendMessage("Activation status: " + result.status());
    })
);
```

Los eventos públicos sí son despachados en el hilo principal.

## Compatibilidad

El API compila para Java 25 y contiene tipos de eventos Paper/Bukkit. El consumidor debe usar una toolchain y servidor compatibles. Cambios futuros en tipos o métodos se versionarán en el artefacto; no copiar clases API al proyecto consumidor.
