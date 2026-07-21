import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":networkpoints-api"))

    compileOnly(libs.paper.api)

    implementation(libs.craftkit.database)
    implementation(libs.boosted.yaml)

    testImplementation(project(":networkpoints-api"))
    testImplementation(libs.paper.api)
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

    exclude("LICENSE")
    exclude("META-INF/LICENSE")
    exclude("META-INF/LICENSE.txt")
    exclude("META-INF/NOTICE")

    relocate("dev.dejvokep.boostedyaml", "com.stephanofer.networkpoints.libs.boostedyaml")
    relocate("com.hera.craftkit", "com.stephanofer.networkpoints.libs.craftkit")
    relocate("com.zaxxer.hikari", "com.stephanofer.networkpoints.libs.hikari")
    relocate("org.flywaydb", "com.stephanofer.networkpoints.libs.flyway")
    relocate("com.mysql", "com.stephanofer.networkpoints.libs.mysql")
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
                "db/migration/V1__create_networkpoints.sql"
            )
            check(entries.containsAll(required)) {
                "Shadow JAR is missing: ${required - entries}"
            }
            check(entries.none { it.startsWith("dev/dejvokep/boostedyaml/") }) {
                "Shadow JAR contains unrelocated BoostedYAML classes"
            }
            val forbidden = listOf(
                "com/github/benmanes/caffeine/",
                "com/hera/craftkit/",
                "io/papermc/",
                "io/lettuce/",
                "net/kyori/",
                "org/bukkit/",
                "org/incendo/cloud/",
                "com/zaxxer/hikari/",
                "org/flywaydb/",
                "com/mysql/"
            )
            check(entries.none { entry -> forbidden.any(entry::startsWith) }) {
                "Shadow JAR contains dependencies not enabled in block 1"
            }
        }
    }
}

tasks.build {
    dependsOn(verifyShadowJar)
}
