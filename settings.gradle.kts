rootProject.name = "noveltea"

// Only the Spring Boot API is a Gradle project. The compile worker is a plain
// npm/TypeScript package under worker/ and is built by npm, not Gradle.
include("api")
