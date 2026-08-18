plugins {
    java
}

allprojects {
    group = "com.noveltea"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    // Pinned so CI and every contributor compile against the same JDK regardless
    // of what happens to be on PATH. Gradle downloads it if absent.
    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
