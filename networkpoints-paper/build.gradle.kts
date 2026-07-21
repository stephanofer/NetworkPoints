import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":networkpoints-api"))

    compileOnly(libs.paper.api)
    compileOnly(libs.network.boosters.api)
    compileOnly(libs.network.player.settings)
    compileOnly(libs.luckperms.api)
    compileOnly(libs.placeholder.api)

    implementation(libs.craftkit.database)
    implementation(libs.craftkit.redis)
    implementation(libs.boosted.yaml)
    implementation(libs.caffeine)
    implementation(libs.cloud.paper)

    testImplementation(project(":networkpoints-api"))
    testImplementation(libs.paper.api)
    testImplementation(libs.network.boosters.api)
    testImplementation(libs.network.player.settings)
    testImplementation(libs.luckperms.api)
    testImplementation(libs.placeholder.api)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    append("META-INF/io.netty.versions.properties")

    exclude("LICENSE")
    exclude("META-INF/LICENSE")
    exclude("META-INF/LICENSE.txt")
    exclude("META-INF/NOTICE")

    relocate("dev.dejvokep.boostedyaml", "com.stephanofer.networkpoints.libs.boostedyaml")
    relocate("com.hera.craftkit", "com.stephanofer.networkpoints.libs.craftkit")
    relocate("com.zaxxer.hikari", "com.stephanofer.networkpoints.libs.hikari")
    relocate("org.flywaydb", "com.stephanofer.networkpoints.libs.flyway")
    relocate("tools.jackson", "com.stephanofer.networkpoints.libs.jackson3")
    relocate("com.fasterxml.jackson", "com.stephanofer.networkpoints.libs.jackson")
    relocate("com.mysql", "com.stephanofer.networkpoints.libs.mysql")
    relocate("com.google.protobuf", "com.stephanofer.networkpoints.libs.protobuf")
    relocate("com.github.benmanes.caffeine", "com.stephanofer.networkpoints.libs.caffeine")
    relocate("io.lettuce", "com.stephanofer.networkpoints.libs.lettuce")
    relocate("redis.clients.authentication", "com.stephanofer.networkpoints.libs.redisAuthx")
    relocate("io.netty", "com.stephanofer.networkpoints.libs.netty")
    relocate("reactor", "com.stephanofer.networkpoints.libs.reactor")
    relocate("org.reactivestreams", "com.stephanofer.networkpoints.libs.reactiveStreams")
    relocate("org.incendo.cloud", "com.stephanofer.networkpoints.libs.cloud")
}

val shadowArchive = tasks.shadowJar.flatMap { it.archiveFile }
val verifyShadowJar = tasks.register("verifyShadowJar") {
    dependsOn(tasks.shadowJar)
    inputs.file(shadowArchive)

    doLast {
        ZipFile(inputs.files.singleFile).use { jar ->
            val entries = jar.entries().asSequence().map { it.name }.toSet()
            val required = setOf(
                "paper-plugin.yml",
                "com/stephanofer/networkpoints/NetworkPointsPlugin.class",
                "com/stephanofer/networkpoints/api/NetworkPointsService.class",
                "com/stephanofer/networkpoints/libs/boostedyaml/YamlDocument.class",
                "com/stephanofer/networkpoints/libs/craftkit/database/Database.class",
                "com/stephanofer/networkpoints/libs/hikari/HikariDataSource.class",
                "com/stephanofer/networkpoints/libs/flyway/core/Flyway.class",
                "com/stephanofer/networkpoints/libs/mysql/cj/jdbc/Driver.class",
                "com/stephanofer/networkpoints/libs/caffeine/cache/Caffeine.class",
                "com/stephanofer/networkpoints/libs/craftkit/redis/RedisClient.class",
                "com/stephanofer/networkpoints/libs/cloud/paper/PaperCommandManager.class",
                "db/migration/V1__create_networkpoints.sql"
            )
            check(entries.containsAll(required)) {
                "Shadow JAR is missing: ${required - entries}"
            }
            check(entries.none { it.startsWith("dev/dejvokep/boostedyaml/") }) {
                "Shadow JAR contains unrelocated BoostedYAML classes"
            }
            val forbidden = listOf(
                "com/hera/craftkit/",
                "io/papermc/",
                "io/lettuce/",
                "io/netty/",
                "redis/clients/authentication/",
                "reactor/",
                "org/reactivestreams/",
                "com/stephanofer/networkboosters/",
                "com/stephanofer/networkplayersettings/",
                "net/luckperms/",
                "me/clip/placeholderapi/",
                "net/kyori/",
                "org/bukkit/",
                "com/zaxxer/hikari/",
                "org/flywaydb/",
                "tools/jackson/",
                "com/fasterxml/jackson/",
                "com/mysql/",
                "com/google/protobuf/"
            )
            check(entries.none { entry -> forbidden.any(entry::startsWith) }) {
                "Shadow JAR contains unrelocated or externally provided dependencies"
            }
        }
    }
}

tasks.build {
    dependsOn(verifyShadowJar)
}
