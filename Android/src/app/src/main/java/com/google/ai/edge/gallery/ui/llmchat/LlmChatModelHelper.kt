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

package com.google.ai.edge.gallery.ui.llmchat

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.llm.LlamaCpp
import com.google.ai.edge.gallery.llm.TokenCallback

private const val TAG = "AGLlmChatModelHelper"

typealias ResultListener = (partialResult: String, done: Boolean) -> Unit

typealias CleanUpListener = () -> Unit

data class LlmModelInstance(
  val handle: Long,
  val modelPath: String,
  val nCtx: Int,
  // Stub conversation for backward compat with custom tasks (MobileActions, TinyGarden).
  // These tasks will return placeholder responses until migrated to llama.cpp.
  val conversation: com.google.ai.edge.litertlm.Conversation = com.google.ai.edge.litertlm.Conversation(),
)

object LlmChatModelHelper {
  private val cleanUpListeners: MutableMap<String, CleanUpListener> = mutableMapOf()

  fun initialize(
    context: Context,
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    onDone: (String) -> Unit,
    systemMessage: Any? = null,
    tools: List<Any> = listOf(),
    enableConversationConstrainedDecoding: Boolean = false,
  ) {
    val maxTokens =
      model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
    Log.d(TAG, "Initializing llama.cpp model...")

    val modelPath = model.getPath(context = context)

    if (!LlamaCpp.isAvailable()) {
      onDone("llama.cpp native library not available")
      return
    }

    try {
      // Use 0 GPU layers on emulator/CPU, could be configurable later
      val nGpuLayers = 0
      val nCtx = maxTokens.coerceIn(512, 4096)

      val handle = LlamaCpp.initModel(modelPath, nCtx, nGpuLayers)
      if (handle == 0L) {
        onDone("Failed to load model: $modelPath")
        return
      }

      model.instance = LlmModelInstance(
        handle = handle,
        modelPath = modelPath,
        nCtx = nCtx,
      )

      Log.d(TAG, "Model initialized successfully: handle=$handle")
    } catch (e: Exception) {
      onDone("Error initializing model: ${e.message}")
      return
    }
    onDone("")
  }

  fun resetConversation(
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    systemMessage: Any? = null,
    tools: List<Any> = listOf(),
    enableConversationConstrainedDecoding: Boolean = false,
  ) {
    // llama.cpp KV cache is cleared after each completion in our JNI wrapper,
    // so no explicit reset is needed. If we want multi-turn, we'd need to
    // manage conversation history and re-prompt.
    Log.d(TAG, "Reset conversation (no-op for llama.cpp stateless mode)")
  }

  fun cleanUp(model: Model, onDone: () -> Unit) {
    if (model.instance == null) {
      return
    }

    val instance = model.instance as LlmModelInstance

    try {
      LlamaCpp.releaseModel(instance.handle)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to release model: ${e.message}")
    }

    val onCleanUp = cleanUpListeners.remove(model.name)
    if (onCleanUp != null) {
      onCleanUp()
    }
    model.instance = null

    onDone()
    Log.d(TAG, "Clean up done.")
  }

  fun runInference(
    model: Model,
    input: String,
    resultListener: ResultListener,
    cleanUpListener: CleanUpListener,
    onError: (message: String) -> Unit = {},
    images: List<Bitmap> = listOf(),
    audioClips: List<ByteArray> = listOf(),
  ) {
    val instance = model.instance as LlmModelInstance

    if (!cleanUpListeners.containsKey(model.name)) {
      cleanUpListeners[model.name] = cleanUpListener
    }

    val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
    val topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
    val temperature =
      model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
    val maxTokens =
      model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)

    try {
      val callback = object : TokenCallback {
        override fun onToken(token: String) {
          resultListener(token, false)
        }
      }

      LlamaCpp.completion(
        handle = instance.handle,
        prompt = input,
        nPredict = maxTokens,
        temperature = temperature,
        topK = topK,
        topP = topP,
        stopSequences = "",
        callback = callback,
      )

      // Signal done
      resultListener("", true)
    } catch (e: Exception) {
      Log.e(TAG, "Inference error", e)
      onError("Error: ${e.message}")
    }
  }
}
