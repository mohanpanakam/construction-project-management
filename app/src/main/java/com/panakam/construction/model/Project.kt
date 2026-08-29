package com.panakam.construction.model

data class Project(
    val projectId: String,
    val name: String,
    val location: String,
    val status: String,           // Planning | In Progress | On Hold | Completed
    val startDate: String,
    val endDate: String,
    val budget: String,
    val description: String,
    // ── New fields ────────────────────────────────────────────────────────────
    val mapLocation: String = "", // free-text address / coordinates for Google Maps
    val partnerName: String = "",
    val partnerPhone: String = "",
    val partnerEmail: String = "",
    /** Comma-separated device photo URIs – stored locally, not sent to backend */
    val photoUris: String = ""
) {
    companion object {
        val STATUS_OPTIONS = listOf("Planning", "In Progress", "On Hold", "Completed")

        fun fromMap(map: Map<String, Any>): Project = Project(
            projectId   = map["projectId"]?.toString()   ?: "",
            name        = map["name"]?.toString()        ?: "",
            location    = map["location"]?.toString()    ?: "",
            status      = map["status"]?.toString()      ?: "Planning",
            startDate   = map["startDate"]?.toString()   ?: "",
            endDate     = map["endDate"]?.toString()     ?: "",
            budget      = map["budget"]?.toString()      ?: "",
            description = map["description"]?.toString() ?: "",
            mapLocation = map["mapLocation"]?.toString() ?: "",
            partnerName = map["partnerName"]?.toString() ?: "",
            partnerPhone= map["partnerPhone"]?.toString() ?: "",
            partnerEmail= map["partnerEmail"]?.toString() ?: ""
            // photoUris not stored in backend – loaded from LocalProjectStorage
        )

        /** Returns map of fields to send to backend (excludes local-only photoUris) */
        fun toMap(p: Project): Map<String, Any> = mapOf(
            "projectId"   to p.projectId,
            "name"        to p.name,
            "location"    to p.location,
            "status"      to p.status,
            "startDate"   to p.startDate,
            "endDate"     to p.endDate,
            "budget"      to p.budget,
            "description" to p.description,
            "mapLocation" to p.mapLocation,
            "partnerName" to p.partnerName,
            "partnerPhone" to p.partnerPhone,
            "partnerEmail" to p.partnerEmail
        )
    }
}
