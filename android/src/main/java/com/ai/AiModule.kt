package com.ai

import ai.mlc.mlcllm.OpenAIProtocol
import ai.mlc.mlcllm.OpenAIProtocol.ChatCompletionMessage
import android.os.Environment
import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.bridge.ReactContext.RCTDeviceEventEmitter
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.turbomodule.core.interfaces.TurboModule
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.channels.Channels
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@ReactModule(name = AiModule.NAME)
class AiModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext),
    TurboModule {

    override fun getName(): String = NAME

    companion object {
        const val NAME = "Ai"

        const val APP_CONFIG_FILENAME = "mlc-app-config.json"
        const val MODEL_CONFIG_FILENAME = "mlc-chat-config.json"
        const val PARAMS_CONFIG_FILENAME = "ndarray-cache.json"
        const val MODEL_URL_SUFFIX = "/resolve/main/"
    }

    private var appConfig = AppConfig(
        emptyList<String>().toMutableList(),
        emptyList<ModelRecord>().toMutableList()
    )
    private val gson = Gson()
    private lateinit var chat: Chat // Consider initializing or making nullable if not always set before use

    private fun getAppConfig(): AppConfig {
        val appConfigFile = File(reactApplicationContext.applicationContext.getExternalFilesDir(""), APP_CONFIG_FILENAME)

        val jsonString: String = if (appConfigFile.exists()) {
            appConfigFile.readText()
        } else {
            reactApplicationContext.applicationContext.assets.open(APP_CONFIG_FILENAME).bufferedReader().use { it.readText() }
        }

        return gson.fromJson(jsonString, AppConfig::class.java)
    }

    private suspend fun getModelConfig(modelRecord: ModelRecord): ModelConfig {
        downloadModelConfig(modelRecord)

        val modelDirFile = File(reactApplicationContext.getExternalFilesDir(""), modelRecord.modelId)
        val modelConfigFile = File(modelDirFile, MODEL_CONFIG_FILENAME)

        val jsonString: String = if (modelConfigFile.exists()) {
            modelConfigFile.readText()
        } else {
            throw Error("Requested model config not found")
        }

        val modelConfig = gson.fromJson(jsonString, ModelConfig::class.java)

        modelConfig.apply {
            this.modelId = modelRecord.modelId // Use 'this' for clarity if needed
            this.modelUrl = modelRecord.modelUrl
            this.modelLib = modelRecord.modelLib
        }

        return modelConfig
    }

    @ReactMethod
    fun getModel(name: String, promise: Promise) {
        appConfig = getAppConfig()

        val modelRecord = appConfig.modelList.find { it.modelId == name } // Renamed for clarity

        if (modelRecord == null) {
            promise.reject("Model not found", "Didn't find the model with id: $name")
            return
        }

        val modelDetails = Arguments.createMap().apply {
            putString("modelId", modelRecord.modelId)
            putString("modelLib", modelRecord.modelLib)
            putString("modelUrl", modelRecord.modelUrl) // Example: add more relevant fields
            // Consider adding estimatedVramBytes if useful to the JS side
            // modelRecord.estimatedVramBytes?.let { putDouble("estimatedVramBytes", it.toDouble()) }
        }

        promise.resolve(modelDetails)
    }

    @ReactMethod
    fun getModels(promise: Promise) {
        try {
            appConfig = getAppConfig()
            // The line `appConfig.modelLibs = emptyList<String>().toMutableList()`
            // clears any previously loaded modelLibs. This might be intentional.

            val modelsArray = Arguments.createArray().apply {
                for (modelRecord in appConfig.modelList) {
                    pushMap(Arguments.createMap().apply {
                        putString("model_id", modelRecord.modelId)
                        // You might want to include more details per model here as well
                        // putString("model_lib", modelRecord.modelLib)
                        // putString("model_url", modelRecord.modelUrl)
                    })
                }
            }
            promise.resolve(modelsArray)
        } catch (e: Exception) {
            promise.reject("GET_MODELS_ERROR", "Error getting models", e)
        }
    }

    @ReactMethod
    fun doGenerate(instanceId: String, messages: ReadableArray, promise: Promise) {
        val messageList = mutableListOf<ChatCompletionMessage>()

        for (i in 0 until messages.size()) {
            // Safely access ReadableMap
            messages.getMap(i)?.let { messageMap ->
                val roleString = messageMap.getString("role")
                val role = if (roleString == "user") OpenAIProtocol.ChatCompletionRole.user else OpenAIProtocol.ChatCompletionRole.assistant
                val content = messageMap.getString("content") ?: "" // Default to empty string if content is null
                messageList.add(ChatCompletionMessage(role, content))
            } ?: run {
                // Optional: Log or handle if a map at a given index is null
                Log.w(NAME, "Null message map found at index $i in doGenerate for instance $instanceId")
            }
        }

        // Ensure 'chat' is initialized before calling methods on it
        if (!this::chat.isInitialized) {
            promise.reject("CHAT_NOT_INITIALIZED", "Chat is not initialized. Call prepareModel first.")
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                chat.generateResponse(
                    messageList,
                    callback = object : Chat.GenerateCallback {
                        override fun onMessageReceived(message: String) {
                            promise.resolve(message)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(NAME, "Error generating response for instance $instanceId", e)
                // It's good practice to reject the promise on error in async operations too
                promise.reject("GENERATION_ERROR", "Error generating response: ${e.message}", e)
            }
        }
    }

    @ReactMethod
    fun doStream(instanceId: String, messages: ReadableArray, promise: Promise) {
        val messageList = mutableListOf<ChatCompletionMessage>()

        for (i in 0 until messages.size()) {
            // Safely access ReadableMap
            messages.getMap(i)?.let { messageMap ->
                val roleString = messageMap.getString("role")
                val role = if (roleString == "user") OpenAIProtocol.ChatCompletionRole.user else OpenAIProtocol.ChatCompletionRole.assistant
                val content = messageMap.getString("content") ?: "" // Default to empty string if content is null
                messageList.add(ChatCompletionMessage(role, content))
            } ?: run {
                // Optional: Log or handle if a map at a given index is null
                Log.w(NAME, "Null message map found at index $i in doStream for instance $instanceId")
            }
        }

        // Ensure 'chat' is initialized
        if (!this::chat.isInitialized) {
            promise.reject("CHAT_NOT_INITIALIZED", "Chat is not initialized. Call prepareModel first.")
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                chat.streamResponse(
                    messageList,
                    callback = object : Chat.StreamCallback {
                        override fun onUpdate(message: String) {
                            val event: WritableMap = Arguments.createMap().apply {
                                putString("content", message)
                                putString("instanceId", instanceId) // Good to include instanceId in event
                            }
                            sendEvent("onChatUpdate", event)
                        }

                        override fun onFinished(message: String) {
                            val event: WritableMap = Arguments.createMap().apply {
                                putString("content", message)
                                putString("instanceId", instanceId) // Good to include instanceId in event
                            }
                            sendEvent("onChatComplete", event)
                        }
                    }
                )
                // The original doStream resolved the promise immediately.
                // Depending on desired behavior, you might resolve it after streaming starts
                // or rely on events for completion signals.
                // For now, keeping original behavior of resolving promise early.
                promise.resolve("Streaming started for instance $instanceId")
            } catch (e: Exception) {
                Log.e(NAME, "Error streaming response for instance $instanceId", e)
                promise.reject("STREAMING_ERROR", "Error streaming response: ${e.message}", e)
            }
        }
        // If the coroutine launch is meant to be fire-and-forget and promise resolved early:
        // promise.resolve(null) // Or a more descriptive message
        // However, it's generally better to resolve/reject the promise based on the async operation's outcome.
        // The current placement inside the coroutine (if it were to resolve after streamResponse returns)
        // is more robust, but streamResponse itself is likely non-blocking and uses callbacks.
        // I've moved promise.resolve into the try block for stream start.
    }


    @ReactMethod
    fun downloadModel(instanceId: String, promise: Promise) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val currentAppConfig = getAppConfig() // Use local variable
                val modelRecord = currentAppConfig.modelList.find { it.modelId == instanceId }
                if (modelRecord == null) {
                    // Ensure promise is rejected on the main thread if required by React Native
                    withContext(Dispatchers.Main) {
                        promise.reject("MODEL_NOT_FOUND", "There's no record for requested model: $instanceId")
                    }
                    return@launch
                }

                val modelConfig = getModelConfig(modelRecord)
                val modelDir = File(reactApplicationContext.getExternalFilesDir(""), modelConfig.modelId)
                val modelState = ModelState(modelConfig, modelDir)

                modelState.initialize()

                sendEvent("onDownloadStart", Arguments.createMap().apply { putString("modelId", instanceId) })

                // This nested launch for progress might be complex to manage.
                // Consider restructuring if it causes issues with lifecycle or multiple calls.
                val progressJob = CoroutineScope(Dispatchers.IO).launch {
                    modelState.progress.collect { newValue ->
                        val event: WritableMap = Arguments.createMap().apply {
                            // Avoid division by zero if total is 0
                            val percentage = if (modelState.total.intValue > 0) { // FIXED
                                (newValue.toDouble() / modelState.total.intValue) * 100 // FIXED
                            } else {
                                0.0
                            }
                            putDouble("percentage", percentage)
                            putString("modelId", instanceId)
                        }
                        sendEvent("onDownloadProgress", event)
                    }
                }

                modelState.download()
                progressJob.cancel() // Cancel progress collection once download is complete

                sendEvent("onDownloadComplete", Arguments.createMap().apply { putString("modelId", instanceId) })
                withContext(Dispatchers.Main) { promise.resolve("Model downloaded: $instanceId") }

            } catch (e: Exception) {
                Log.e(NAME, "Error downloading model $instanceId", e)
                sendEvent("onDownloadError", Arguments.createMap().apply {
                    putString("modelId", instanceId)
                    putString("error", e.message ?: "Unknown error")
                })
                withContext(Dispatchers.Main) { promise.reject("DOWNLOAD_ERROR", "Error downloading model $instanceId: ${e.message}", e) }
            }
        }
    }

    private fun sendEvent(eventName: String, data: Any?) {
        // Ensure context is still valid, especially if events can be sent after module tear-down
        if (reactApplicationContext.hasActiveCatalystInstance()) {
            reactApplicationContext.getJSModule(RCTDeviceEventEmitter::class.java)?.emit(eventName, data)
        }
    }

    @ReactMethod
    fun prepareModel(instanceId: String, promise: Promise) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val currentAppConfig = getAppConfig() // Use local variable
                // ▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼
                // THE FIX IS HERE: Changed currentAppGcurrentAppConfigonfig to currentAppConfig
                // ▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼
                val modelRecord = currentAppConfig.modelList.find { it.modelId == instanceId }

                if (modelRecord == null) {
                    withContext(Dispatchers.Main) {
                        promise.reject("MODEL_NOT_FOUND", "There's no record for requested model for preparation: $instanceId")
                    }
                    return@launch
                }
                val modelConfig = getModelConfig(modelRecord)
                val modelDir = File(reactApplicationContext.getExternalFilesDir(""), modelConfig.modelId)

                // It seems ModelState might handle download if not present.
                // If initialize/download are lengthy, consider sending progress events.
                val modelState = ModelState(modelConfig, modelDir)
                modelState.initialize() // What if this throws? Caught by outer try-catch.
                if (!modelDir.exists() || modelDir.listFiles()?.isEmpty() == true) { // Basic check if download is needed
                    Log.i(NAME, "Model files not found for $instanceId, attempting download within prepareModel.")
                    modelState.download() // Ensure model is downloaded
                }


                chat = Chat(modelConfig, modelDir) // This initializes the lateinit var

                withContext(Dispatchers.Main) { promise.resolve("Model prepared: $instanceId") }
            } catch (e: Exception) {
                Log.e(NAME, "Error preparing model $instanceId", e)
                withContext(Dispatchers.Main) { promise.reject("PREPARATION_ERROR", "Error preparing model $instanceId: ${e.message}", e) }
            }
        }
    }

    private suspend fun downloadModelConfig(modelRecord: ModelRecord) {
        withContext(Dispatchers.IO) {
            val modelDirFile = File(reactApplicationContext.getExternalFilesDir(""), modelRecord.modelId) // Changed modelFile to modelDirFile for clarity
            val modelConfigFile = File(modelDirFile, MODEL_CONFIG_FILENAME)

            // Check if the specific config file exists, not just the directory
            if (modelConfigFile.exists()) {
                Log.i(NAME, "Model config for ${modelRecord.modelId} already exists. Skipping download.")
                return@withContext
            }

            // Ensure model directory exists before trying to place files in it
            if (!modelDirFile.exists()) {
                modelDirFile.mkdirs()
            }

            val url = URL("${modelRecord.modelUrl}${MODEL_URL_SUFFIX}$MODEL_CONFIG_FILENAME")
            val tempId = UUID.randomUUID().toString()
            // It's generally safer to use app-specific cache or files directory for temps
            val tempFile = File(reactApplicationContext.cacheDir, "$tempId.json")


            Log.i(NAME, "Downloading model config for ${modelRecord.modelId} from $url")
            url.openStream().use { inputStream ->
                Channels.newChannel(inputStream).use { src ->
                    FileOutputStream(tempFile).use { fileOutputStream ->
                        fileOutputStream.channel.transferFrom(src, 0, Long.MAX_VALUE)
                    }
                }
            }
            require(tempFile.exists()) { "Temporary config file download failed for ${modelRecord.modelId}" }


            val modelConfigString = tempFile.readText()
            // gson.fromJson can throw JsonSyntaxException, which will be caught by the calling method's try-catch
            val modelConfig = gson.fromJson(modelConfigString, ModelConfig::class.java).apply {
                this.modelId = modelRecord.modelId // Use 'this' for clarity
                this.modelLib = modelRecord.modelLib
                this.estimatedVramBytes = modelRecord.estimatedVramBytes
                // modelUrl is already part of ModelConfig, so modelRecord.modelUrl should align or be primary.
                // If modelRecord.modelUrl is the source of truth, ensure it's set here if different from config.
                this.modelUrl = modelRecord.modelUrl
            }

            tempFile.copyTo(modelConfigFile, overwrite = true)
            tempFile.delete()
            Log.i(NAME, "Successfully downloaded and saved model config for ${modelRecord.modelId}")
            return@withContext
        }
    }

    // Add @ReactMethod addListener and removeListeners if you plan to send events often
    // and want to follow the new TurboModule/EventEmitter guidelines.
    // For now, direct RCTDeviceEventEmitter is used.
    @ReactMethod
    fun addListener(eventName: String) {
      // Keep: Required for RN built in Event Emitter Calls.
    }

    @ReactMethod
    fun removeListeners(count: Int) {
      // Keep: Required for RN built in Event Emitter Calls.
    }
}

