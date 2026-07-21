plugins {
    `maven-publish`
}

dependencies {
    compileOnlyApi(libs.paper.api)

    testImplementation(libs.paper.api)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

java {
    withJavadocJar()
}

tasks.jar {
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("target-api"))
}

tasks.clean {
    delete(rootProject.layout.projectDirectory.dir("target-api"))
}

publishing {
    publications {
        create<MavenPublication>("api") {
            from(components["java"])
            artifactId = "networkpoints-api"
        }
    }
}
