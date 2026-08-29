package com.panakam.construction.backend.routes

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

fun Route.projectRoutes(dynamoDbClient: DynamoDbClient) {
    val tableName = "Projects"

    route("/projects") {

        // GET /projects  – list all
        get {
            val response = dynamoDbClient.scan(
                aws.sdk.kotlin.services.dynamodb.model.ScanRequest {
                    this.tableName = tableName
                }
            )
            val items = response.items?.map { it.mapValues { e -> e.value.asS() } } ?: emptyList()
            call.respond(HttpStatusCode.OK, items)
        }

        // GET /projects/{projectId}
        get("/{projectId}") {
            val projectId = call.parameters["projectId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))

            val response = dynamoDbClient.getItem(GetItemRequest {
                this.tableName = tableName
                key = mapOf("projectId" to AttributeValue.S(projectId))
            })

            if (response.item == null || response.item!!.isEmpty()) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Project not found"))
            } else {
                val result = response.item!!.mapValues { it.value.asS() }
                call.respond(HttpStatusCode.OK, result)
            }
        }

        // POST /projects
        post {
            val body = call.receiveText()
            val json = Json.parseToJsonElement(body).jsonObject
            val projectId = json["projectId"]?.jsonPrimitive?.content
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))

            val item = json.mapValues { AttributeValue.S(it.value.jsonPrimitive.content) }

            dynamoDbClient.putItem(PutItemRequest {
                this.tableName = tableName
                this.item = item
            })
            call.respond(HttpStatusCode.Created, mapOf("message" to "Project created", "projectId" to projectId))
        }

        // PUT /projects/{projectId}
        put("/{projectId}") {
            val projectId = call.parameters["projectId"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))

            val body = call.receiveText()
            val json = Json.parseToJsonElement(body).jsonObject
            val item = json.toMutableMap().apply {
                put("projectId", JsonPrimitive(projectId))
            }.mapValues { AttributeValue.S(it.value.jsonPrimitive.content) }

            dynamoDbClient.putItem(PutItemRequest {
                this.tableName = tableName
                this.item = item
            })
            call.respond(HttpStatusCode.OK, mapOf("message" to "Project updated", "projectId" to projectId))
        }

        // DELETE /projects/{projectId}
        delete("/{projectId}") {
            val projectId = call.parameters["projectId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))

            dynamoDbClient.deleteItem(DeleteItemRequest {
                this.tableName = tableName
                key = mapOf("projectId" to AttributeValue.S(projectId))
            })
            call.respond(HttpStatusCode.OK, mapOf("message" to "Project deleted", "projectId" to projectId))
        }
    }
}

