package com.panakam.construction.backend.service

import com.panakam.construction.backend.db.DatabaseFactory.dbQuery
import com.panakam.construction.backend.db.Notifications
import com.panakam.construction.backend.db.Users
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import java.util.UUID

/**
 * Fans out payment lifecycle events into per-recipient Notification rows so
 * every device (customer OR staff) only has to query "what's for me" and can
 * mark its own copy read independently.
 *
 * Recipients for the two events we currently notify on:
 *  - PAYMENT CREATED  -> the customer (confirmation) + every Admin/Auditor (needs review)
 *  - PAYMENT AUDITED  -> the customer (outcome) + every OTHER Admin/Auditor (stays in sync),
 *                        excluding whoever just performed the audit
 */
object NotificationService {

    private suspend fun insert(
        recipientType: String, recipientId: String, type: String,
        title: String, body: String, projectId: String = "",
        unitId: String = "", paymentId: String = ""
    ) {
        if (recipientId.isBlank()) return
        dbQuery {
            Notifications.insert {
                it[Notifications.notificationId] = UUID.randomUUID().toString()
                it[Notifications.recipientType]   = recipientType
                it[Notifications.recipientId]     = recipientId
                it[Notifications.type]            = type
                it[Notifications.title]           = title
                it[Notifications.body]            = body
                it[Notifications.projectId]       = projectId
                it[Notifications.unitId]          = unitId
                it[Notifications.paymentId]       = paymentId
                it[Notifications.isRead]          = false
                it[Notifications.createdAt]       = System.currentTimeMillis()
            }
        }
    }

    /** All staff userIds with role ADMIN or AUDITOR — the roles responsible for
     *  reviewing/auditing payments — excluding [excludeUserId] (the actor who
     *  triggered this event, so nobody gets notified about their own action). */
    private suspend fun adminAuditorUserIds(excludeUserId: String = ""): List<String> = dbQuery {
        Users.selectAll()
            .map { it[Users.userId] to it[Users.role] }
            .filter { (uid, role) -> (role == "ADMIN" || role == "AUDITOR") && uid != excludeUserId }
            .map { it.first }
    }

    /** New payment recorded (customer self-service confirm OR staff/sales-rep add). */
    suspend fun notifyPaymentCreated(
        paymentId: String, customerId: String, customerName: String,
        projectId: String, unitId: String, unitLabel: String, amount: Double,
        createdByUserId: String
    ) {
        val amountStr = "₹%,.2f".format(amount)
        val label = unitLabel.ifBlank { "your unit" }

        insert(
            "CUSTOMER", customerId, "PAYMENT_CREATED",
            "Payment recorded",
            "Your payment of $amountStr for unit $label has been recorded and is pending audit.",
            projectId, unitId, paymentId
        )

        adminAuditorUserIds(createdByUserId).forEach { uid ->
            insert(
                "USER", uid, "PAYMENT_CREATED",
                "New payment needs audit",
                "${customerName.ifBlank { "A customer" }} submitted a payment of $amountStr for unit $label — pending review.",
                projectId, unitId, paymentId
            )
        }
    }

    /** Payment audit status changed to AUDITED / REJECTED / PENDING. */
    suspend fun notifyPaymentAudited(
        paymentId: String, customerId: String, customerName: String,
        projectId: String, unitId: String, unitLabel: String, amount: Double,
        newStatus: String, auditedByUserId: String, rejectReason: String
    ) {
        val amountStr = "₹%,.2f".format(amount)
        val label = unitLabel.ifBlank { "your unit" }

        val (custTitle, custBody) = when (newStatus) {
            "AUDITED"  -> "Payment approved" to
                "Your payment of $amountStr for unit $label has been audited and approved."
            "REJECTED" -> "Payment rejected" to
                "Your payment of $amountStr for unit $label was rejected." +
                    (if (rejectReason.isNotBlank()) " Reason: $rejectReason" else "")
            else       -> "Payment status updated" to
                "Your payment of $amountStr for unit $label is now marked PENDING re-review."
        }
        insert("CUSTOMER", customerId, "PAYMENT_$newStatus", custTitle, custBody, projectId, unitId, paymentId)

        val staffTitle = when (newStatus) {
            "AUDITED"  -> "Payment audited"
            "REJECTED" -> "Payment rejected"
            else       -> "Payment status updated"
        }
        val staffBody = "${customerName.ifBlank { "A customer" }}'s payment of $amountStr for unit $label was marked $newStatus."
        adminAuditorUserIds(auditedByUserId).forEach { uid ->
            insert("USER", uid, "PAYMENT_$newStatus", staffTitle, staffBody, projectId, unitId, paymentId)
        }
    }
}

