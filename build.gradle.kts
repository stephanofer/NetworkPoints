plugins {
    alias(libs.plugins.shadow) apply false
}

subprojects {
    apply(plugin = "java-library")

    repositories {
        mavenLocal()
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(25)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
