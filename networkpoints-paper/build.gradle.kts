plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":networkpoints-api"))

    compileOnly(libs.paper.api)
    compileOnly(libs.network.player.settings)
    compileOnly(libs.luckperms.api)
    compileOnly(libs.placeholder.api)
    compileOnly(libs.network.boosters.api)

    implementation(libs.boosted.yaml)
    implementation(libs.caffeine)
    implementation(libs.cloud.paper)
    implementation(libs.craftkit.database)
    implementation(libs.craftkit.redis)

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

tasks.shadowJar {
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    exclude("LICENSE", "META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/NOTICE", "META-INF/io.netty.versions.properties")

    relocate("com.hera.craftkit", "com.stephanofer.networkpoints.libs.craftkit")
    relocate("com.zaxxer", "com.stephanofer.networkpoints.libs.hikari")
    relocate("org.flywaydb", "com.stephanofer.networkpoints.libs.flyway")
    relocate("tools.jackson", "com.stephanofer.networkpoints.libs.jackson3")
    relocate("com.fasterxml.jackson", "com.stephanofer.networkpoints.libs.jackson")
    relocate("com.mysql", "com.stephanofer.networkpoints.libs.mysql")
    relocate("com.google.protobuf", "com.stephanofer.networkpoints.libs.protobuf")
    relocate("io.lettuce", "com.stephanofer.networkpoints.libs.lettuce")
    relocate("redis.clients.authentication", "com.stephanofer.networkpoints.libs.redisAuthx")
    relocate("io.netty", "com.stephanofer.networkpoints.libs.netty")
    relocate("reactor", "com.stephanofer.networkpoints.libs.reactor")
    relocate("org.reactivestreams", "com.stephanofer.networkpoints.libs.reactiveStreams")
    relocate("dev.dejvokep.boostedyaml", "com.stephanofer.networkpoints.libs.boostedyaml")
    relocate("com.github.benmanes.caffeine", "com.stephanofer.networkpoints.libs.caffeine")
    relocate("org.incendo.cloud", "com.stephanofer.networkpoints.libs.cloud")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
