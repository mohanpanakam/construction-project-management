plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("io.ktor.plugin") version "2.3.12"
    application
}

group = "com.panakam.construction"
version = "1.0.0"

application {
    mainClass.set("com.panakam.construction.backend.ApplicationKt")
}

ktor {
    fatJar {
        archiveFileName.set("construction-backend.jar")
    }
}

repositories {
    mavenCentral()
}

val ktorVersion = "2.3.12"
val awsSdkVersion = "1.3.99"
val coroutinesVersion = "1.8.1"

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")

    // AWS SDK for Kotlin – DynamoDB + S3
    implementation("aws.sdk.kotlin:dynamodb:$awsSdkVersion")
    implementation("aws.sdk.kotlin:s3:$awsSdkVersion")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Test
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

