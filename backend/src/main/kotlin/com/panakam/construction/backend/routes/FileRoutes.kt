package com.panakam.construction.backend.routes

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.*
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.sdk.kotlin.services.s3.presigners.presignPutObject
import com.panakam.construction.backend.db.DatabaseFactory.dbQuery
import com.panakam.construction.backend.db.ProjectFiles
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

fun Route.fileRoutes(s3Client: S3Client, s3PresignClient: S3Client) {
    val bucketName = System.getenv("S3_BUCKET") ?: "construction-files"


    route("/projects/{projectId}/files") {

        // GET /projects/{projectId}/files?folder=photos
        get {
            val projectId = call.parameters["projectId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val folder = call.request.queryParameters["folder"]

            val files = dbQuery {
                var query = ProjectFiles.selectAll().where { ProjectFiles.projectId eq projectId }
                if (folder != null) query = query.andWhere { ProjectFiles.folder eq folder }
                query.orderBy(ProjectFiles.uploadedAt, SortOrder.DESC).map { it.toFileMap() }
            }
            call.respond(HttpStatusCode.OK, files)
        }

        // POST /projects/{projectId}/files/upload-url
        post("/upload-url") {
            val projectId = call.parameters["projectId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))

            val json        = Json.parseToJsonElement(call.receiveText()).jsonObject
            val fileName    = json.str("fileName").ifBlank {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing fileName"))
            }
            val folder      = json.str("folder", "documents")
            val contentType = json.str("contentType", "application/octet-stream")

            val fileId = UUID.randomUUID().toString()
            val s3Key  = "projects/$projectId/$folder/$fileId-$fileName"

            val presigned = s3PresignClient.presignPutObject(PutObjectRequest {
                bucket = bucketName; key = s3Key
            }, 15.minutes)

            dbQuery {
                ProjectFiles.insert {
                    it[ProjectFiles.fileId]      = fileId
                    it[ProjectFiles.projectId]   = projectId
                    it[ProjectFiles.fileName]    = fileName
                    it[ProjectFiles.folder]      = folder
                    it[ProjectFiles.s3Key]       = s3Key
                    it[ProjectFiles.contentType] = contentType
                    it[ProjectFiles.uploadedAt]  = System.currentTimeMillis()
                }
            }

            call.respond(HttpStatusCode.OK, mapOf(
                "fileId"    to fileId,
                "uploadUrl" to presigned.url.toString(),
                "s3Key"     to s3Key
            ))
        }

        // GET /projects/{projectId}/files/download-url?fileId=...
        get("/download-url") {
            val projectId = call.parameters["projectId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val fileId = call.request.queryParameters["fileId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing fileId"))

            val s3Key = dbQuery {
                ProjectFiles.selectAll().where {
                    (ProjectFiles.projectId eq projectId) and (ProjectFiles.fileId eq fileId)
                }.singleOrNull()?.get(ProjectFiles.s3Key)
            } ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "File not found"))

            val presigned = s3PresignClient.presignGetObject(GetObjectRequest {
                bucket = bucketName; key = s3Key
            }, 30.minutes)

            call.respond(HttpStatusCode.OK, mapOf("downloadUrl" to presigned.url.toString()))
        }

        // DELETE /projects/{projectId}/files/{fileId}
        delete("/{fileId}") {
            val projectId = call.parameters["projectId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val fileId = call.parameters["fileId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing fileId"))

            val s3Key = dbQuery {
                ProjectFiles.selectAll().where {
                    (ProjectFiles.projectId eq projectId) and (ProjectFiles.fileId eq fileId)
                }.singleOrNull()?.get(ProjectFiles.s3Key)
            }
            if (s3Key != null) {
                s3Client.deleteObject(DeleteObjectRequest { bucket = bucketName; key = s3Key })
            }
            dbQuery {
                ProjectFiles.deleteWhere {
                    (ProjectFiles.projectId eq projectId) and (ProjectFiles.fileId eq fileId)
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("message" to "File deleted"))
        }
    }
}

private fun ResultRow.toFileMap() = mapOf(
    "fileId"      to this[ProjectFiles.fileId],
    "projectId"   to this[ProjectFiles.projectId],
    "fileName"    to this[ProjectFiles.fileName],
    "folder"      to this[ProjectFiles.folder],
    "s3Key"       to this[ProjectFiles.s3Key],
    "contentType" to this[ProjectFiles.contentType],
    "uploadedAt"  to this[ProjectFiles.uploadedAt].toString()
)
