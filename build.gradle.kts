plugins {
    kotlin("jvm") version "1.9.23"
    id("com.github.johnrengelman.shadow") version "7.0.0"
    kotlin("plugin.serialization") version "1.9.22"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // HTTP Requests
    implementation("org.jetbrains.exposed:exposed-core:0.49.0") // SQL
    implementation("org.jetbrains.exposed:exposed-jdbc:0.49.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.49.0")
    implementation("org.xerial:sqlite-jdbc:3.45.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3") // JSON
    implementation("org.json:json:20240303") // More JSON
    implementation("com.twitter:twitter-api-java-sdk:2.0.3") // Twitter v.2
    implementation("org.twitter4j:twitter4j-core:4.1.2") // Twitter v.1.1
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<Jar> {
    archiveFileName = "twitter-server.jar"
    manifest {
        attributes["Main-Class"] = "org.example.MainKt"
    }
}

kotlin {
    jvmToolchain(17)
}