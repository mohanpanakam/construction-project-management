package com.panakam.construction.model

data class ProjectFile(
    val fileId: String,
    val projectId: String,
    val fileName: String,
    val folder: String,       // "photos" | "documents" | "transactions"
    val s3Key: String,
    val contentType: String,
    val uploadedAt: String
) {
    val isImage: Boolean
        get() = contentType.startsWith("image/")

    companion object {
        val FOLDERS = listOf("photos", "documents", "transactions")

        fun fromMap(map: Map<String, Any>) = ProjectFile(
            fileId      = map["fileId"]?.toString()      ?: "",
            projectId   = map["projectId"]?.toString()   ?: "",
            fileName    = map["fileName"]?.toString()    ?: "",
            folder      = map["folder"]?.toString()      ?: "documents",
            s3Key       = map["s3Key"]?.toString()       ?: "",
            contentType = map["contentType"]?.toString() ?: "application/octet-stream",
            uploadedAt  = map["uploadedAt"]?.toString()  ?: ""
        )
    }
}

