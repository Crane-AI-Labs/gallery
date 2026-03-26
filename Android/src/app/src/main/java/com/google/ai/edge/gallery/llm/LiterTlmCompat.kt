@file:Suppress("unused")

/**
 * Compatibility stubs replacing com.google.ai.edge.litertlm types.
 *
 * These exist so that the MobileActions, TinyGarden, and LlmSingleTurn custom tasks
 * compile after removing the LiterTLM dependency. The actual functionality of those
 * tasks is non-operational until they are migrated to use llama.cpp directly.
 */

// Re-create the package path that existing imports expect.
package com.google.ai.edge.litertlm

/** Stub for ExperimentalApi opt-in annotation. */
@RequiresOptIn(message = "This is an experimental API (stub).")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class ExperimentalApi

/** Stub for ExperimentalFlags. */
object ExperimentalFlags {
  @ExperimentalApi
  var enableBenchmark: Boolean = false

  @ExperimentalApi
  var enableConversationConstrainedDecoding: Boolean = false
}

/** Stub for Content sealed class. */
sealed class Content {
  data class Text(val text: String) : Content()
  data class ImageBytes(val bytes: ByteArray) : Content()
  data class AudioBytes(val bytes: ByteArray) : Content()
}

/** Stub for Message. */
data class Message(val contents: List<Content>) {
  companion object {
    fun of(text: String): Message = Message(listOf(Content.Text(text)))
    fun of(contents: List<Content>): Message = Message(contents)
    fun of(vararg contents: Content): Message = Message(contents.toList())
  }

  override fun toString(): String {
    return contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
  }
}

/** Stub for Tool annotation. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Tool(val description: String = "")

/** Stub for ToolParam annotation. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class ToolParam(val description: String = "")

/** Stub for SamplerConfig. */
data class SamplerConfig(
  val topK: Int = 40,
  val topP: Double = 0.95,
  val temperature: Double = 1.0,
)

/** Stub for MessageCallback. */
interface MessageCallback {
  fun onMessage(message: Message)
  fun onDone()
  fun onError(throwable: Throwable)
}

/** Stub for BenchmarkInfo. */
data class BenchmarkInfo(val lastPrefillTokenCount: Int = 0)

/** Stub for Conversation. */
open class Conversation : AutoCloseable {
  fun sendMessage(message: Message): Message {
    return Message(listOf(Content.Text("[llama.cpp: conversation stub - not implemented]")))
  }

  fun sendMessageAsync(message: Message, callback: MessageCallback) {
    callback.onMessage(Message(listOf(Content.Text("[llama.cpp: conversation stub - not implemented]"))))
    callback.onDone()
  }

  fun sendMessageAsync(message: Message): kotlinx.coroutines.flow.Flow<Message> {
    return kotlinx.coroutines.flow.flow {
      emit(Message(listOf(Content.Text("[llama.cpp: conversation stub - not implemented]"))))
    }
  }

  fun cancelProcess() {}

  @ExperimentalApi
  fun getBenchmarkInfo(): BenchmarkInfo = BenchmarkInfo()

  override fun close() {}
}

/** Stub for ConversationConfig. */
data class ConversationConfig(
  val samplerConfig: SamplerConfig = SamplerConfig(),
  val systemMessage: Message? = null,
  val tools: List<Any> = listOf(),
)

/** Stub for Backend enum. */
enum class Backend { CPU, GPU }

/** Stub for EngineConfig. */
data class EngineConfig(
  val modelPath: String = "",
  val backend: Backend = Backend.CPU,
  val visionBackend: Backend? = null,
  val audioBackend: Backend? = null,
  val maxNumTokens: Int = 1024,
  val cacheDir: String? = null,
)

/** Stub for Engine. */
open class Engine(val config: EngineConfig = EngineConfig()) : AutoCloseable {
  fun initialize() {}
  fun createConversation(config: ConversationConfig = ConversationConfig()): Conversation = Conversation()
  override fun close() {}
}
