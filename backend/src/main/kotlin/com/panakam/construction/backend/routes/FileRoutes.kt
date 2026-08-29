package com.panakam.construction.backend.routes

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.*
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.sdk.kotlin.services.s3.presigners.presignPutObject
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

fun Route.fileRoutes(dynamoDbClient: DynamoDbClient, s3Client: S3Client) {
    val fileTable  = "ProjectFiles"
    val bucketName = System.getenv("S3_BUCKET") ?: "construction-files"

    route("/projects/{projectId}/files") {

        // ── GET /projects/{projectId}/files?folder=photos ──────────────────
        get {
            val projectId = call.parameters["projectId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val folder = call.request.queryParameters["folder"]

            val resp = dynamoDbClient.query(QueryRequest {
                tableName = fileTable
                keyConditionExpression = "projectId = :pid"
                expressionAttributeValues = mapOf(":pid" to AttributeValue.S(projectId))
            })
            var items = resp.items?.map { it.mapValues { e -> e.value.asS() } } ?: emptyList()
            if (folder != null) items = items.filter { it["folder"] == folder }
            call.respond(HttpStatusCode.OK, items)
        }

        // ── POST /projects/{projectId}/files/upload-url ────────────────────
        // Body: { "fileName": "photo.jpg", "folder": "photos", "contentType": "image/jpeg" }
        // Returns: { "fileId": "...", "uploadUrl": "...", "s3Key": "..." }
        post("/upload-url") {
            val projectId = call.parameters["projectId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))

            val body  = call.receiveText()
            val json  = Json.parseToJsonElement(body).jsonObject
            val fileName    = json["fileName"]?.jsonPrimitive?.content
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing fileName"))
            val folder      = json["folder"]?.jsonPrimitive?.content ?: "documents"
            val contentType = json["contentType"]?.jsonPrimitive?.content ?: "application/octet-stream"

            val fileId = UUID.randomUUID().toString()
            val s3Key  = "projects/$projectId/$folder/$fileId-$fileName"

            // Generate presigned PUT URL (valid 15 min)
            val presigned = s3Client.presignPutObject(PutObjectRequest {
                bucket = bucketName
                key    = s3Key
            }, 15.minutes)

            // Save metadata to DynamoDB
            dynamoDbClient.putItem(PutItemRequest {
                tableName = fileTable
                item = mapOf(
                    "projectId"   to AttributeValue.S(projectId),
                    "fileId"      to AttributeValue.S(fileId),
                    "fileName"    to AttributeValue.S(fileName),
                    "folder"      to AttributeValue.S(folder),
                    "s3Key"       to AttributeValue.S(s3Key),
                    "contentType" to AttributeValue.S(contentType),
                    "uploadedAt"  to AttributeValue.S(System.currentTimeMillis().toString())
                )
            })

            call.respond(HttpStatusCode.OK, mapOf(
                "fileId"    to fileId,
                "uploadUrl" to presigned.url.toString(),
                "s3Key"     to s3Key
            ))
        }

        // ── GET /projects/{projectId}/files/download-url?fileId=... ───────
        get("/download-url") {
            val projectId = call.parameters["projectId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val fileId = call.request.queryParameters["fileId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing fileId"))

            val resp = dynamoDbClient.getItem(GetItemRequest {
                tableName = fileTable
                key = mapOf(
                    "projectId" to AttributeValue.S(projectId),
                    "fileId"    to AttributeValue.S(fileId)
                )
            })
            val s3Key = resp.item?.get("s3Key")?.asS()
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "File not found"))

            val presigned = s3Client.presignGetObject(GetObjectRequest {
                bucket = bucketName
                key    = s3Key
            }, 30.minutes)

            call.respond(HttpStatusCode.OK, mapOf("downloadUrl" to presigned.url.toString()))
        }

        // ── DELETE /projects/{projectId}/files/{fileId} ───────────────────
        delete("/{fileId}") {
            val projectId = call.parameters["projectId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val fileId = call.parameters["fileId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing fileId"))

            // Fetch metadata to get S3 key
            val resp = dynamoDbClient.getItem(GetItemRequest {
                tableName = fileTable
                key = mapOf(
                    "projectId" to AttributeValue.S(projectId),
                    "fileId"    to AttributeValue.S(fileId)
                )
            })
            val s3Key = resp.item?.get("s3Key")?.asS()
            if (s3Key != null) {
                s3Client.deleteObject(DeleteObjectRequest {
                    bucket = bucketName
                    key    = s3Key
                })
            }

            dynamoDbClient.deleteItem(DeleteItemRequest {
                tableName = fileTable
                key = mapOf(
                    "projectId" to AttributeValue.S(projectId),
                    "fileId"    to AttributeValue.S(fileId)
                )
            })
            call.respond(HttpStatusCode.OK, mapOf("message" to "File deleted"))
        }
    }
}

