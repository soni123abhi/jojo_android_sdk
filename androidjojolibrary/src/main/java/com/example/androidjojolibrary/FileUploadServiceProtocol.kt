package com.example.androidjojolibrary


import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.google.gson.Gson
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object FileUploadServiceProtocol {
    private var isInitialized = false
    private var apiKey : String = ""
    private val client = OkHttpClient()
    private const val BASE_URL = "http://192.168.0.202:8001/v1/admin/user"

    /** for emulator*/
//    private const val BASE_URL = "http://10.0.2.2:8001/v1/admin/user/file/"
    /**
     * Initializes the SDK. Should be called once before using other SDK functionalities.
     */
    fun initialize(context: Context) {
        if (!isInitialized) {
            isInitialized = true
        }
    }

    /**
     * Generates an API key which is required for accessing the remaining endpoints.
     *
     * @param email The email address used to generate the API key.
     */
    fun generateApiKey(email: String) {
        val client = OkHttpClient()

        val requestBody = JSONObject().apply {
            put("email", email)
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

        val request = Request.Builder()
            .url("$BASE_URL/generate-api-key")
            .post(requestBody)
            .addHeader("Accept", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                println("API key request failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        println("API key request failed: ${it.code}")
                        return
                    }

                    val responseData = it.body?.string()
                    if (!responseData.isNullOrEmpty()) {
                        try {
                            val jsonResponse = JSONObject(responseData)
                            apiKey = jsonResponse.optString("apiKey", "")
                            println("Generated API Key: $apiKey")
                        } catch (e: Exception) {
                            println("Error parsing API key response: ${e.message}")
                        }
                    } else {
                        println("Empty response received when generating API key")
                    }
                }
            }
        })
    }



    /**
     * Uploads a document to the server.
     * @param file The file to be uploaded.
     * @param token Authorization token.
     * @param context Application context.
     */
    fun uploadDocuments(
        file: File,
        token: String,
        context: Context,
        email_list: ArrayList<String>,
        radio_type: String
    ) {
        println("Uploading file: ${file.name}")

        // Convert email_list to JSON array string
        val emailJson = Gson().toJson(email_list)

        // Build and send the request
        val request = buildMultipartRequest("/file/upload", file, token, emailJson, radio_type)

        executeRequest(request, "Failed to upload document") { responseBody ->
            try {
                val jsonObject = JSONObject(responseBody)
                val id = jsonObject.getString("_id")
                println("Extracted Document ID: $id")
                downloadPdf(id, token, context)
            } catch (e: Exception) {
                println("Error parsing upload response: ${e.message}")
            }
        }
    }


    /**
     * Downloads a PDF file using the document ID.
     * @param id Document ID.
     * @param token Authorization token.
     * @param context Application context.
     */
    private fun downloadPdf(id: String, token: String, context: Context) {
        val pdfUrl = "$BASE_URL/file/download/$id"
        println("Downloading file from: $pdfUrl")

        val savedFilePath = downloadFile(context, pdfUrl, token, "signed_file.pdf")
        if (savedFilePath != null) {
            println("PDF successfully downloaded at: $savedFilePath")
        } else {
            println("Error: Failed to download PDF.")
        }
    }

    /**
     * Downloads a file and saves it to the device storage.
     * @param context Application context.
     * @param fileUrl URL of the file to download.
     * @param authToken Authorization token.
     * @param defaultFileName Default filename for saving the file.
     * @return The file path if successful, null otherwise.
     */
    fun downloadFile(
        context: Context, fileUrl: String, authToken: String, defaultFileName: String
    ): String? {
        val request = Request.Builder()
            .url(fileUrl)
            .addHeader("Authorization", "Bearer $authToken")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    println("Error: Failed to download file. Server Response: ${response.code} - ${response.message}")
                    return null
                }

                val contentType = response.header("Content-Type") ?: "application/octet-stream"
                val fileExtension = getFileExtension(contentType)
                val fileName = defaultFileName.substringBeforeLast(".") + fileExtension
                val inputStream = response.body?.byteStream() ?: return null

                saveFile(context, fileName, contentType, inputStream)
            }
        } catch (e: IOException) {
            println("Error: File download failed due to ${e.message}")
            null
        }
    }

    /**
     * Verifies a document by uploading it to the verification API.
     * @param file The file to be verified.
     * @param token Authorization token.
     * @param context Application context.
     */
    fun verifyDocuments(file: File, token: String, context: Context) {
        println("Verifying file: ${file.name}")

        val request = buildMultipartRequestVerfiy("/file/verification", file, token)
        executeRequest(request, "Failed to verify document")
    }

    /**
     * Fetches a list of uploaded documents.
     * @param token Authorization token.
     */
    fun fetchDocuments(token: String) {
        val request = Request.Builder()
            .url("${BASE_URL}/file/documents")
            .addHeader("Authorization", "Bearer $token")
            .build()

        executeRequest(request, "Failed to fetch documents")
    }

    fun fetchInviteHistory(token: String) {
        val request = Request.Builder()
            .url("${BASE_URL}file/my-invitations")
            .addHeader("Authorization", "Bearer $token")
            .build()

        executeRequest(request, "Failed to fetch documents")
    }

    fun fetchFileDetails(token: String, fileId:String) {
        val request = Request.Builder()
            .url("${BASE_URL}file/details/$fileId")
            .addHeader("Authorization", "Bearer $token")
            .build()

        executeRequest(request, "Failed to fetch Details")
    }

   fun signInvitedDocument(token: String, fileId:String) {
        val request = Request.Builder()
            .url("${BASE_URL}file/details/$fileId")
            .addHeader("Authorization", "Bearer $token")
            .build()

        executeRequest(request, "Failed to fetch Details")
    }

    /**
     * Builds a multipart request for uploading files.
     * @param endpoint API endpoint for the request.
     * @param file The file to be uploaded.
     * @param token Authorization token.
     * @return The constructed Request object.
     */
    private fun buildMultipartRequest(
        endpoint: String,
        file: File,
        token: String,
        emailJson: String,
        signingType: String
    ): Request {
        val mimeType = getMimeType(file) ?: "application/pdf"
        val fileRequestBody = file.asRequestBody(mimeType.toMediaTypeOrNull())

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, fileRequestBody)
            .addFormDataPart("signers", null, emailJson.toRequestBody("application/json".toMediaTypeOrNull()))
            .addFormDataPart("signingType", signingType)
            .build()

        return Request.Builder()
            .url("$BASE_URL$endpoint")
            .post(multipartBody)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "*/*")
            .build()
    }

    /**
     * Builds a multipart request for uploading files.
     * @param endpoint API endpoint for the request.
     * @param file The file to be uploaded.
     * @param token Authorization token.
     * @return The constructed Request object.
     */
    private fun buildMultipartRequestVerfiy(endpoint: String, file: File, token: String): Request {
        val mimeType = getMimeType(file) ?: "application/octet-stream"
        val fileRequestBody = file.asRequestBody(mimeType.toMediaTypeOrNull())

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, fileRequestBody)
            .build()

        return Request.Builder()
            .url("$BASE_URL$endpoint")
            .post(multipartBody)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "*/*")
            .build()
    }

    /**
     * Executes an HTTP request and handles responses or failures.
     * @param request The HTTP request to be executed.
     * @param errorMessage Error message to be displayed in case of failure.
     * @param onSuccess (Optional) Callback function for successful response.
     */
    private fun executeRequest(request: Request, errorMessage: String, onSuccess: ((String) -> Unit)? = null) {
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                println("$errorMessage: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val responseBody = response.body?.string()
                    if (!response.isSuccessful) {
                        println("Error: $errorMessage. Server Response: ${response.code} - ${response.message}")
                        println("API Response: $responseBody")
                    } else {
                        println("Success: $responseBody")
                        responseBody?.let { onSuccess?.invoke(it) }
                    }
                }
            }
        })
    }

    /**
     * Returns the MIME type of a given file based on its extension.
     * @param file The file whose MIME type is to be determined.
     * @return The corresponding MIME type as a String.
     */
    private fun getMimeType(file: File): String? = when (file.extension.lowercase()) {
        "pdf" -> "application/pdf"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "txt" -> "text/plain"
        "html" -> "text/html"
        "csv" -> "text/csv"
        else -> null
    }

    /**
     * Determines the file extension based on the MIME type.
     * @param contentType The MIME type of the file.
     * @return The corresponding file extension.
     */
    private fun getFileExtension(contentType: String): String = when {
        contentType.contains("pdf", true) -> ".pdf"
        contentType.contains("json", true) -> ".json"
        contentType.contains("xml", true) -> ".xml"
        else -> ".bin"
    }

    /**
     * Saves a file to the device storage.
     * @param context Application context.
     * @param fileName Name of the file to be saved.
     * @param contentType MIME type of the file.
     * @param inputStream Input stream of the file.
     * @return The saved file path if successful, null otherwise.
     */
    private fun saveFile(context: Context, fileName: String, contentType: String, inputStream: java.io.InputStream): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, contentType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }

                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues) ?: return null
                resolver.openOutputStream(uri).use { outputStream -> inputStream.copyTo(outputStream!!) }
                uri.toString()
            } else {
                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                FileOutputStream(file).use { output -> inputStream.copyTo(output) }
                file.absolutePath
            }
        } catch (e: IOException) {
            println("Error saving file: ${e.message}")
            null
        }
    }
}
