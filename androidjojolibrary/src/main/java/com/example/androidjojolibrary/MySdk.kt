package com.example.androidjojolibrary

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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

object MySDK {
    private var isInitialized = false
    private var apiKey : String = ""
    private const val BASE_URL = "http://192.168.0.202:8001/v1/admin/user"

    fun initialize(context: Context) {
        if (!isInitialized) {
            // Perform any required SDK setup
            isInitialized = true
        }
    }

    fun generateApiKey(email: String, onResult: (String?) -> Unit) {
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
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        println("API key request failed: ${it.code}")
                        onResult(null)
                        return
                    }

                    val responseData = it.body?.string()
                    val key = try {
                        val jsonResponse = JSONObject(responseData ?: "")
                        jsonResponse.optString("apiKey", null)
                    } catch (e: Exception) {
                        null
                    }

                    if (key != null) {
                        apiKey = key
                        println("Generated API Key: $apiKey")
                    }
                    onResult(key)
                }
            }
        })
    }



fun uploadDocuments(file: File, apiKey: String, context: Context) {
    println("Uploading file: $file with token: $apiKey")

    val client = OkHttpClient()

    // Detect MIME Type based on file extension
    val mimeType = getMimeType(file) ?: "application/octet-stream"

    // Create RequestBody for file with detected MIME type
    val fileRequestBody = file.asRequestBody(mimeType.toMediaTypeOrNull())

    // Build Multipart Body
    val multipartBody = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("file", file.name, fileRequestBody)
        .build()

    // Build Request
    val request = Request.Builder()
        .url("$BASE_URL/file/upload")
        .post(multipartBody)
        .addHeader( "x-api-key",apiKey)
        .addHeader("Accept", "*/*")
        .build()

    // Execute Request
    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            println("API call failed: ${e.message}")
        }

        override fun onResponse(call: Call, response: Response) {
            response.use {
                if (!response.isSuccessful) {
                    println("API Response: ${response.body?.string()}")
                    println("Unexpected response: ${response.code} ${response.message}")
                } else {
                    val responseBody = response.body?.string()
                    println("API Response: $responseBody")
                    responseBody?.let {
                        val jsonObject = JSONObject(it)
                        val id = jsonObject.getString("_id") // Extract _id from JSON
                        println("Extracted ID: $id")
                        downloadpdf(id, apiKey, context)
                    }
                }
            }
        }
    })
}

    // Function to detect file MIME type based on extension
    fun getMimeType(file: File): String? {
        return when (file.extension.lowercase()) {
            "pdf" -> "application/pdf"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "txt" -> "text/plain"
            "html" -> "text/html"
            "csv" -> "text/csv"
            else -> null // Default will be handled as "application/octet-stream"
        }
    }


    private fun downloadpdf(id: String, api_key: String, context: Context) {
//        val pdfUrl = "http://10.0.2.2:8001/v1/admin/user/file/download/$id"
            val pdfUrl = "$BASE_URL/file/download/$id"
        println("urlll=="+id+"<<"+pdfUrl)

            val savedFilePath = downloadFileWithToken(context, pdfUrl, api_key, "signed_file.pdf")

            if (savedFilePath != null) {
                println("PDF downloaded at: $savedFilePath")
            } else {
                println("Failed to download PDF")
            }
    }

     fun downloadFileWithToken(
        context: Context,
        fileUrl: String,
        api_key: String,
        defaultFileName: String
    ) {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url(fileUrl)
                .addHeader( "x-api-key",api_key)
                .build()

            try {
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) throw IOException("Failed to download file: ${response.message}")

                val inputStream = response.body?.byteStream() ?: throw IOException("Empty response body")

                // Detect MIME type from the response
                val contentType = response.header("Content-Type") ?: "application/octet-stream"
                val fileExtension = when {
                    contentType.contains("pdf", true) -> ".pdf"
                    contentType.contains("json", true) -> ".json"
                    contentType.contains("xml", true) -> ".xml"
                    else -> ".bin" // Default for unknown file types
                }

                val fileName = defaultFileName.substringBeforeLast(".") + fileExtension

                val filePath: String?
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, contentType)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        ?: throw IOException("Failed to create file in Downloads")

                    resolver.openOutputStream(uri).use { outputStream ->
                        inputStream.copyTo(outputStream!!)
                    }
                    filePath = uri.toString()
                } else {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val file = File(downloadsDir, fileName)

                    FileOutputStream(file).use { output ->
                        inputStream.copyTo(output)
                    }
                    filePath = file.absolutePath
                }
                filePath
            }
            catch (e: IOException) {
                e.printStackTrace()
                null
            }
    }


    fun verifyDocuments(file: File, api_key: String, context: Context) {
        println("Uploading file: $file with token: $api_key")

        val client = OkHttpClient()

        // Detect MIME Type based on file extension
        val mimeType = MySDK.getMimeType(file) ?: "application/octet-stream"

        // Create RequestBody for file with detected MIME type
        val fileRequestBody = file.asRequestBody(mimeType.toMediaTypeOrNull())

        // Build Multipart Body
        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, fileRequestBody)
            .build()

        // Build Request
        val request = Request.Builder()
            .url("$BASE_URL/file/verification")
            .post(multipartBody)
            .addHeader( "x-api-key",api_key)
            .addHeader("Accept", "*/*")
            .build()

        // Execute Request
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                println("API call failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        println("API Response: ${response.body?.string()}")
                        println("Unexpected response: ${response.code} ${response.message}")
                    } else {
                        val responseBody = response.body?.string()
                        println("API Response: $responseBody")
                    }
                }
            }
        })
    }

    fun fetchDocuments(api_key: String) {
        val client = OkHttpClient()

        val request = Request.Builder()
            .url("$BASE_URL/file/documents")
            .addHeader( "x-api-key",api_key)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                println("API call failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        println("API Response: ${response.body?.string()}")
                        println("Unexpected response: ${response.code} ${response.message}")
                    } else {
                        val responseBody = response.body?.string()
                        println("API Response: $responseBody")
                    }
                }
            }
        })
    }
}


