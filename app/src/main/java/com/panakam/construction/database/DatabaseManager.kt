package com.panakam.construction.database
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
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
    // 192.168.1.21 = Mac's LAN IP — reachable from a physical device on the same WiFi
    // Change this if your Mac's IP changes (check with: ipconfig getifaddr en0)
    private const val BASE_URL = "http://192.168.1.21:8080"
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
        onSuccess: (count: Int) -> Unit,
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
                    val count = JSONObject(respText).optInt("count", 0)
                    withContext(Dispatchers.Main) { onSuccess(count) }
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
    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "GET"; c.connectTimeout = 5000; c.readTimeout = 5000
        return c.inputStream.bufferedReader().readText().also { c.disconnect() }
    }
    private fun post(url: String, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.connectTimeout = 5000; c.readTimeout = 5000
        OutputStreamWriter(c.outputStream).use { it.write(body) }
        return c.inputStream.bufferedReader().readText().also { c.disconnect() }
    }
    private fun put(url: String, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "PUT"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.connectTimeout = 5000; c.readTimeout = 5000
        OutputStreamWriter(c.outputStream).use { it.write(body) }
        return c.inputStream.bufferedReader().readText().also { c.disconnect() }
    }
    private fun delete(url: String) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "DELETE"; c.connectTimeout = 5000; c.readTimeout = 5000
        c.responseCode; c.disconnect()
    }
    private fun toMap(j: JSONObject): Map<String, Any> = j.keys().asSequence().associateWith { j.getString(it) }
    private fun toList(a: JSONArray): List<Map<String, Any>> = (0 until a.length()).map { toMap(a.getJSONObject(it)) }

    // ── Customers ─────────────────────────────────────────────────────────────

    fun getCustomerForUnit(unitId: String, onSuccess: (Map<String, Any>?) -> Unit, onFailure: (Exception) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
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
                val resp = post("$BASE_URL/payments/extract", body)
                val parsed = JSONObject(resp).optJSONObject("parsed") ?: JSONObject()
                withContext(Dispatchers.Main) { onSuccess(toMap(parsed)) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onFailure(e) } }
        }
    }
}
