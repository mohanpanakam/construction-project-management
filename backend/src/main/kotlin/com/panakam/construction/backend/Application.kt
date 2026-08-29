package com.panakam.construction.backend

import aws.sdk.kotlin.services.s3.S3Client
import aws.smithy.kotlin.runtime.net.url.Url
import com.panakam.construction.backend.db.DatabaseFactory
import com.panakam.construction.backend.routes.authRoutes
import com.panakam.construction.backend.routes.unitsRoutes
import com.panakam.construction.backend.routes.fileRoutes
import com.panakam.construction.backend.routes.financialRoutes
import com.panakam.construction.backend.routes.inventoryRoutes
import com.panakam.construction.backend.routes.projectRoutes
import com.panakam.construction.backend.routes.customerRoutes
import com.panakam.construction.backend.routes.paymentRoutes
import com.panakam.construction.backend.routes.auditRoutes
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

fun main() {
    embeddedServer(Netty, port = System.getenv("PORT")?.toInt() ?: 8080, module = Application::module).start(wait = true)
}

fun Application.module() {
    val s3Endpoint       = System.getenv("S3_ENDPOINT")   ?: "http://minio:9000"
    val s3PublicEndpoint = System.getenv("PUBLIC_S3_URL") ?: s3Endpoint
    val awsRegion        = System.getenv("AWS_REGION")    ?: "us-east-1"

    // ── PostgreSQL via Exposed ─────────────────────────────────────────────
    DatabaseFactory.init()

    // ── S3 / MinIO clients ────────────────────────────────────────────────────
    // Internal client: used for delete / bucket ops (reaches minio container directly)
    val s3Client = S3Client {
        region         = awsRegion
        endpointUrl    = Url.parse(s3Endpoint)
        forcePathStyle = true
    }
    // Presign client: uses the LAN-accessible public URL so Android devices can
    // PUT/GET directly.  Signature must be computed against the host the device hits.
    val s3PresignClient = S3Client {
        region         = awsRegion
        endpointUrl    = Url.parse(s3PublicEndpoint)
        forcePathStyle = true
    }

    install(ContentNegotiation) {
        json(Json { prettyPrint = true; isLenient = true; ignoreUnknownKeys = true })
    }

    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
    }

    install(CallLogging)

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respondText(
                text = """{"error": "${cause.message ?: "Internal Server Error"}"}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.InternalServerError
            )
        }
    }

    routing {
        get("/health") {
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        }
        authRoutes()
        projectRoutes()
        inventoryRoutes()
        financialRoutes()
        unitsRoutes()
        fileRoutes(s3Client, s3PresignClient)
        customerRoutes()
        paymentRoutes(s3Client, s3PresignClient)
        auditRoutes()
    }
}
