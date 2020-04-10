package com.trustwallet.walletconnect

import android.util.Log
import com.github.salomonbrys.kotson.fromJson
import com.github.salomonbrys.kotson.registerTypeAdapter
import com.github.salomonbrys.kotson.typeToken
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.trustwallet.walletconnect.WCCipher.decrypt
import com.trustwallet.walletconnect.WCCipher.encrypt
import com.trustwallet.walletconnect.exceptions.InvalidJsonRpcParamsException
import com.trustwallet.walletconnect.extensions.hexStringToByteArray
import com.trustwallet.walletconnect.jsonrpc.JsonRpcError
import com.trustwallet.walletconnect.jsonrpc.JsonRpcErrorResponse
import com.trustwallet.walletconnect.jsonrpc.JsonRpcRequest
import com.trustwallet.walletconnect.jsonrpc.JsonRpcResponse
import com.trustwallet.walletconnect.models.*
import com.trustwallet.walletconnect.models.binance.*
import com.trustwallet.walletconnect.models.ethereum.WCEthereumSignMessage
import com.trustwallet.walletconnect.models.ethereum.WCEthereumTransaction
import com.trustwallet.walletconnect.models.session.WCApproveSessionResponse
import com.trustwallet.walletconnect.models.session.WCSession
import com.trustwallet.walletconnect.models.session.WCSessionRequest
import com.trustwallet.walletconnect.models.session.WCSessionUpdate
import okhttp3.*
import okio.ByteString
import java.util.*

const val JSONRPC_VERSION = "2.0"
const val WS_CLOSE_NORMAL = 1000

