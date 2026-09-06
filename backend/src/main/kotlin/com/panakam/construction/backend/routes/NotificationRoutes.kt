package com.panakam.construction.backend.routes

import com.panakam.construction.backend.db.Customers
import com.panakam.construction.backend.db.DatabaseFactory.dbQuery
import com.panakam.construction.backend.db.Notifications
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList

/**
 * Notification inbox — one device may qualify as BOTH a staff user (userId)
 * AND a customer (customerId / phone, possibly across several units), so every
 * endpoint here accepts all three identifiers and unions whatever matches.
 */
fun Route.notificationRoutes() {

    route("/notifications") {

        // GET /notifications?userId=&customerId=&phone=
        get {
            val userId     = call.request.queryParameters["userId"]?.takeIf { it.isNotBlank() }
            val customerId = call.request.queryParameters["customerId"]?.takeIf { it.isNotBlank() }
            val phone      = call.request.queryParameters["phone"]?.takeIf { it.isNotBlank() }

            val (userIds, customerIds) = resolveRecipientIds(userId, customerId, phone)
            if (userIds.isEmpty() && customerIds.isEmpty()) {
                return@get call.respond(HttpStatusCode.OK, emptyList<Map<String, Any>>())
            }
            val list = dbQuery {
                Notifications.selectAll()
                    .where { recipientCondition(userIds, customerIds) }
                    .orderBy(Notifications.createdAt, SortOrder.DESC)
                    .limit(300)
                    .map { it.toNotificationMap() }
            }
            call.respond(HttpStatusCode.OK, list)
        }

        // GET /notifications/unread-count?userId=&customerId=&phone=
        get("/unread-count") {
            val userId     = call.request.queryParameters["userId"]?.takeIf { it.isNotBlank() }
            val customerId = call.request.queryParameters["customerId"]?.takeIf { it.isNotBlank() }
            val phone      = call.request.queryParameters["phone"]?.takeIf { it.isNotBlank() }

            val (userIds, customerIds) = resolveRecipientIds(userId, customerId, phone)
            val count = if (userIds.isEmpty() && customerIds.isEmpty()) 0L else dbQuery {
                Notifications.selectAll()
                    .where { recipientCondition(userIds, customerIds) and (Notifications.isRead eq false) }
                    .count()
            }
            call.respond(HttpStatusCode.OK, mapOf("unread" to count))
        }

        // PUT /notifications/{id}/read
        put("/{id}/read") {
            val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            dbQuery {
                Notifications.update({ Notifications.notificationId eq id }) {
                    it[Notifications.isRead] = true
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("message" to "Marked read"))
        }

        // PUT /notifications/read-all   body: { "userId": "", "customerId": "", "phone": "" }
        put("/read-all") {
            val json = Json.parseToJsonElement(call.receiveText()).jsonObject
            val userId     = json.str("userId").takeIf { it.isNotBlank() }
            val customerId = json.str("customerId").takeIf { it.isNotBlank() }
            val phone      = json.str("phone").takeIf { it.isNotBlank() }

            val (userIds, customerIds) = resolveRecipientIds(userId, customerId, phone)
            if (userIds.isNotEmpty() || customerIds.isNotEmpty()) {
                dbQuery {
                    Notifications.update({ recipientCondition(userIds, customerIds) }) {
                        it[Notifications.isRead] = true
                    }
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("message" to "All marked read"))
        }
    }
}

/** Resolves the (userIds, customerIds) a device should see notifications for.
 *  [phone] expands to every Customers row sharing that phone number (multi-unit
 *  customer portal login), unioned with a directly-known [customerId]. */
private suspend fun resolveRecipientIds(
    userId: String?, customerId: String?, phone: String?
): Pair<List<String>, List<String>> {
    val userIds = listOfNotNull(userId?.takeIf { it.isNotBlank() })
    val customerIds = mutableListOf<String>()
    if (!customerId.isNullOrBlank()) customerIds += customerId
    if (!phone.isNullOrBlank()) {
        val byPhone = dbQuery {
            Customers.selectAll().where { Customers.phone eq phone }.map { it[Customers.customerId] }
        }
        customerIds += byPhone
    }
    return userIds to customerIds.distinct()
}

private fun recipientCondition(userIds: List<String>, customerIds: List<String>): Op<Boolean> {
    val conditions = mutableListOf<Op<Boolean>>()
    if (userIds.isNotEmpty()) {
        conditions += (Notifications.recipientType eq "USER") and (Notifications.recipientId inList userIds)
    }
    if (customerIds.isNotEmpty()) {
        conditions += (Notifications.recipientType eq "CUSTOMER") and (Notifications.recipientId inList customerIds)
    }
    // Guaranteed non-match fallback (recipientId is never blank) — callers should
    // avoid invoking this when both lists are empty, but this keeps it total/safe.
    return conditions.reduceOrNull { a, b -> a or b } ?: (Notifications.recipientId eq "")
}

private fun ResultRow.toNotificationMap() = mapOf(
    "notificationId" to this[Notifications.notificationId],
    "recipientType"  to this[Notifications.recipientType],
    "recipientId"    to this[Notifications.recipientId],
    "type"           to this[Notifications.type],
    "title"          to this[Notifications.title],
    "body"           to this[Notifications.body],
    "projectId"      to this[Notifications.projectId],
    "unitId"         to this[Notifications.unitId],
    "paymentId"      to this[Notifications.paymentId],
    "isRead"         to this[Notifications.isRead].toString(),
    "createdAt"      to this[Notifications.createdAt].toString()
)

