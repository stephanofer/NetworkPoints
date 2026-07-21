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

publishing {
    publications {
        create<MavenPublication>("api") {
            from(components["java"])
            artifactId = "networkpoints-api"
        }
    }
}