open class WCClient(
    private val httpClient: OkHttpClient,
    builder: GsonBuilder = GsonBuilder()
) : WebSocketListener() {
    private val TAG = "WCClient"

    private val gson = builder
        .serializeNulls()
        .registerTypeAdapter(cancelOrderSerializer)
        .registerTypeAdapter(cancelOrderDeserializer)
        .registerTypeAdapter(tradeOrderSerializer)
        .registerTypeAdapter(tradeOrderDeserializer)
        .registerTypeAdapter(transferOrderSerializer)
        .registerTypeAdapter(transferOrderDeserializer)
        .create()

    private var socket: WebSocket? = null

    private val listeners: MutableSet<WebSocketListener> = mutableSetOf()

    var session: WCSession? = null
        private set

    var peerMeta: WCPeerMeta? = null
        private set

    var peerId: String? = null
        private set

    var remotePeerId: String? = null
        private set

    var isConnected: Boolean = false
        private set

    private var handshakeId: Long = -1

    var onFailure: (topic: String, Throwable) -> Unit = { _, _ -> Unit }
    var onDisconnect: (topic: String, code: Int, reason: String) -> Unit = { _, _, _ -> Unit }
    var onSessionRequest: (topic: String, id: Long, peer: WCPeerMeta) -> Unit = { _, _, _ -> Unit }
    var onEthSign: (topic: String, id: Long, message: WCEthereumSignMessage) -> Unit =
        { _, _, _ -> Unit }
    var onEthSignTransaction: (topic: String, id: Long, transaction: WCEthereumTransaction) -> Unit =
        { _, _, _ -> Unit }
    var onEthSendTransaction: (topic: String, id: Long, transaction: WCEthereumTransaction) -> Unit =
        { _, _, _ -> Unit }
    var onCustomRequest: (topic: String, id: Long, payload: String) -> Unit = { _, _, _ -> Unit }
    var onBnbTrade: (topic: String, id: Long, order: WCBinanceTradeOrder) -> Unit =
        { _, _, _ -> Unit }
    var onBnbCancel: (topic: String, id: Long, order: WCBinanceCancelOrder) -> Unit =
        { _, _, _ -> Unit }
    var onBnbTransfer: (topic: String, id: Long, order: WCBinanceTransferOrder) -> Unit =
        { _, _, _ -> Unit }
    var onBnbTxConfirm: (topic: String, id: Long, order: WCBinanceTxConfirmParam) -> Unit =
        { _, _, _ -> Unit }
    var onGetAccounts: (topic: String, id: Long) -> Unit = { _, _ -> Unit }
    var onSignTransaction: (topic: String, id: Long, transaction: WCSignTransaction) -> Unit =
        { _, _, _ -> Unit }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        Log.d(TAG, "<< websocket opened >>")
        isConnected = true

        listeners.forEach { it.onOpen(webSocket, response) }

        val session = this.session ?: throw IllegalStateException("session can't be null on connection open")
        val peerId = this.peerId ?: throw IllegalStateException("peerId can't be null on connection open")
        // The Session.topic channel is used to listen session request messages only.
        subscribe(session.topic)
        // The peerId channel is used to listen to all messages sent to this httpClient.
        subscribe(peerId)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        var decrypted: String? = null
        try {
            Log.d(TAG, "<== message $text")

            val message = gson.fromJson<WCSocketMessage>(text)
            val encrypted = gson.fromJson<WCEncryptionPayload>(message.payload)
            val session = this.session
                ?: throw IllegalStateException("session can't be null on message receive")
            val payload =
                String(decrypt(encrypted, session.key.hexStringToByteArray()), Charsets.UTF_8)
            Log.d(TAG, "<== decrypted $payload")

            val request = gson.fromJson<JsonRpcRequest<JsonArray>>(
                payload,
                typeToken<JsonRpcRequest<JsonArray>>()
            )
            val method = request.method
            if (method != null) {
                handleRequest(request)
            } else {
                onCustomRequest(this.session!!.topic, request.id, payload)
            }
        } catch (e: InvalidJsonRpcParamsException) {
            invalidParams(e.requestId)
        } catch (e: Exception) {
            onFailure(this.session?.topic ?: "", e)
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        resetState()

        onFailure(this.session?.topic ?: "", t)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Log.d(TAG, "<< websocket closed >>")
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        Log.d(TAG, "<== pong")
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        Log.d(TAG, "<< closing socket >>")
        resetState()
        onDisconnect(this.session?.topic ?: "", code, reason)

//        onFailure(t)
//
//        listeners.forEach { it.onFailure(webSocket, t, response) }
//    }
//
//    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
//        Log.d(TAG,"<< websocket closed >>")
//
//        listeners.forEach { it.onClosed(webSocket, code, reason) }
//    }
//
//    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
//        Log.d(TAG,"<== pong")
//
//        listeners.forEach { it.onMessage(webSocket, bytes) }
//    }
//
//    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
//        Log.d(TAG,"<< closing socket >>")
//
//        resetState()
//        onDisconnect(code, reason)
//
//        listeners.forEach { it.onClosing(webSocket, code, reason) }
    }

    fun connect(
        session: WCSession,
        peerMeta: WCPeerMeta,
        peerId: String = UUID.randomUUID().toString(),
        remotePeerId: String? = null
    ) {
        if (this.session != null && this.session?.topic != session.topic) {
            killSession()
        }

        this.session = session
        this.peerMeta = peerMeta
        this.peerId = peerId
        this.remotePeerId = remotePeerId

        val request = Request.Builder()
            .url(session.bridge)
            .build()

        socket = httpClient.newWebSocket(request, this)
    }

    fun approveSession(accounts: List<String>, chainId: Int): Boolean {
        check(handshakeId > 0) { "handshakeId must be greater than 0 on session approve" }

        val result = WCApproveSessionResponse(
            chainId = chainId,
            accounts = accounts,
            peerId = peerId,
            peerMeta = peerMeta
        )
        val response = JsonRpcResponse(
            id = handshakeId,
            result = result
        )

        return encryptAndSend(gson.toJson(response))
    }

    fun updateSession(
        accounts: List<String>? = null,
        chainId: Int? = null,
        approved: Boolean = true
    ): Boolean {
        val request = JsonRpcRequest(
            id = generateId(),
            method = WCMethod.SESSION_UPDATE,
            params = listOf(
                WCSessionUpdate(
                    approved = approved,
                    chainId = chainId,
                    accounts = accounts
                )
            )
        )
        return encryptAndSend(gson.toJson(request))
    }

    fun rejectSession(message: String = "Session rejected"): Boolean {
        check(handshakeId > 0) { "handshakeId must be greater than 0 on session reject" }

        val response = JsonRpcErrorResponse(
            id = handshakeId,
            error = JsonRpcError.serverError(
                message = message
            )
        )
        return encryptAndSend(gson.toJson(response))
    }

    fun killSession(): Boolean {
        updateSession(approved = false)
        return disconnect()
    }

    fun <T> approveRequest(id: Long, result: T): Boolean {
        val response = JsonRpcResponse(
            id = id,
            result = result
        )
        return encryptAndSend(gson.toJson(response))
    }

    fun rejectRequest(id: Long, message: String = "Reject by the user"): Boolean {
        val response = JsonRpcErrorResponse(
            id = id,
            error = JsonRpcError.serverError(
                message = message
            )
        )
        return encryptAndSend(gson.toJson(response))
    }

    private fun decryptMessage(text: String): String {
        val message = gson.fromJson<WCSocketMessage>(text)
        val encrypted = gson.fromJson<WCEncryptionPayload>(message.payload)
        val session = this.session ?: throw IllegalStateException("session can't be null on message receive")
        return String(WCCipher.decrypt(encrypted, session.key.hexStringToByteArray()), Charsets.UTF_8)
    }

    private fun invalidParams(id: Long): Boolean {
        val response = JsonRpcErrorResponse(
            id = id,
            error = JsonRpcError.invalidParams(
                message = "Invalid parameters"
            )
        )

        return encryptAndSend(gson.toJson(response))
    }

    private fun handleRequest(request: JsonRpcRequest<JsonArray>) {
        when (request.method) {
            WCMethod.SESSION_REQUEST -> {
                val param = gson.fromJson<List<WCSessionRequest>>(request.params)
                    .firstOrNull() ?: throw InvalidJsonRpcParamsException(request.id)
                handshakeId = request.id
                remotePeerId = param.peerId
                onSessionRequest(this.session!!.topic, request.id, param.peerMeta)
            }
            WCMethod.SESSION_UPDATE -> {
                val param = gson.fromJson<List<WCSessionUpdate>>(request.params)
                    .firstOrNull() ?: throw InvalidJsonRpcParamsException(request.id)
                if (!param.approved) {
                    killSession()
                }
            }
            WCMethod.ETH_SIGN -> {
                val params = gson.fromJson<List<String>>(request.params)
                if (params.size < 2)
                    throw InvalidJsonRpcParamsException(request.id)
                onEthSign(
                    this.session!!.topic,
                    request.id,
                    WCEthereumSignMessage(params, WCEthereumSignMessage.WCSignType.MESSAGE)
                )
            }
            WCMethod.ETH_PERSONAL_SIGN -> {
                val params = gson.fromJson<List<String>>(request.params)
                if (params.size < 2)
                    throw InvalidJsonRpcParamsException(request.id)
                onEthSign(
                    this.session!!.topic,
                    request.id,
                    WCEthereumSignMessage(params, WCEthereumSignMessage.WCSignType.PERSONAL_MESSAGE)
                )
            }
            WCMethod.ETH_SIGN_TYPE_DATA -> {
                val params = gson.fromJson<List<String>>(request.params)
                if (params.size < 2)
                    throw InvalidJsonRpcParamsException(request.id)
                onEthSign(
                    this.session!!.topic,
                    request.id,
                    WCEthereumSignMessage(params, WCEthereumSignMessage.WCSignType.TYPED_MESSAGE)
                )
            }
            WCMethod.ETH_SIGN_TRANSACTION -> {
                val param = gson.fromJson<List<WCEthereumTransaction>>(request.params)
                    .firstOrNull() ?: throw InvalidJsonRpcParamsException(request.id)
                onEthSignTransaction(this.session!!.topic, request.id, param)
            }
            WCMethod.ETH_SEND_TRANSACTION -> {
                val param = gson.fromJson<List<WCEthereumTransaction>>(request.params)
                    .firstOrNull() ?: throw InvalidJsonRpcParamsException(request.id)
                onEthSendTransaction(this.session!!.topic, request.id, param)
            }
            WCMethod.BNB_SIGN -> {
                try {
                    val order = gson.fromJson<List<WCBinanceCancelOrder>>(request.params)
                        .firstOrNull() ?: throw InvalidJsonRpcParamsException(request.id)
                    onBnbCancel(this.session!!.topic, request.id, order)
                } catch (e: NoSuchElementException) {
                }

                try {
                    val order = gson.fromJson<List<WCBinanceTradeOrder>>(request.params)
                        .firstOrNull() ?: throw InvalidJsonRpcParamsException(request.id)
                    onBnbTrade(this.session!!.topic, request.id, order)
                } catch (e: NoSuchElementException) {
                }

                try {
                    val order = gson.fromJson<List<WCBinanceTransferOrder>>(request.params)
                        .firstOrNull() ?: throw InvalidJsonRpcParamsException(request.id)
                    onBnbTransfer(this.session!!.topic, request.id, order)
                } catch (e: NoSuchElementException) {
                }
            }
            WCMethod.BNB_TRANSACTION_CONFIRM -> {
                val param = gson.fromJson<List<WCBinanceTxConfirmParam>>(request.params)
                    .firstOrNull() ?: throw InvalidJsonRpcParamsException(request.id)
                onBnbTxConfirm(this.session!!.topic, request.id, param)
            }
            WCMethod.GET_ACCOUNTS -> {
                onGetAccounts(this.session!!.topic, request.id)
            }
            WCMethod.SIGN_TRANSACTION -> {
                val param = gson.fromJson<List<WCSignTransaction>>(request.params)
                    .firstOrNull() ?: throw InvalidJsonRpcParamsException(request.id)
                onSignTransaction(this.session!!.topic, request.id, param)
            }
        }
    }

    private fun subscribe(topic: String): Boolean {
        val message = WCSocketMessage(
            topic = topic,
            type = MessageType.SUB,
            payload = ""
        )
        val json = gson.toJson(message)
        Log.d(TAG, "==> subscribe $json")

        return socket?.send(gson.toJson(message)) ?: false
    }

    private fun encryptAndSend(result: String): Boolean {
        
        Log.d(TAG, "==> message $result")
        val session =
            this.session ?: throw IllegalStateException("session can't be null on message send")
        val payload = gson.toJson(
            encrypt(
                result.toByteArray(Charsets.UTF_8),
                session.key.hexStringToByteArray()
            )
        )
//        Log.d(TAG,"==> message $result")
//        val session = this.session ?: throw IllegalStateException("session can't be null on message send")
//        val payload = gson.toJson(WCCipher.encrypt(result.toByteArray(Charsets.UTF_8), session.key.hexStringToByteArray()))
        val message = WCSocketMessage(
            // Once the remotePeerId is defined, all messages must be sent to this channel. The session.topic channel
            // will be used only to respond the session request message.
            topic = remotePeerId ?: session.topic,
            type = MessageType.PUB,
            payload = payload
        )
        val json = gson.toJson(message)
        Log.d(TAG, "==> encrypted $json")

        return socket?.send(json) ?: false
    }


    fun disconnect(): Boolean {
        return socket?.close(WS_CLOSE_NORMAL, null) ?: false
    }

    fun addSocketListener(listener: WebSocketListener) {
        listeners.add(listener)
    }

    fun removeSocketListener(listener: WebSocketListener) {
        listeners.remove(listener)
    }

    private fun resetState() {
        handshakeId = -1
        isConnected = false
        session = null
        peerId = null
        remotePeerId = null
        peerMeta = null
    }
}

private fun generateId(): Long {
    return Date().time
}
