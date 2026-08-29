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

val ktorVersion       = "2.3.12"
val exposedVersion    = "0.55.0"
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

    // AWS SDK for Kotlin – S3 only (MinIO file storage)
    implementation("aws.sdk.kotlin:s3:1.3.99")

    // Exposed ORM (PostgreSQL / SQL)
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")

    // PostgreSQL JDBC driver
    implementation("org.postgresql:postgresql:42.7.4")

    // HikariCP connection pool
    implementation("com.zaxxer:HikariCP:5.1.0")

    // Apache POI – Excel (.xls / .xlsx) parsing for bulk unit import
    implementation("org.apache.poi:poi:5.3.0")
    implementation("org.apache.poi:poi-ooxml:5.3.0")

    // BCrypt – password hashing for user credentials
    implementation("org.mindrot:jbcrypt:0.4")

    // Apache PDFBox – PDF text extraction for payment receipt parsing
    implementation("org.apache.pdfbox:pdfbox:3.0.3")

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
