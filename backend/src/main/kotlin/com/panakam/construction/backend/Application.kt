package com.panakam.construction.backend

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.textract.TextractClient
import aws.smithy.kotlin.runtime.net.url.Url
import com.panakam.construction.backend.db.DatabaseFactory
import com.panakam.construction.backend.service.OcrService
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import com.panakam.construction.backend.routes.authRoutes
import com.panakam.construction.backend.routes.unitsRoutes
import com.panakam.construction.backend.routes.fileRoutes
import com.panakam.construction.backend.routes.financialRoutes
import com.panakam.construction.backend.routes.inventoryRoutes
import com.panakam.construction.backend.routes.projectRoutes
import com.panakam.construction.backend.routes.customerRoutes
import com.panakam.construction.backend.routes.paymentRoutes
import com.panakam.construction.backend.routes.auditRoutes
import com.panakam.construction.backend.routes.collectionRoutes
import com.panakam.construction.backend.routes.suspenseRoutes
import com.panakam.construction.backend.routes.salesRepRoutes
import com.panakam.construction.backend.routes.kycRoutes
import com.panakam.construction.backend.routes.agreementRoutes
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
    // S3_ENDPOINT: only set this for local dev (MinIO). Leave UNSET in production so the
    // AWS SDK talks to real AWS S3 using its default regional endpoint.
    val s3Endpoint       = System.getenv("S3_ENDPOINT")
    val s3PublicEndpoint = System.getenv("PUBLIC_S3_URL") ?: s3Endpoint
    val awsRegion        = System.getenv("AWS_REGION")    ?: "us-east-1"
    val ocrProvider      = System.getenv("OCR_PROVIDER")  ?: "NONE"
    // Base URL of the PaddleOCR sidecar microservice (see ocr-service/main.py), only used
    // when OCR_PROVIDER=PADDLE. Defaults to the Docker Compose service name/port.
    val paddleOcrUrl     = System.getenv("PADDLE_OCR_URL") ?: "http://ocr-service:8000"
    // MinIO requires path-style URLs (http://host:port/bucket/key); real AWS S3 uses
    // virtual-hosted style (https://bucket.s3.region.amazonaws.com/key) by default.
    // Path-style is auto-enabled when a custom S3_ENDPOINT is set (i.e. MinIO), unless
    // explicitly overridden via S3_FORCE_PATH_STYLE.
    val forcePathStyle = System.getenv("S3_FORCE_PATH_STYLE")?.toBooleanStrictOrNull()
        ?: (s3Endpoint != null)

    // ── PostgreSQL via Exposed ─────────────────────────────────────────────
    DatabaseFactory.init()

    // ── S3 / MinIO clients ────────────────────────────────────────────────────
    // Internal client: used for delete / bucket ops.
    val s3Client = S3Client {
        region = awsRegion
        if (s3Endpoint != null) endpointUrl = Url.parse(s3Endpoint)
        this.forcePathStyle = forcePathStyle
    }
    // Presign client: uses the publicly-reachable URL so Android devices can PUT/GET
    // directly. Signature must be computed against the host the device hits.
    val s3PresignClient = S3Client {
        region = awsRegion
        if (s3PublicEndpoint != null) endpointUrl = Url.parse(s3PublicEndpoint)
        this.forcePathStyle = forcePathStyle
    }
    val textractClient: TextractClient? = if (ocrProvider.equals("TEXTRACT", ignoreCase = true)) {
        TextractClient { region = awsRegion }
    } else null

    // HTTP client for calling the PaddleOCR sidecar microservice (no native JVM client
    // needed — PaddleOCR has no Java/Kotlin binding, so it runs as its own container).
    val ocrHttpClient = HttpClient(CIO)

    // ── OCR self-test ──────────────────────────────────────────────────────
    // Runs once at startup and logs a clear pass/fail so you can tell from
    // `docker logs construction-backend` (without waiting for a real upload)
    // whether receipt-image OCR will actually work on this deployment — this
    // is the #1 way to catch "tesseract isn't installed on this container".
    val (tesseractAvailable, tesseractStatusMsg) =
        if (ocrProvider.equals("NONE", ignoreCase = true)) OcrService.checkTesseractAvailable()
        else true to "skipped (OCR_PROVIDER=$ocrProvider)"
    if (tesseractAvailable) {
        println("[OCR] Tesseract self-test passed — image receipt OCR is available ($tesseractStatusMsg).")
    } else {
        println("[OCR] Tesseract self-test FAILED — image receipt OCR will NOT work: $tesseractStatusMsg")
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
            call.respondText(
                """{"status":"ok","ocr":{"provider":"$ocrProvider","available":$tesseractAvailable,"detail":"${tesseractStatusMsg.replace("\"", "'")}"}}""",
                ContentType.Application.Json
            )
        }
        authRoutes()
        projectRoutes()
        inventoryRoutes()
        financialRoutes()
        unitsRoutes()
        fileRoutes(s3Client, s3PresignClient)
        customerRoutes()
        paymentRoutes(s3Client, s3PresignClient, textractClient, ocrProvider, ocrHttpClient, paddleOcrUrl)
        auditRoutes()
        collectionRoutes()
        suspenseRoutes()
        salesRepRoutes()
        kycRoutes(s3Client, s3PresignClient, textractClient, ocrProvider, ocrHttpClient, paddleOcrUrl)
        agreementRoutes(s3Client, s3PresignClient)
    }
}
