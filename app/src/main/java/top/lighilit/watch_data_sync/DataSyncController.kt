package top.lighilit.watch_data_sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import android.util.Base64
import java.lang.reflect.UndeclaredThrowableException

/** Xiaomi wearable bridge wrapper. The vendor AAR is loaded from app/libs at runtime. */
class DataSyncController(
    private val context: Context,
    private val onError: (String, Throwable) -> Unit = { _, _ -> }
) {
    private val callbacks = mutableMapOf<String, (JSONObject) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var authApi: Any? = null
    private var messageApi: Any? = null
    private var listener: Any? = null
    private var currentNodeId: String? = null

    fun registerActionCallback(action: String, callback: (JSONObject) -> Unit) {
        callbacks[action] = callback
    }

    fun connect(onStatus: (String) -> Unit, registerMessages: Boolean = true) {
        runCatching {
            val wearable = Class.forName("com.xiaomi.xms.wearable.Wearable")
            val nodeApi = wearable.getMethod("getNodeApi", Context::class.java).invoke(null, context)
            authApi = wearable.getMethod("getAuthApi", Context::class.java).invoke(null, context)
            messageApi = wearable.getMethod("getMessageApi", Context::class.java).invoke(null, context)
            val task = requireNotNull(
                nodeApi.javaClass.getMethod("getConnectedNodes").invoke(nodeApi)
            ) { "getConnectedNodes returned no task" }
            addTaskListener(task, "addOnSuccessListener") { nodes ->
                reportErrors("Connecting to Xiaomi wearable") {
                    val node = (nodes as? Iterable<*>)?.firstOrNull()
                    currentNodeId = node?.let { readStringProperty(it, "id") }
                    val id = currentNodeId
                    if (id == null) {
                        postStatus(onStatus, context.getString(R.string.no_paired_watch))
                    } else {
                        authorize(id, onStatus, registerMessages)
                    }
                }
            }
            addTaskListener(task, "addOnFailureListener") { error ->
                postStatus(onStatus, context.getString(R.string.connect_failed, errorMessage(error)))
            }
        }.onFailure {
            if (it is ClassNotFoundException) {
                postStatus(onStatus, context.getString(R.string.sdk_missing))
            } else {
                reportError("Initializing Xiaomi wearable SDK", it)
                postStatus(onStatus, context.getString(R.string.connect_failed, errorMessage(it)))
            }
        }
    }

    fun requestPermission(onStatus: (String) -> Unit, registerMessages: Boolean = true) {
        val id = currentNodeId ?: return postStatus(onStatus, context.getString(R.string.no_paired_watch))
        requestDeviceManagerPermission(id, onStatus, registerMessages)
    }

    fun sendText(text: String, onStatus: (String) -> Unit) {
        sendJson(JSONObject().put("type", "text").put("content", text), onStatus)
    }

    fun sendProtocolVersion(onStatus: (String) -> Unit) {
        sendJson(JSONObject().put("type", "protocol").put("version", PROTOCOL_VERSION), onStatus)
    }


    fun sendJson(payload: JSONObject, onStatus: (String) -> Unit) {
        val api = messageApi ?: return postStatus(onStatus, context.getString(R.string.not_connected))
        val id = currentNodeId ?: return postStatus(onStatus, context.getString(R.string.no_paired_watch))
        runCatching {
            val bytes = payload.toString().toByteArray(Charsets.UTF_8)
            val method = api.javaClass.methods.first {
                it.name == "sendMessage" && it.parameterTypes.size == 2
            }
            val task = requireNotNull(method.invoke(api, id, bytes)) {
                "sendMessage returned no task"
            }
            addTaskListener(task, "addOnSuccessListener") { postStatus(onStatus, "sent") }
            addTaskListener(task, "addOnFailureListener") { error ->
                if (error is Throwable) reportError("Sending message to watch", error)
                postStatus(onStatus, context.getString(R.string.send_failed, errorMessage(error)))
            }
        }.onFailure {
            reportError("Sending message to watch", it)
            postStatus(onStatus, context.getString(R.string.send_failed, it.message ?: "SDK error"))
        }
    }

    /**
     * The Xiaomi bridge delivers messages to the watch as strings, so raw bytes
     * are corrupted. Image bytes are sent as base64 (1.33x size, ASCII-safe); the
     * watch appends each chunk to the image file with native base64 decoding.
     */
    fun sendImageChunk(id: Int, key: String, offset: Int, total: Int, payload: ByteArray, onStatus: (String) -> Unit) {
        val data = "{\"type\":\"image_chunk\",\"id\":$id,\"key\":\"$key\",\"offset\":$offset,\"length\":${payload.size}," +
            "\"total\":$total,\"data\":\"${Base64.encodeToString(payload, Base64.NO_WRAP)}\"}"
        sendBytes(data.toByteArray(Charsets.US_ASCII), onStatus)
    }

    private fun sendBytes(bytes: ByteArray, onStatus: (String) -> Unit) {
        val api = messageApi ?: return postStatus(onStatus, context.getString(R.string.not_connected))
        val id = currentNodeId ?: return postStatus(onStatus, context.getString(R.string.no_paired_watch))
        runCatching {
            val method = api.javaClass.methods.first { it.name == "sendMessage" && it.parameterTypes.size == 2 }
            val task = requireNotNull(method.invoke(api, id, bytes)) { "sendMessage returned no task" }
            addTaskListener(task, "addOnSuccessListener") { postStatus(onStatus, "sent") }
            addTaskListener(task, "addOnFailureListener") { error ->
                if (error is Throwable) reportError("Sending message to watch", error)
                postStatus(onStatus, context.getString(R.string.send_failed, errorMessage(error)))
            }
        }.onFailure {
            reportError("Sending message to watch", it)
            postStatus(onStatus, context.getString(R.string.send_failed, it.message ?: "SDK error"))
        }
    }

    fun close() {
        val api = messageApi ?: return
        listener ?: return
        runCatching {
            api.javaClass.methods.firstOrNull {
                it.name == "removeListener" && it.parameterTypes.size == 1
            }?.invoke(api, currentNodeId)
        }.onFailure { reportError("Removing Xiaomi message listener", it) }
        listener = null
    }

    private fun registerMessageListener(nodeId: String, onStatus: (String) -> Unit) {
        val api = messageApi ?: return
        val listenerClass = Class.forName(
            "com.xiaomi.xms.wearable.message.OnMessageReceivedListener"
        )
        listener = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "DataSyncMessageListener"
                "onMessageReceived" -> reportErrors("Receiving message from watch") {
                    val bytes = args?.lastOrNull() as? ByteArray
                    if (bytes != null) handleMessage(bytes)
                }
                else -> null
            }
        }
        val task = requireNotNull(api.javaClass.methods.first {
            it.name == "addListener" && it.parameterTypes.size == 2
        }.invoke(api, nodeId, listener)) { "addListener returned no task" }
        addTaskListener(task, "addOnSuccessListener") {
            postStatus(onStatus, context.getString(R.string.connected_authorized))
        }
        addTaskListener(task, "addOnFailureListener") { error ->
            reportSdkFailure("Registering watch message listener", error)
            listener = null
            postStatus(onStatus, context.getString(R.string.listener_failed, errorMessage(error)))
        }
    }

    private fun authorize(nodeId: String, onStatus: (String) -> Unit, registerMessages: Boolean) {
        val api = authApi ?: return postStatus(onStatus, context.getString(R.string.authorization_unavailable))
        reportErrors("Checking Xiaomi wearable permission") {
            val permission = deviceManagerPermission()
            val method = api.javaClass.methods.first {
                it.name == "checkPermission" && it.parameterTypes.size == 2
            }
            val task = requireNotNull(method.invoke(api, nodeId, permission)) {
                "checkPermission returned no task"
            }
            addTaskListener(task, "addOnSuccessListener") { granted ->
                if (granted == true) {
                    finishConnection(nodeId, onStatus, registerMessages)
                } else {
                    requestDeviceManagerPermission(nodeId, onStatus, registerMessages)
                }
            }
            addTaskListener(task, "addOnFailureListener") { error ->
                reportSdkFailure("Checking Xiaomi wearable permission", error)
                postStatus(onStatus, context.getString(R.string.permission_check_failed, errorMessage(error)))
            }
        }
    }

    private fun requestDeviceManagerPermission(
        nodeId: String,
        onStatus: (String) -> Unit,
        registerMessages: Boolean
    ) {
        val api = authApi ?: return postStatus(onStatus, context.getString(R.string.authorization_unavailable))
        postStatus(onStatus, context.getString(R.string.grant_permission))
        reportErrors("Requesting Xiaomi wearable permission") {
            val permissionClass = Class.forName("com.xiaomi.xms.wearable.auth.Permission")
            val permission = deviceManagerPermission()
            val permissions = java.lang.reflect.Array.newInstance(permissionClass, 1)
            java.lang.reflect.Array.set(permissions, 0, permission)
            val method = api.javaClass.methods.first {
                it.name == "requestPermission" && it.parameterTypes.size == 2
            }
            val task = requireNotNull(method.invoke(api, nodeId, permissions)) {
                "requestPermission returned no task"
            }
            addTaskListener(task, "addOnSuccessListener") { granted ->
                if (arrayContainsPermission(granted, "data_manager")) {
                    finishConnection(nodeId, onStatus, registerMessages)
                } else {
                    postStatus(onStatus, context.getString(R.string.watch_permission_denied))
                }
            }
            addTaskListener(task, "addOnFailureListener") { error ->
                reportSdkFailure("Requesting Xiaomi wearable permission", error)
                postStatus(onStatus, context.getString(R.string.permission_denied))
            }
        }
    }

    private fun finishConnection(
        nodeId: String,
        onStatus: (String) -> Unit,
        registerMessages: Boolean
    ) {
        reportErrors("Registering Xiaomi message listener") {
            if (!registerMessages) {
                postStatus(onStatus, context.getString(R.string.connected_authorized))
            } else if (listener == null) {
                registerMessageListener(nodeId, onStatus)
            } else {
                postStatus(onStatus, context.getString(R.string.connected_authorized))
            }
        }
    }

    private fun deviceManagerPermission(): Any {
        val permissionClass = Class.forName("com.xiaomi.xms.wearable.auth.Permission")
        return requireNotNull(permissionClass.getField("DEVICE_MANAGER").get(null)) {
            "DEVICE_MANAGER permission is unavailable"
        }
    }

    private fun arrayContainsPermission(value: Any?, expectedName: String): Boolean {
        if (value == null || !value.javaClass.isArray) return false
        return (0 until java.lang.reflect.Array.getLength(value)).any { index ->
            val permission = java.lang.reflect.Array.get(value, index) ?: return@any false
            readStringProperty(permission, "name") == expectedName
        }
    }

    /** Called on the main thread for every watch action, before its registered callback. */
    var onAction: ((JSONObject) -> Unit)? = null

    private fun handleMessage(bytes: ByteArray) {
        runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.onSuccess { json ->
            if (json.optString("type") == "action") {
                val callback = callbacks[json.optString("action")]
                mainHandler.post {
                    reportErrors("Running watch action callback") {
                        onAction?.invoke(json)
                        callback?.invoke(json)
                    }
                }
            }
        }
    }

    private fun addTaskListener(task: Any, methodName: String, callback: (Any?) -> Unit) {
        val method = task.javaClass.methods.first { it.name == methodName && it.parameterTypes.size == 1 }
        val listenerClass = method.parameterTypes[0]
        val listener = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { proxy, invoked, args ->
            when (invoked.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "DataSyncTaskListener"
                else -> reportErrors("Handling Xiaomi SDK task result") {
                    callback(args?.firstOrNull())
                }
            }
        }
        method.invoke(task, listener)
    }

    private fun readStringProperty(target: Any, name: String): String? {
        val getterName = "get${name.replaceFirstChar(Char::uppercaseChar)}"
        val getter = target.javaClass.methods.firstOrNull {
            it.name == getterName && it.parameterTypes.isEmpty()
        }
        if (getter != null) return getter.invoke(target) as? String
        return target.javaClass.getField(name).get(target) as? String
    }

    private fun errorMessage(error: Any?): String = when (error) {
        is Throwable -> error.message ?: error.javaClass.simpleName
        null -> "unknown error"
        else -> error.toString()
    }

    private fun reportSdkFailure(source: String, error: Any?) {
        if (error is Throwable) reportError(source, error)
    }

    private fun postStatus(callback: (String) -> Unit, status: String) {
        mainHandler.post { reportErrors("Updating connection status") { callback(status) } }
    }

    private inline fun reportErrors(source: String, block: () -> Unit) {
        runCatching(block).onFailure { reportError(source, it) }
    }

    private fun reportError(source: String, throwable: Throwable) {
        val cause = unwrap(throwable)
        mainHandler.post { onError(source, cause) }
    }

    private fun unwrap(throwable: Throwable): Throwable = when (throwable) {
        is InvocationTargetException -> throwable.targetException?.let(::unwrap) ?: throwable
        is UndeclaredThrowableException -> throwable.undeclaredThrowable?.let(::unwrap) ?: throwable
        else -> throwable
    }
}