// Data classes seem fine, no immediate changes needed for null safety based on errors.
// Ensure properties are nullable if they can indeed be absent in JSON.
enum class ModelChatState {
    Generating,
    Resetting,
    Reloading,
    Terminating,
    Ready,
    Failed
}

data class MessageData(val role: String, val text: String, val id: UUID = UUID.randomUUID())

data class ModelConfig(
    @SerializedName("model_lib") var modelLib: String,
    @SerializedName("model_id") var modelId: String,
    @SerializedName("model_url") var modelUrl: String, // Added this as it was used in apply
    @SerializedName("estimated_vram_bytes") var estimatedVramBytes: Long?,
    @SerializedName("tokenizer_files") val tokenizerFiles: List<String>, // Ensure JSON matches or handle potential null
    @SerializedName("context_window_size") val contextWindowSize: Int?, // Made nullable if can be absent
    @SerializedName("prefill_chunk_size") val prefillChunkSize: Int? // Made nullable if can be absent
)

data class AppConfig(
    @SerializedName("model_libs") var modelLibs: MutableList<String>,
    @SerializedName("model_list") val modelList: MutableList<ModelRecord>
)

data class ModelRecord(
    @SerializedName("model_url") val modelUrl: String,
    @SerializedName("model_id") val modelId: String,
    @SerializedName("estimated_vram_bytes") val estimatedVramBytes: Long?,
    @SerializedName("model_lib") val modelLib: String
)

