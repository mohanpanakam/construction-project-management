package com.panakam.construction.backend.routes

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

fun Route.financialRoutes(dynamoDbClient: DynamoDbClient) {
    val tableName = "Financials"

    route("/financials") {

        // GET /financials/{projectId}
        get("/{projectId}") {
            val projectId = call.parameters["projectId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))

            val response = dynamoDbClient.query(QueryRequest {
                this.tableName = tableName
                keyConditionExpression = "projectId = :pid"
                expressionAttributeValues = mapOf(":pid" to AttributeValue.S(projectId))
            })

            val items = response.items?.map { it.mapValues { e -> e.value.asS() } } ?: emptyList()
            call.respond(HttpStatusCode.OK, items)
        }

        // POST /financials
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
            call.respond(HttpStatusCode.Created, mapOf("message" to "Financial record added", "projectId" to projectId))
        }

        // DELETE /financials/{projectId}/{recordId}
        delete("/{projectId}/{recordId}") {
            val projectId = call.parameters["projectId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing projectId"))
            val recordId = call.parameters["recordId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing recordId"))

            dynamoDbClient.deleteItem(DeleteItemRequest {
                this.tableName = tableName
                key = mapOf(
                    "projectId" to AttributeValue.S(projectId),
                    "recordId" to AttributeValue.S(recordId)
                )
            })
            call.respond(HttpStatusCode.OK, mapOf("message" to "Financial record deleted"))
        }
    }
}

