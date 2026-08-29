package com.panakam.construction.model

data class Project(
    val projectId: String,
    val name: String,
    val location: String,
    val status: String,
    val startDate: String,
    val endDate: String,
    val budget: String,
    val description: String,
    val mapLocation: String = "",
    val partnerName: String = "",
    val partnerPhone: String = "",
    val partnerEmail: String = "",
    val projectType: String = "Builder Owned",
    val landOwnerName: String = "",
    val landOwnerShare: String = "",
    val photoUris: String = ""
) {
    val isJointDevelopment get() = projectType == "Joint Development"

    companion object {
        val STATUS_OPTIONS       = listOf("Planning", "In Progress", "On Hold", "Completed")
        val PROJECT_TYPE_OPTIONS = listOf("Builder Owned", "Joint Development")

        fun fromMap(map: Map<String, Any>): Project = Project(
            projectId      = map["projectId"]?.toString()      ?: "",
            name           = map["name"]?.toString()           ?: "",
            location       = map["location"]?.toString()       ?: "",
            status         = map["status"]?.toString()         ?: "Planning",
            startDate      = map["startDate"]?.toString()      ?: "",
            endDate        = map["endDate"]?.toString()        ?: "",
            budget         = map["budget"]?.toString()         ?: "",
            description    = map["description"]?.toString()    ?: "",
            mapLocation    = map["mapLocation"]?.toString()    ?: "",
            partnerName    = map["partnerName"]?.toString()    ?: "",
            partnerPhone   = map["partnerPhone"]?.toString()   ?: "",
            partnerEmail   = map["partnerEmail"]?.toString()   ?: "",
            projectType    = map["projectType"]?.toString()    ?: "Builder Owned",
            landOwnerName  = map["landOwnerName"]?.toString()  ?: "",
            landOwnerShare = map["landOwnerShare"]?.toString() ?: ""
        )

        fun toMap(p: Project): Map<String, Any> = mapOf(
            "projectId"      to p.projectId,
            "name"           to p.name,
            "location"       to p.location,
            "status"         to p.status,
            "startDate"      to p.startDate,
            "endDate"        to p.endDate,
            "budget"         to p.budget,
            "description"    to p.description,
            "mapLocation"    to p.mapLocation,
            "partnerName"    to p.partnerName,
            "partnerPhone"   to p.partnerPhone,
            "partnerEmail"   to p.partnerEmail,
            "projectType"    to p.projectType,
            "landOwnerName"  to p.landOwnerName,
            "landOwnerShare" to p.landOwnerShare
        )
    }
}
