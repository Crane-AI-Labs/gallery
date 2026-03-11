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

package com.google.ai.edge.gallery.healthdemo.data

/**
 * Configuration for Health Demo API endpoints.
 */
data class HealthDemoApiConfig(
    /** MedGemma endpoint URL for medical guidance */
    val medGemmaEndpoint: String = "",
    
    /** API key for MedGemma endpoint (optional) */
    val medGemmaApiKey: String = "",
    
    /** ASR endpoint URL for voice transcription */
    val asrEndpoint: String = "http://185.69.165.233:8001/v1/audio/transcriptions",
    
    /** API key for ASR endpoint (optional) */
    val asrApiKey: String = "",
    
    /** System prompt for MedGemma to guide JSON output */
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT = """You are a medical AI assistant helping community health workers assess patients. 
Based on the symptoms provided (and optionally an image or audio recording), provide assessment guidance in JSON format.

IMPORTANT: You must respond with ONLY valid JSON. Do not include any conversational text, markdown formatting, or explanations outside the JSON block.

The JSON structure must be exactly:
{
  "guidanceSteps": [
    {
      "number": 1,
      "title": "Step title",
      "description": "Detailed description of what to do"
    }
  ],
  "nextSteps": [
    {
      "title": "Next step title",
      "description": "Description of the next step",
      "type": "MONITOR|TREATMENT|REFERRAL"
    }
  ]
}"""
    }
}