// These data classes seem unused in the AiModule provided, but are kept for completeness
data class DownloadTask(val url: URL, val file: File)
data class ParamsConfig(@SerializedName("records") val paramsRecords: List<ParamsRecord>)
data class ParamsRecord(@SerializedName("dataPath") val dataPath: String)

// You'll also need to define the Chat class and ModelState class or ensure they are compatible.
// This example assumes they exist and function as intended.
// For example, a placeholder for Chat and ModelState:

/*
class Chat(private val modelConfig: ModelConfig, private val modelDir: File) {
    interface GenerateCallback {
        fun onMessageReceived(message: String)
    }
    interface StreamCallback {
        fun onUpdate(message: String)
        fun onFinished(message: String)
    }

    fun generateResponse(messages: List<ChatCompletionMessage>, callback: GenerateCallback) {
        // Implementation for generating response
        callback.onMessageReceived("Generated: Hello!")
    }

    fun streamResponse(messages: List<ChatCompletionMessage>, callback: StreamCallback) {
        // Implementation for streaming response
        callback.onUpdate("Streaming part 1...")
        callback.onFinished("Streaming complete: Hello World!")
    }
}

class ModelState(private val config: ModelConfig, private val modelDir: File) {
    val progress = kotlinx.coroutines.flow.MutableStateFlow(0)
    val total = java.util.concurrent.atomic.AtomicInteger(100) // Example total

    fun initialize() {
        // Initialize model state
        Log.i(AiModule.NAME, "ModelState initialized for ${config.modelId}")
    }

    suspend fun download() {
        // Simulate download
        Log.i(AiModule.NAME, "ModelState download started for ${config.modelId}")
        for (i in 1..10) {
            kotlinx.coroutines.delay(100) // Simulate work
            progress.value = i * 10
        }
        Log.i(AiModule.NAME, "ModelState download complete for ${config.modelId}")
    }
}
*/