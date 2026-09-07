package com.panakam.construction.database
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.panakam.construction.config.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
object DatabaseManager {
    // Single source of truth — see AppConfig.kt to change the backend URL (local vs AWS).
    private const val BASE_URL = AppConfig.BASE_URL
    fun getAllProjects(onSuccess: (List<Map<String, Any>>) -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = get("$BASE_URL/projects")
                val arr = org.json.JSONArray(response)
                withContext(Dispatchers.Main) { onSuccess(toList(arr)) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    fun updateProject(projectId: String, projectData: Map<String, Any>, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = org.json.JSONObject(projectData.mapValues { it.value.toString() }).toString()
                put("$BASE_URL/projects/$projectId", body)
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    fun addProject(projectId: String, projectData: Map<String, Any>, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject(projectData.mapValues { it.value.toString() }).apply { put("projectId", projectId) }.toString()
                post("$BASE_URL/projects", body)
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }
    fun getProject(projectId: String, onSuccess: (Map<String, Any>?) -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = get("$BASE_URL/projects/$projectId")
                withContext(Dispatchers.Main) { onSuccess(toMap(JSONObject(response))) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }
    fun deleteProject(projectId: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                delete("$BASE_URL/projects/$projectId")
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }
    // ── File / S3 methods ────────────────────────────────────────────────────

    /** List files for a project. Pass folder = "photos"|"documents"|"transactions"|null for all. */
    fun getProjectFiles(
        projectId: String,
        folder: String? = null,
        onSuccess: (List<Map<String, Any>>) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = if (folder != null) "$BASE_URL/projects/$projectId/files?folder=$folder"
                          else "$BASE_URL/projects/$projectId/files"
                val response = get(url)
                withContext(Dispatchers.Main) { onSuccess(toList(org.json.JSONArray(response))) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    /** Ask backend for a presigned S3 PUT URL. Returns map with uploadUrl, fileId, s3Key. */
    fun getUploadUrl(
        projectId: String,
        fileName: String,
        folder: String,
        contentType: String,
        onSuccess: (Map<String, Any>) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = org.json.JSONObject().apply {
                    put("fileName", fileName)
                    put("folder", folder)
                    put("contentType", contentType)
                }.toString()
                val response = post("$BASE_URL/projects/$projectId/files/upload-url", body)
                withContext(Dispatchers.Main) { onSuccess(toMap(org.json.JSONObject(response))) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    /** Ask backend for a presigned S3 GET URL. Returns downloadUrl string. */
    fun getDownloadUrl(
        projectId: String,
        fileId: String,
        onSuccess: (String) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = get("$BASE_URL/projects/$projectId/files/download-url?fileId=$fileId")
                val url = org.json.JSONObject(response).getString("downloadUrl")
                withContext(Dispatchers.Main) { onSuccess(url) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    /** Delete a file record from DynamoDB and the object from S3. */
    fun deleteProjectFile(
        projectId: String,
        fileId: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                delete("$BASE_URL/projects/$projectId/files/$fileId")
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    fun addInventory(projectId: String, inventoryData: Map<String, Any>, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject(inventoryData.mapValues { it.value.toString() }).apply { put("projectId", projectId) }.toString()
                post("$BASE_URL/inventory", body)
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }
    fun getInventory(projectId: String, onSuccess: (List<Map<String, Any>>) -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = get("$BASE_URL/inventory/$projectId")
                withContext(Dispatchers.Main) { onSuccess(toList(JSONArray(response))) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }
    fun updateInventoryItem(projectId: String, itemId: String, data: Map<String, Any>, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject(data.mapValues { it.value.toString() }).toString()
                put("$BASE_URL/inventory/$projectId/$itemId", body)
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    // ── Units ─────────────────────────────────────────────────────────────────

    fun getUnits(projectId: String, onSuccess: (List<Map<String, Any>>) -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = get("$BASE_URL/projects/$projectId/units")
                withContext(Dispatchers.Main) { onSuccess(toList(JSONArray(response))) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    fun getUnitsSummary(projectId: String, onSuccess: (Map<String, Any>) -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = get("$BASE_URL/projects/$projectId/units/summary")
                withContext(Dispatchers.Main) { onSuccess(toMap(JSONObject(response))) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    fun addUnit(projectId: String, unitData: Map<String, Any>, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject(unitData.mapValues { it.value.toString() }).toString()
                post("$BASE_URL/projects/$projectId/units", body)
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    fun updateUnit(projectId: String, unitId: String, data: Map<String, Any>, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject(data.mapValues { it.value.toString() }).toString()
                put("$BASE_URL/projects/$projectId/units/$unitId", body)
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    fun deleteUnit(projectId: String, unitId: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                delete("$BASE_URL/projects/$projectId/units/$unitId")
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    /** Upload an Excel file (.xls/.xlsx) as multipart/form-data for bulk unit import. */
    fun uploadUnitsExcel(
        context: Context,
        projectId: String,
        uri: Uri,
        onSuccess: (count: Int, warning: String?) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val fileName = run {
                    var name = "units.xlsx"
                    context.contentResolver.query(uri, null, null, null, null)?.use { c: Cursor ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx != -1) name = c.getString(idx) ?: name
                        }
                    }
                    name
                }

                val boundary = "----boundary${System.currentTimeMillis()}"
                val conn = URL("$BASE_URL/projects/$projectId/units/upload")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 30_000
                conn.readTimeout    = 60_000
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

                conn.outputStream.use { os ->
                    os.write("--$boundary\r\n".toByteArray())
                    os.write("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n".toByteArray())
                    os.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())
                    os.write(bytes)
                    os.write("\r\n--$boundary--\r\n".toByteArray())
                    os.flush()
                }

                val code     = conn.responseCode
                val respText = if (code in 200..299) conn.inputStream.bufferedReader().readText()
                               else conn.errorStream?.bufferedReader()?.readText() ?: "Error $code"
                conn.disconnect()

                if (code in 200..299) {
                    val json    = JSONObject(respText)
                    val count   = json.optInt("count", 0)
                    val warning = json.optString("warning").takeIf { it.isNotBlank() }
                    withContext(Dispatchers.Main) { onSuccess(count, warning) }
                } else {
                    withContext(Dispatchers.Main) { onFailure(Exception(respText)) }
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    fun deleteInventory(projectId: String, itemId: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                delete("$BASE_URL/inventory/$projectId/$itemId")
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }
    fun deleteFinancialData(projectId: String, recordId: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {        CoroutineScope(Dispatchers.IO).launch {
            try {
                delete("$BASE_URL/financials/$projectId/$recordId")
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }
    fun addFinancialData(projectId: String, financialData: Map<String, Any>, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject(financialData.mapValues { it.value.toString() }).apply { put("projectId", projectId) }.toString()
                post("$BASE_URL/financials", body)
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }
    fun getFinancialData(projectId: String, onSuccess: (List<Map<String, Any>>) -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = get("$BASE_URL/financials/$projectId")
                withContext(Dispatchers.Main) { onSuccess(toList(JSONArray(response))) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }
    // Generous timeouts: mobile WiFi round-trips + server cold-starts can easily
    // exceed a few seconds. A short timeout was causing spurious "timeout" errors
    // (e.g. on customer save) even though the server responded fine.
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS    = 30_000

    /** Reads the response body regardless of success/error status, then throws
     *  a descriptive exception for non-2xx responses instead of a generic
     *  FileNotFoundException from touching c.inputStream directly. */
    private fun readResponseOrThrow(c: HttpURLConnection): String {
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.readText() ?: ""
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).optString("error") }.getOrNull()
                .takeUnless { it.isNullOrBlank() } ?: text.ifBlank { "HTTP $code" }
            throw java.io.IOException("Server error ($code): $msg")
        }
        return text
    }

    private fun wrapNetworkError(url: String, e: Exception): Exception = when (e) {
        is java.net.SocketTimeoutException ->
            Exception("Timed out waiting for server at $url. Check that the backend is running and your device is on the same network.", e)
        is java.net.ConnectException, is java.net.UnknownHostException ->
            Exception("Cannot reach server at $url. Check the server IP/BASE_URL and that your device is on the same Wi-Fi network.", e)
        is java.io.IOException -> e
        else -> e
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "GET"; c.connectTimeout = CONNECT_TIMEOUT_MS; c.readTimeout = READ_TIMEOUT_MS
        return try {
            readResponseOrThrow(c)
        } catch (e: Exception) { throw wrapNetworkError(url, e) } finally { c.disconnect() }
    }
    private fun post(url: String, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.connectTimeout = CONNECT_TIMEOUT_MS; c.readTimeout = READ_TIMEOUT_MS
        return try {
            OutputStreamWriter(c.outputStream).use { it.write(body) }
            readResponseOrThrow(c)
        } catch (e: Exception) { throw wrapNetworkError(url, e) } finally { c.disconnect() }
    }
    // Longer read timeout for OCR-heavy endpoints (KYC / payment receipt extraction) —
    // these run Tesseract/PaddleOCR synchronously on the server, which can legitimately
    // take much longer than a normal API call for larger images, especially on a
    // resource-constrained server. Using the generic 30s timeout caused OCR extraction
    // to intermittently "just fail" (SocketTimeoutException) for bigger photos while
    // smaller ones worked fine — this was reported as "uploading Aadhaar card doesn't
    // work sometimes".
    private const val OCR_READ_TIMEOUT_MS = 90_000
    private fun postLongRunning(url: String, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.connectTimeout = CONNECT_TIMEOUT_MS; c.readTimeout = OCR_READ_TIMEOUT_MS
        return try {
            OutputStreamWriter(c.outputStream).use { it.write(body) }
            readResponseOrThrow(c)
        } catch (e: Exception) { throw wrapNetworkError(url, e) } finally { c.disconnect() }
    }
    private fun put(url: String, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "PUT"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.connectTimeout = CONNECT_TIMEOUT_MS; c.readTimeout = READ_TIMEOUT_MS
        return try {
            OutputStreamWriter(c.outputStream).use { it.write(body) }
            readResponseOrThrow(c)
        } catch (e: Exception) { throw wrapNetworkError(url, e) } finally { c.disconnect() }
    }
    private fun delete(url: String) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "DELETE"; c.connectTimeout = CONNECT_TIMEOUT_MS; c.readTimeout = READ_TIMEOUT_MS
        try {
            readResponseOrThrow(c)
        } catch (e: Exception) { throw wrapNetworkError(url, e) } finally { c.disconnect() }
    }
    private fun toMap(j: JSONObject): Map<String, Any> = j.keys().asSequence().associateWith { j.getString(it) }
    private fun toList(a: JSONArray): List<Map<String, Any>> = (0 until a.length()).map { toMap(a.getJSONObject(it)) }

    // ── Customers ─────────────────────────────────────────────────────────────

    /** Fetch all customer-unit records for a phone number (multi-unit support). */
    fun getCustomersByPhone(phone: String, onSuccess: (List<Map<String, Any>>) -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val encoded = java.net.URLEncoder.encode(phone, "UTF-8")
                val response = get("$BASE_URL/customers/by-phone/$encoded")
                withContext(Dispatchers.Main) { onSuccess(toList(JSONArray(response))) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    /** Fetch a single unit's details (unitNumber, floor, type, sba, etc.). */
    fun getUnitById(projectId: String, unitId: String, onSuccess: (Map<String, Any>) -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = get("$BASE_URL/projects/$projectId/units/$unitId")
                withContext(Dispatchers.Main) { onSuccess(toMap(JSONObject(response))) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    fun getCustomerForUnit(unitId: String, onSuccess: (Map<String, Any>?) -> Unit, onFailure: (Exception) -> Unit) {        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = get("$BASE_URL/customers/unit/$unitId")
                withContext(Dispatchers.Main) { onSuccess(toMap(JSONObject(response))) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (e.message?.contains("404") == true || e.message?.contains("No customer") == true)
                        onSuccess(null)
                    else
                        onFailure(e)
                }
            }
        }
    }

    fun getCustomerById(customerId: String, onSuccess: (Map<String, Any>) -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = get("$BASE_URL/customers/$customerId")
                withContext(Dispatchers.Main) { onSuccess(toMap(JSONObject(response))) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    fun addCustomer(data: Map<String, Any>, onSuccess: (String) -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject(data.mapValues { it.value.toString() }).toString()
                val resp = post("$BASE_URL/customers", body)
                val customerId = JSONObject(resp).optString("customerId", "")
                withContext(Dispatchers.Main) { onSuccess(customerId) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    fun updateCustomer(customerId: String, data: Map<String, Any>, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject(data.mapValues { it.value.toString() }).toString()
                put("$BASE_URL/customers/$customerId", body)
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    // ── Payments ──────────────────────────────────────────────────────────────

    fun getPayments(customerId: String, onSuccess: (List<Map<String, Any>>) -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = get("$BASE_URL/payments/customer/$customerId")
                withContext(Dispatchers.Main) { onSuccess(toList(JSONArray(response))) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    /** Fetch all payments across every unit owned by a customer phone number
     *  (multi-unit "My Payments" history for the customer portal). */
    fun getPaymentsByPhone(phone: String, onSuccess: (List<Map<String, Any>>) -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val encoded = java.net.URLEncoder.encode(phone, "UTF-8")
                val response = get("$BASE_URL/payments/by-phone/$encoded")
                withContext(Dispatchers.Main) { onSuccess(toList(JSONArray(response))) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    fun addPayment(data: Map<String, Any>, onSuccess: (String) -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject(data.mapValues { it.value.toString() }).toString()
                val resp = post("$BASE_URL/payments", body)
                val paymentId = JSONObject(resp).optString("paymentId", "")
                withContext(Dispatchers.Main) { onSuccess(paymentId) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    fun confirmPayment(
        customerId: String,
        projectId: String,
        unitId: String,
        receiptS3Key: String,
        receiptFileId: String,
        confirmedBy: String,
        declaredType: String = "AUTO",
        draftId: String = "",
        fields: Map<String, Any>,
        onSuccess: (String) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject().apply {
                    put("draftId", draftId)
                    put("customerId", customerId)
                    put("projectId", projectId)
                    put("unitId", unitId)
                    put("receiptS3Key", receiptS3Key)
                    put("receiptFileId", receiptFileId)
                    put("confirmedBy", confirmedBy)
                    put("declaredType", declaredType)
                    put("fields", JSONObject(fields.mapValues { it.value.toString() }))
                }.toString()
                val resp = post("$BASE_URL/payments/confirm", body)
                val paymentId = JSONObject(resp).optString("paymentId", "")
                withContext(Dispatchers.Main) { onSuccess(paymentId) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    fun updatePayment(paymentId: String, data: Map<String, Any>, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject(data.mapValues { it.value.toString() }).toString()
                put("$BASE_URL/payments/$paymentId", body)
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    fun deletePayment(paymentId: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                delete("$BASE_URL/payments/$paymentId")
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    fun getReceiptUploadUrl(customerId: String, fileName: String, contentType: String,
                            onSuccess: (Map<String, Any>) -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject().apply {
                    put("customerId", customerId); put("fileName", fileName); put("contentType", contentType)
                }.toString()
                val resp = post("$BASE_URL/payments/receipt/upload-url", body)
                withContext(Dispatchers.Main) { onSuccess(toMap(JSONObject(resp))) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    fun extractPaymentFromDoc(s3Key: String, rawText: String,
                              onSuccess: (Map<String, Any>) -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject().apply {
                    if (s3Key.isNotBlank()) put("s3Key", s3Key)
                    if (rawText.isNotBlank()) put("rawText", rawText)
                }.toString()
                val resp = postLongRunning("$BASE_URL/payments/extract", body)
                val parsed = JSONObject(resp).optJSONObject("parsed") ?: JSONObject()
                withContext(Dispatchers.Main) { onSuccess(toMap(parsed)) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    fun extractPaymentDraft(
        customerId: String,
        projectId: String,
        unitId: String,
        s3Key: String,
        rawText: String = "",
        declaredType: String = "AUTO",
        onSuccess: (Map<String, Any>) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject().apply {
                    put("customerId", customerId)
                    put("projectId", projectId)
                    put("unitId", unitId)
                    if (s3Key.isNotBlank()) put("s3Key", s3Key)
                    if (rawText.isNotBlank()) put("rawText", rawText)
                    put("declaredType", declaredType)
                }.toString()
                val resp = postLongRunning("$BASE_URL/payments/extract", body)
                val obj = JSONObject(resp)
                val parsedObj = obj.optJSONObject("parsed") ?: JSONObject()
                val map = mutableMapOf<String, Any>(
                    "message" to obj.optString("message", ""),
                    "draftId" to obj.optString("draftId", ""),
                    "documentType" to obj.optString("documentType", "Unknown"),
                    "source" to obj.optString("source", "UNKNOWN"),
                    "confidence" to obj.optString("confidence", "0"),
                    "needsReview" to obj.optString("needsReview", "true"),
                    "missingFields" to (obj.optJSONArray("missingFields")?.toString() ?: "[]"),
                    "warnings" to (obj.optJSONArray("warnings")?.toString() ?: "[]")
                )
                parsedObj.keys().forEach { key -> map[key] = parsedObj.optString(key, "") }
                withContext(Dispatchers.Main) { onSuccess(map) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    /** All payments across all projects — for Auditor / Admin view. */
    fun getAllPayments(
        statusFilter: String? = null,
        projectId: String? = null,
        onSuccess: (List<Map<String, Any>>) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val params = buildString {
                    if (!statusFilter.isNullOrBlank()) append("status=${statusFilter.uppercase()}")
                    if (!projectId.isNullOrBlank()) {
                        if (isNotEmpty()) append("&")
                        append("projectId=$projectId")
                    }
                }
                val url = if (params.isBlank()) "$BASE_URL/payments/all" else "$BASE_URL/payments/all?$params"
                val response = get(url)
                withContext(Dispatchers.Main) { onSuccess(toList(JSONArray(response))) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    /** Get a presigned, time-limited GET URL to view/download a payment's attached receipt. */
    fun getReceiptDownloadUrl(s3Key: String, onSuccess: (String) -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val encoded = java.net.URLEncoder.encode(s3Key, "UTF-8")
                val response = get("$BASE_URL/payments/receipt/download-url?s3Key=$encoded")
                val url = JSONObject(response).getString("downloadUrl")
                withContext(Dispatchers.Main) { onSuccess(url) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    // ── Collections ───────────────────────────────────────────────────────────

    /**
     * Admin-only: revert a sold unit back to Available.
     * Backend atomically: marks collection "Reverted", creates suspense entry, sets unit Available.
     */
    fun revertUnitToAvailable(
        projectId: String,
        unitId: String,
        reason: String,
        revertedBy: String,
        onSuccess: (suspenseId: String, collectedAmount: Double) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject().apply {
                    put("reason", reason)
                    put("revertedBy", revertedBy)
                }.toString()
                val resp = post("$BASE_URL/projects/$projectId/units/$unitId/revert-to-available", body)
                val obj  = JSONObject(resp)
                val suspenseId      = obj.optString("suspenseId", "")
                val collectedAmount = obj.optString("collectedAmount", "0").toDoubleOrNull() ?: 0.0
                withContext(Dispatchers.Main) { onSuccess(suspenseId, collectedAmount) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    /** Get suspense entries (all or filtered by projectId). */
    fun getSuspenseEntries(
        projectId: String? = null,
        onSuccess: (List<Map<String, Any>>) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = if (projectId != null)
                    "$BASE_URL/suspense?projectId=${java.net.URLEncoder.encode(projectId, "UTF-8")}"
                else "$BASE_URL/suspense"
                val response = get(url)
                withContext(Dispatchers.Main) { onSuccess(toList(JSONArray(response))) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    /** Get suspense summary (total holding amount). */
    fun getSuspenseSummary(
        projectId: String? = null,
        onSuccess: (Map<String, Any>) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = if (projectId != null)
                    "$BASE_URL/suspense/summary?projectId=${java.net.URLEncoder.encode(projectId, "UTF-8")}"
                else "$BASE_URL/suspense/summary"
                val response = get(url)
                withContext(Dispatchers.Main) { onSuccess(toMap(JSONObject(response))) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    /** Add a unit-sale collection record. */
    fun addCollection(data: Map<String, Any>, onSuccess: (String) -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject(data.mapValues { it.value.toString() }).toString()
                val resp = post("$BASE_URL/collections", body)
                val collectionId = JSONObject(resp).optString("collectionId", "")
                withContext(Dispatchers.Main) { onSuccess(collectionId) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    /** Get all collection records, optionally filtered by projectId. */
    fun getCollections(
        projectId: String? = null,
        onSuccess: (List<Map<String, Any>>) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = if (projectId != null)
                    "$BASE_URL/collections?projectId=${java.net.URLEncoder.encode(projectId, "UTF-8")}"
                else "$BASE_URL/collections"
                val response = get(url)
                withContext(Dispatchers.Main) { onSuccess(toList(JSONArray(response))) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    /** Get collection summary (total units, amounts). */
    fun getCollectionSummary(
        projectId: String? = null,
        onSuccess: (Map<String, Any>) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = if (projectId != null)
                    "$BASE_URL/collections/summary?projectId=${java.net.URLEncoder.encode(projectId, "UTF-8")}"
                else "$BASE_URL/collections/summary"
                val response = get(url)
                withContext(Dispatchers.Main) { onSuccess(toMap(JSONObject(response))) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    /** Collections grouped by who sold the unit (Admin name or Sales Rep name). */
    fun getCollectionSummaryBySalesRep(
        projectId: String? = null,
        onSuccess: (List<Map<String, Any>>) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = if (projectId != null)
                    "$BASE_URL/collections/summary-by-sales-rep?projectId=${java.net.URLEncoder.encode(projectId, "UTF-8")}"
                else "$BASE_URL/collections/summary-by-sales-rep"
                val response = get(url)
                withContext(Dispatchers.Main) { onSuccess(toList(JSONArray(response))) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    /** Admin: apply/update a discount on a specific unit's collection record.
     *  Recomputes totalAmount/pendingAmount server-side and auto-adjusts
     *  Financials — reflected immediately in Collections, Financials and the
     *  customer portal. */
    fun applyDiscount(
        collectionId: String, discountAmount: Double, discountReason: String, discountedBy: String,
        onSuccess: (totalAmount: Double, pendingAmount: Double, paymentStatus: String) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject().apply {
                    put("discountAmount", discountAmount.toString())
                    put("discountReason", discountReason)
                    put("discountedBy", discountedBy)
                }.toString()
                val resp = put("$BASE_URL/collections/$collectionId/discount", body)
                val obj = JSONObject(resp)
                withContext(Dispatchers.Main) {
                    onSuccess(
                        obj.optString("totalAmount", "0").toDoubleOrNull() ?: 0.0,
                        obj.optString("pendingAmount", "0").toDoubleOrNull() ?: 0.0,
                        obj.optString("paymentStatus", "Unpaid")
                    )
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }


    // ── Reports ───────────────────────────────────────────────────────────────

    private fun reportParams(projectId: String?, soldBy: String?): String =
        buildList {
            if (!projectId.isNullOrBlank()) add("projectId=${java.net.URLEncoder.encode(projectId, "UTF-8")}")
            if (!soldBy.isNullOrBlank())    add("soldBy=${java.net.URLEncoder.encode(soldBy, "UTF-8")}")
        }.joinToString("&")

    /** Customer/unit payment status report: customerName, unitId, totalCost,
     *  paidAudited, paidUnaudited, balanceAudited, balanceUnaudited, soldBy.
     *  Optionally scoped to a project and/or a specific sales rep (Admin sees
     *  everything; a Sales Rep should pass their own name to see only their sales). */
    fun getCustomerPaymentReport(
        projectId: String? = null,
        soldBy: String? = null,
        onSuccess: (List<Map<String, Any>>) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val params = reportParams(projectId, soldBy)
                val url = if (params.isBlank()) "$BASE_URL/reports/customer-payments"
                          else "$BASE_URL/reports/customer-payments?$params"
                val response = get(url)
                withContext(Dispatchers.Main) { onSuccess(toList(JSONArray(response))) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    /** Downloads the same report as a CSV file into the device's Downloads folder
     *  (via MediaStore — no storage permission needed, minSdk 35 is well past the
     *  API 29 requirement) and returns the saved content Uri so the caller can
     *  open/share it. */
    fun downloadCustomerPaymentReportCsv(
        context: Context,
        projectId: String? = null,
        soldBy: String? = null,
        onSuccess: (Uri) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val params = reportParams(projectId, soldBy)
                val url = if (params.isBlank()) "$BASE_URL/reports/customer-payments/csv"
                          else "$BASE_URL/reports/customer-payments/csv?$params"
                val csvText = get(url)
                val fileName = "customer_payments_report_${System.currentTimeMillis()}.csv"
                val resolver = context.contentResolver
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw java.io.IOException("Could not create file in Downloads")
                resolver.openOutputStream(uri)?.use { it.write(csvText.toByteArray()) }
                    ?: throw java.io.IOException("Could not open output stream for the downloaded file")
                withContext(Dispatchers.Main) { onSuccess(uri) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    // ── Sales Reps (per project) ─────────────────────────────────────────────

    /** List active sales reps for a project (used in the "Sold By" dropdown). */
    fun getSalesReps(
        projectId: String,
        onSuccess: (List<Map<String, Any>>) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = get("$BASE_URL/projects/$projectId/sales-reps")
                withContext(Dispatchers.Main) { onSuccess(toList(JSONArray(response))) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    /** Admin: add a new sales rep to a project. */
    fun addSalesRep(
        projectId: String,
        name: String,
        phone: String,
        createdBy: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject().apply {
                    put("name", name); put("phone", phone); put("createdBy", createdBy)
                }.toString()
                post("$BASE_URL/projects/$projectId/sales-reps", body)
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    /** Admin: remove (deactivate) a sales rep from a project. */
    fun removeSalesRep(
        projectId: String,
        salesRepId: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                delete("$BASE_URL/projects/$projectId/sales-reps/$salesRepId")
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    /** Auditor/Admin: change auditStatus to AUDITED or REJECTED. */
    fun auditPayment(
        paymentId: String,
        auditStatus: String,   // "AUDITED" | "REJECTED" | "PENDING"
        auditedBy: String,
        rejectReason: String = "",
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject().apply {
                    put("auditStatus",  auditStatus)
                    put("auditedBy",    auditedBy)
                    put("rejectReason", rejectReason)
                }.toString()
                put("$BASE_URL/payments/$paymentId/audit", body)
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    /** Admin: mark a suspense entry as adjusted with notes. */
    fun adjustSuspenseEntry(
        suspenseId: String,
        notes: String,
        adjustedBy: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject().apply {
                    put("notes", notes)
                    put("adjustedBy", adjustedBy)
                }.toString()
                put("$BASE_URL/suspense/$suspenseId/adjust", body)
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    // ── KYC (Aadhaar) ─────────────────────────────────────────────────────────

    /** Current KYC status/details for a customer. */
    fun getKyc(customerId: String, onSuccess: (Map<String, Any>) -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = get("$BASE_URL/customers/$customerId/kyc")
                withContext(Dispatchers.Main) { onSuccess(toMap(JSONObject(response))) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    /** Presigned S3 PUT URL for uploading the Aadhaar card image. */
    fun getKycUploadUrl(customerId: String, fileName: String, contentType: String,
                        onSuccess: (Map<String, Any>) -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject().apply {
                    put("fileName", fileName); put("contentType", contentType)
                }.toString()
                val resp = post("$BASE_URL/customers/$customerId/kyc/upload-url", body)
                withContext(Dispatchers.Main) { onSuccess(toMap(JSONObject(resp))) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    /** OCR-extracts name/Aadhaar number/address from the uploaded image. */
    fun extractKyc(customerId: String, s3Key: String,
                   onSuccess: (Map<String, Any>) -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject().apply { put("s3Key", s3Key) }.toString()
                val resp = postLongRunning("$BASE_URL/customers/$customerId/kyc/extract", body)
                val obj = JSONObject(resp)
                val map = mutableMapOf<String, Any>(
                    "s3Key" to obj.optString("s3Key", s3Key),
                    "name" to obj.optString("name", ""),
                    "aadharNumber" to obj.optString("aadharNumber", ""),
                    "address" to obj.optString("address", ""),
                    "dob" to obj.optString("dob", ""),
                    "gender" to obj.optString("gender", ""),
                    "missingFields" to (obj.optJSONArray("missingFields")?.toString() ?: "[]"),
                    "warnings" to (obj.optJSONArray("warnings")?.toString() ?: "[]")
                )
                withContext(Dispatchers.Main) { onSuccess(map) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    /** Persists the (reviewed/edited) KYC fields — becomes the source of truth for
     *  agreement auto-fill. */
    fun confirmKyc(customerId: String, name: String, aadharNumber: String, address: String, s3Key: String,
                   onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject().apply {
                    put("name", name); put("aadharNumber", aadharNumber)
                    put("address", address); put("s3Key", s3Key)
                }.toString()
                post("$BASE_URL/customers/$customerId/kyc/confirm", body)
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    /** Presigned GET URL to view the uploaded Aadhaar image. */
    fun getKycAadhaarUrl(customerId: String, onSuccess: (String) -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = get("$BASE_URL/customers/$customerId/kyc/aadhaar-url")
                withContext(Dispatchers.Main) { onSuccess(JSONObject(response).getString("downloadUrl")) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    // ── Agreement templates (per project, admin-managed) ────────────────────

    fun getAgreementTemplates(projectId: String, onSuccess: (List<Map<String, Any>>) -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = get("$BASE_URL/projects/$projectId/agreement-templates")
                withContext(Dispatchers.Main) { onSuccess(toList(JSONArray(response))) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    fun getAgreementTemplateUploadUrl(projectId: String, fileName: String, contentType: String,
                                      onSuccess: (Map<String, Any>) -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject().apply {
                    put("fileName", fileName); put("contentType", contentType)
                }.toString()
                val resp = post("$BASE_URL/projects/$projectId/agreement-templates/upload-url", body)
                withContext(Dispatchers.Main) { onSuccess(toMap(JSONObject(resp))) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    /** Registers the uploaded template file (or a directly-typed templateText) and
     *  extracts its placeholder text server-side. */
    fun registerAgreementTemplate(
        projectId: String, templateId: String, name: String, s3Key: String,
        contentType: String, uploadedBy: String, templateText: String = "",
        onSuccess: () -> Unit, onFailure: (Exception) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject().apply {
                    put("templateId", templateId); put("name", name); put("s3Key", s3Key)
                    put("contentType", contentType); put("uploadedBy", uploadedBy)
                    if (templateText.isNotBlank()) put("templateText", templateText)
                }.toString()
                post("$BASE_URL/projects/$projectId/agreement-templates", body)
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    fun deleteAgreementTemplate(projectId: String, templateId: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                delete("$BASE_URL/projects/$projectId/agreement-templates/$templateId")
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    // ── Agreements (auto-filled drafts, sent to customer portal) ────────────

    /** Admin/builder: auto-fill a template with the unit's customer KYC + unit
     *  details and immediately send the draft to the customer portal. */
    fun createAgreement(
        projectId: String, unitId: String, customerId: String, templateId: String, createdBy: String,
        onSuccess: (String) -> Unit, onFailure: (Exception) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject().apply {
                    put("projectId", projectId); put("unitId", unitId)
                    put("customerId", customerId); put("templateId", templateId)
                    put("createdBy", createdBy)
                }.toString()
                val resp = post("$BASE_URL/agreements", body)
                withContext(Dispatchers.Main) { onSuccess(JSONObject(resp).optString("agreementId", "")) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    /** Customer portal — Documents tab: all agreements sent to this customer. */
    fun getAgreementsForCustomer(customerId: String, onSuccess: (List<Map<String, Any>>) -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = get("$BASE_URL/agreements/customer/$customerId")
                withContext(Dispatchers.Main) { onSuccess(toList(JSONArray(response))) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    /** Admin: all agreements for a project. */
    fun getAgreementsForProject(projectId: String, onSuccess: (List<Map<String, Any>>) -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = get("$BASE_URL/agreements/project/$projectId")
                withContext(Dispatchers.Main) { onSuccess(toList(JSONArray(response))) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    fun getAgreementDownloadUrl(agreementId: String, onSuccess: (String) -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = get("$BASE_URL/agreements/$agreementId/download-url")
                withContext(Dispatchers.Main) { onSuccess(JSONObject(response).getString("downloadUrl")) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    /** Customer accepts the draft. Blank comments = accepted as-is (builder may then
     *  sign); non-blank comments = sent back for revision. */
    fun acceptAgreement(agreementId: String, comments: String, respondedBy: String,
                        onSuccess: (status: String) -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject().apply {
                    put("comments", comments); put("respondedBy", respondedBy)
                }.toString()
                val resp = put("$BASE_URL/agreements/$agreementId/accept", body)
                withContext(Dispatchers.Main) { onSuccess(JSONObject(resp).optString("status", "")) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    /** Builder/admin: countersign an already-ACCEPTED agreement. */
    fun signAgreement(agreementId: String, signedBy: String, signedPdfS3Key: String = "",
                      onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject().apply {
                    put("signedBy", signedBy); put("signedPdfS3Key", signedPdfS3Key)
                }.toString()
                put("$BASE_URL/agreements/$agreementId/sign", body)
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    // ── Notifications ────────────────────────────────────────────────────────
    // A device may qualify as BOTH staff (userId) and customer (customerId /
    // phone — possibly several units under one phone) at once, e.g. an Admin
    // who personally bought a unit. Pass whichever identifiers apply; the
    // backend unions all matches so the SAME device sees both "your payment
    // was approved" (customer) and "new payment needs audit" (admin) alerts.

    private fun notifQuery(userId: String?, customerId: String?, phone: String?): String {
        val params = mutableListOf<String>()
        if (!userId.isNullOrBlank())     params += "userId=${java.net.URLEncoder.encode(userId, "UTF-8")}"
        if (!customerId.isNullOrBlank()) params += "customerId=${java.net.URLEncoder.encode(customerId, "UTF-8")}"
        if (!phone.isNullOrBlank())      params += "phone=${java.net.URLEncoder.encode(phone, "UTF-8")}"
        return params.joinToString("&")
    }

    fun getNotifications(
        userId: String? = null, customerId: String? = null, phone: String? = null,
        onSuccess: (List<Map<String, Any>>) -> Unit, onFailure: (Exception) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = get("$BASE_URL/notifications?${notifQuery(userId, customerId, phone)}")
                withContext(Dispatchers.Main) { onSuccess(toList(JSONArray(response))) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    fun getUnreadNotificationCount(
        userId: String? = null, customerId: String? = null, phone: String? = null,
        onSuccess: (Int) -> Unit, onFailure: (Exception) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = get("$BASE_URL/notifications/unread-count?${notifQuery(userId, customerId, phone)}")
                withContext(Dispatchers.Main) { onSuccess(JSONObject(response).optInt("unread", 0)) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    fun markNotificationRead(notificationId: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                put("$BASE_URL/notifications/$notificationId/read", "{}")
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }

    fun markAllNotificationsRead(
        userId: String? = null, customerId: String? = null, phone: String? = null,
        onSuccess: () -> Unit, onFailure: (Exception) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject().apply {
                    if (!userId.isNullOrBlank())     put("userId", userId)
                    if (!customerId.isNullOrBlank()) put("customerId", customerId)
                    if (!phone.isNullOrBlank())      put("phone", phone)
                }.toString()
                put("$BASE_URL/notifications/read-all", body)
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }
}
