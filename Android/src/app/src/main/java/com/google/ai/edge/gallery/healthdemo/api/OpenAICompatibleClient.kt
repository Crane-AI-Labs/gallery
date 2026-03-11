/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.healthdemo.api

import com.google.gson.Gson
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for OpenAI-compatible API endpoints.
 * Uses HttpURLConnection to avoid external dependencies.
 */
class OpenAICompatibleClient {
    
    private val gson = Gson()
    
    /**
     * Send a chat completion request to an OpenAI-compatible endpoint.
     * 
     * @param endpoint The API endpoint URL
     * @param apiKey Optional API key for authentication
     * @param systemPrompt System prompt to guide the model
     * @param userMessage User message/query
     * @param imageBase64 Optional base64-encoded image
     * @return The model's response text
     */
    fun chatCompletion(
        endpoint: String,
        apiKey: String?,
        model: String = "medgemma",
        systemPrompt: String,
        userMessage: String,
        imageBase64: String? = null
    ): String {
        val messages = mutableListOf<Map<String, Any>>()
        
        // Add system message
        messages.add(mapOf(
            "role" to "system",
            "content" to systemPrompt
        ))
        
        // Add user message with optional image
        val userContent = if (imageBase64 != null) {
            listOf(
                mapOf("type" to "text", "text" to userMessage),
                mapOf(
                    "type" to "image_url",
                    "image_url" to mapOf("url" to "data:image/png;base64,$imageBase64")
                )
            )
        } else {
            userMessage
        }
        
        messages.add(mapOf(
            "role" to "user",
            "content" to userContent
        ))
        
        val requestBody = mapOf(
            "model" to model,
            "messages" to messages,
            "temperature" to 0.7
        )
        
        val json = gson.toJson(requestBody)
        
        val url = URL(endpoint)
        val connection = url.openConnection() as HttpURLConnection
        
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            if (!apiKey.isNullOrEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            connection.doOutput = true
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            
            // Write request body
            DataOutputStream(connection.outputStream).use { it.writeBytes(json) }
            
            // Read response
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("API request failed: $responseCode ${connection.responseMessage} (URL: $endpoint)")
            }
            
            val response = BufferedReader(InputStreamReader(connection.inputStream)).use {
                it.readText()
            }
            
            val responseJson = gson.fromJson(response, Map::class.java)
            val choices = responseJson["choices"] as? List<*>
                ?: throw IOException("Invalid response format: missing 'choices'")
            
            val firstChoice = choices.firstOrNull() as? Map<*, *>
                ?: throw IOException("Invalid response format: empty choices")
            
            val message = firstChoice["message"] as? Map<*, *>
                ?: throw IOException("Invalid response format: missing 'message'")
            
            return message["content"] as? String
                ?: throw IOException("Invalid response format: missing 'content'")
        } finally {
            connection.disconnect()
        }
    }
    
    /**
     * Send an audio transcription request to an OpenAI-compatible endpoint.
     * 
     * @param endpoint The API endpoint URL
     * @param apiKey Optional API key for authentication
     * @param audioBytes Audio file bytes
     * @return The transcribed text
     */
    fun transcribe(
        endpoint: String,
        apiKey: String?,
        audioBytes: ByteArray
    ): String {
        val boundary = "----WebKitFormBoundary" + System.currentTimeMillis()
        val url = URL(endpoint)
        val connection = url.openConnection() as HttpURLConnection
        
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (!apiKey.isNullOrEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            connection.doOutput = true
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            
            // Build multipart body
            DataOutputStream(connection.outputStream).use { output ->
                // Add file part
                output.writeBytes("--$boundary\r\n")
                output.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n")
                output.writeBytes("Content-Type: audio/wav\r\n\r\n")
                output.write(audioBytes)
                output.writeBytes("\r\n")
                
                // Add model part
                output.writeBytes("--$boundary\r\n")
                output.writeBytes("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
                output.writeBytes("medasr\r\n")
                
                // End boundary
                output.writeBytes("--$boundary--\r\n")
            }
            
            // Read response
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Transcription request failed: $responseCode ${connection.responseMessage}")
            }
            
            val response = BufferedReader(InputStreamReader(connection.inputStream)).use {
                it.readText()
            }
            
            val responseJson = gson.fromJson(response, Map::class.java)
            return responseJson["text"] as? String
                ?: throw IOException("Invalid response format: missing 'text'")
        } finally {
            connection.disconnect()
        }
    }
}
