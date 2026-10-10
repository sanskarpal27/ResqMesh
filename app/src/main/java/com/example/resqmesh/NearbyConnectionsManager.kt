package com.example.resqmesh

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.nio.charset.StandardCharsets
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manages the Google Nearby Connections lifecycle for ResQMesh.
 *
 * Uses [Strategy.P2P_CLUSTER] so that all devices in proximity can discover
 * and connect to each other (many-to-many mesh foundation).
 *
 * Thread-safety: all state mutation happens inside [lock].
 */
class NearbyConnectionsManager(
    context: Context,
    private val listener: Listener
) {

    // ── Public interface ──────────────────────────────────────────────────────

    interface Listener {
        /** Called on the calling thread (main or bg — caller decides). */
        fun onStatusChanged(status: String)
        fun onPeerConnected(endpointId: String, endpointName: String)
        fun onPeerDisconnected(endpointId: String)
        fun onMessageReceived(senderName: String, message: String)
        fun onError(message: String)
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "NearbyMgr"

        /** Service ID uniquely identifies this app's mesh network. */
        private const val SERVICE_ID = "com.example.resqmesh.MESH"

        private val STRATEGY = Strategy.P2P_CLUSTER
    }

    // ── State (guarded by lock) ───────────────────────────────────────────────

    private val lock = ReentrantLock()

    /** Endpoints we have successfully completed connection to (endpointId → name). */
    private val connectedPeers = mutableMapOf<String, String>()

    private val pendingConnections = mutableSetOf<String>()

    /** Cache of endpoint names from onConnectionInitiated, needed for onConnectionResult */
    private val pendingNames = mutableMapOf<String, String>()

    private var isAdvertising = false
    private var isDiscovering = false

    // ── Nearby Connections client ─────────────────────────────────────────────

    private val connectionsClient: ConnectionsClient =
        Nearby.getConnectionsClient(context.applicationContext)

    /** Human-readable name broadcast to nearby peers. */
    val localEndpointName: String = android.os.Build.MODEL
        .replace(Regex("[^A-Za-z0-9 ]"), "")
        .take(20)
        .ifBlank { "ResQDevice" }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Start advertising + discovering simultaneously so both sides can
     * initiate connections (P2P_CLUSTER pattern).
     */
    fun startMesh() {
        startAdvertising()
        startDiscovering()
    }

    /** Stop all Nearby activity and disconnect all peers. */
    fun stopMesh() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        lock.withLock {
            isAdvertising = false
            isDiscovering = false
            connectedPeers.clear()
            pendingConnections.clear()
            pendingNames.clear()
        }
        listener.onStatusChanged("Mesh stopped")
    }

    /** Send a UTF-8 text message to every connected peer. */
    fun sendMessage(text: String) {
        val peers = lock.withLock { connectedPeers.keys.toList() }
        if (peers.isEmpty()) {
            listener.onError("No connected peers to send to")
            return
        }
        val payload = Payload.fromBytes(text.toByteArray(StandardCharsets.UTF_8))
        peers.forEach { endpointId ->
            connectionsClient.sendPayload(endpointId, payload)
                .addOnFailureListener { e ->
                    listener.onError("Send failed to $endpointId: ${e.message}")
                }
        }
    }

    /** Returns a snapshot of currently connected peer names. */
    fun getConnectedPeerNames(): List<String> =
        lock.withLock { connectedPeers.values.toList() }

    // ── Advertising ───────────────────────────────────────────────────────────

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).setLowPower(true).build()
        connectionsClient.startAdvertising(
            localEndpointName,
            SERVICE_ID,
            connectionLifecycleCallback,
            options
        ).addOnSuccessListener {
            lock.withLock { isAdvertising = true }
            Log.i(TAG, "Advertising (low-power) started as '$localEndpointName'")
            listener.onStatusChanged("Advertising as $localEndpointName…")
        }.addOnFailureListener { e ->
            val statusCode = (e as? com.google.android.gms.common.api.ApiException)?.statusCode
            Log.e(TAG, "Advertising (low-power) failed with status: $statusCode", e)
            listener.onError("Advertising failed: status $statusCode, ${e.message}")
        }
    }

    // ── Discovery ─────────────────────────────────────────────────────────────

    private fun startDiscovering() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).setLowPower(true).build()
        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            options
        ).addOnSuccessListener {
            lock.withLock { isDiscovering = true }
            Log.i(TAG, "Discovery (low-power) started")
            listener.onStatusChanged("Advertising + discovering…")
        }.addOnFailureListener { e ->
            val statusCode = (e as? com.google.android.gms.common.api.ApiException)?.statusCode
            Log.e(TAG, "Discovery (low-power) failed with status: $statusCode", e)
            listener.onError("Discovery failed: status $statusCode, ${e.message}")
        }
    }

    // ── Endpoint discovery callback ───────────────────────────────────────────

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.i(TAG, "Endpoint found: $endpointId (${info.endpointName})")

            val shouldConnect = lock.withLock {
                val alreadyConnected = connectedPeers.containsKey(endpointId)
                val alreadyPending = pendingConnections.contains(endpointId)
                if (!alreadyConnected && !alreadyPending) {
                    pendingConnections.add(endpointId)
                    true
                } else {
                    false
                }
            }

            if (shouldConnect) {
                connectionsClient.requestConnection(
                    localEndpointName,
                    endpointId,
                    connectionLifecycleCallback
                ).addOnFailureListener { e ->
                    lock.withLock { pendingConnections.remove(endpointId) }
                    Log.e(TAG, "requestConnection failed: ${e.message}")
                    listener.onError("Connection request failed: ${e.message}")
                }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.i(TAG, "Endpoint lost: $endpointId")
            // If we were pending, clean up — peer went away before we connected.
            lock.withLock { 
                pendingConnections.remove(endpointId)
                pendingNames.remove(endpointId)
            }
        }
    }

    // ── Connection lifecycle callback ─────────────────────────────────────────

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.i(TAG, "Connection initiated from ${info.endpointName} ($endpointId)")
            lock.withLock { pendingNames[endpointId] = info.endpointName }
            // Auto-accept all incoming connections for the mesh prototype.
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            val nameFromInitiation = lock.withLock { 
                pendingConnections.remove(endpointId) 
                pendingNames.remove(endpointId)
            }

            when (resolution.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    val peerName = nameFromInitiation ?: endpointId.take(8)
                    lock.withLock {
                        connectedPeers[endpointId] = peerName
                    }
                    Log.i(TAG, "Connected to $endpointId ($peerName)")
                    listener.onPeerConnected(endpointId, peerName)
                    listener.onStatusChanged("Connected to $peerName")
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Log.w(TAG, "Connection rejected by $endpointId")
                    listener.onError("Connection rejected by $endpointId")
                }
                ConnectionsStatusCodes.STATUS_ERROR -> {
                    Log.e(TAG, "Connection error with $endpointId: ${resolution.status.statusMessage}")
                    listener.onError("Connection error: ${resolution.status.statusMessage}")
                }
                else -> {
                    Log.w(TAG, "Unknown status ${resolution.status.statusCode} for $endpointId")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            val name = lock.withLock {
                val n = connectedPeers.remove(endpointId)
                pendingConnections.remove(endpointId)
                pendingNames.remove(endpointId)
                n ?: endpointId.take(8)
            }
            Log.i(TAG, "Disconnected from $endpointId ($name)")
            listener.onPeerDisconnected(endpointId)
            listener.onStatusChanged("Disconnected from $name")
        }
    }

    // ── Payload callback ──────────────────────────────────────────────────────

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val bytes = payload.asBytes() ?: return
                val text = String(bytes, StandardCharsets.UTF_8)
                val senderName = lock.withLock { connectedPeers[endpointId] ?: endpointId.take(8) }
                Log.i(TAG, "Message from $senderName: $text")
                listener.onMessageReceived(senderName, text)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Only bytes payloads are used; no need to track transfer progress.
        }
    }

}
