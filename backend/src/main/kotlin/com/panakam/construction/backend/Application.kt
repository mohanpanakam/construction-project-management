package com.panakam.construction.backend

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.s3.S3Client
import aws.smithy.kotlin.runtime.net.url.Url
import com.panakam.construction.backend.routes.fileRoutes
import com.panakam.construction.backend.routes.financialRoutes
import com.panakam.construction.backend.routes.inventoryRoutes
import com.panakam.construction.backend.routes.projectRoutes
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
    val dynamoEndpoint = System.getenv("DYNAMO_ENDPOINT") ?: "http://dynamodb-local:8000"
    val s3Endpoint     = System.getenv("S3_ENDPOINT")     ?: "http://minio:9000"
    val awsRegion      = System.getenv("AWS_REGION")      ?: "us-east-1"

    val dynamoDbClient = DynamoDbClient {
        region      = awsRegion
        endpointUrl = Url.parse(dynamoEndpoint)
    }

    val s3Client = S3Client {
        region         = awsRegion
        endpointUrl    = Url.parse(s3Endpoint)
        forcePathStyle = true   // required for MinIO / path-style S3
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
        projectRoutes(dynamoDbClient)
        inventoryRoutes(dynamoDbClient)
        financialRoutes(dynamoDbClient)
        fileRoutes(dynamoDbClient, s3Client)
    }
}
