plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("io.ktor.plugin") version "2.3.12"
    application
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
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

    // JWT authentication — verifies the caller's identity server-side (userId + role
    // claims signed by us at login) instead of trusting client-supplied "createdBy"/
    // "changedBy"/"adminId" fields for audit trails and authorization.
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt:$ktorVersion")

    // Ktor client — used to call the PaddleOCR sidecar microservice over HTTP
    // (PaddleOCR has no JVM/Kotlin binding; see ocr-service/main.py).
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")

    // AWS SDK for Kotlin – S3 only (MinIO file storage)
    implementation("aws.sdk.kotlin:s3:1.3.99")
    implementation("aws.sdk.kotlin:textract:1.3.99")

    // Exposed ORM (PostgreSQL / SQL)
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")

    // PostgreSQL JDBC driver — 42.7.12+ required to fix 3 known HIGH-severity CVEs in 42.7.4
    // (SCRAM channel-binding downgrade / auth bypass, PBKDF2 CPU-exhaustion DoS — see
    // CVE-2025-49146, CVE-2026-42198, CVE-2026-54291).
    implementation("org.postgresql:postgresql:42.7.12")

    // HikariCP connection pool
    implementation("com.zaxxer:HikariCP:5.1.0")

    // Apache POI – Excel (.xls / .xlsx) parsing for bulk unit import
    // 5.4.0+ required to fix CVE-2025-31672 (duplicate zip-entry-name input validation issue
    // in OOXML parsing).
    implementation("org.apache.poi:poi:5.4.0")
    implementation("org.apache.poi:poi-ooxml:5.4.0")

    // BCrypt – password hashing for user credentials
    implementation("org.mindrot:jbcrypt:0.4")

    // Apache PDFBox – PDF text extraction for payment receipt parsing
    implementation("org.apache.pdfbox:pdfbox:3.0.3")

    // Tess4J – free, local, offline OCR for image receipts (no cloud cost).
    // Requires the tesseract-ocr native package installed on the runtime image.
    implementation("net.sourceforge.tess4j:tess4j:5.11.0")

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
