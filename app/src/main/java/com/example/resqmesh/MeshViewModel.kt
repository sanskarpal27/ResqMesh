package com.example.resqmesh

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * Survives screen rotations and holds all UI-observable state for the mesh.
 * The [NearbyConnectionsManager] is created here so the connection persists
 * across orientation changes.
 */
class MeshViewModel(application: Application) : AndroidViewModel(application),
    NearbyConnectionsManager.Listener {

    // ── UI-observable state ───────────────────────────────────────────────────

    private val _statusText = MutableLiveData("Idle — tap Start Mesh")
    val statusText: LiveData<String> = _statusText

    private val _connectedPeers = MutableLiveData<List<String>>(emptyList())
    val connectedPeers: LiveData<List<String>> = _connectedPeers

    /** List of "Sender: message" strings for the log. */
    private val _messageLog = MutableLiveData<List<String>>(emptyList())
    val messageLog: LiveData<List<String>> = _messageLog

    private val _toastEvent = MutableLiveData<String?>()
    val toastEvent: LiveData<String?> = _toastEvent

    // ── Internal peer map (endpointId → displayName) ──────────────────────────

    @Volatile private var peersMap = LinkedHashMap<String, String>()
    private val peersLock = Any()

    // ── Nearby manager ────────────────────────────────────────────────────────

    val nearbyManager: NearbyConnectionsManager =
        NearbyConnectionsManager(application, this)

    private var meshActive = false

    // ── Public actions ────────────────────────────────────────────────────────

    fun startMesh() {
        if (meshActive) return
        meshActive = true
        _statusText.postValue("Starting mesh…")
        nearbyManager.startMesh()
    }

    fun stopMesh() {
        if (!meshActive) return
        meshActive = false
        nearbyManager.stopMesh()
        synchronized(peersLock) { peersMap.clear() }
        _connectedPeers.postValue(emptyList())
    }

    fun sendMessage(text: String) {
        nearbyManager.sendMessage(text)
        // Append our own message to the log immediately for responsiveness.
        appendLog("Me: $text")
    }

    fun clearToast() {
        _toastEvent.postValue(null)
    }

    // ── NearbyConnectionsManager.Listener ─────────────────────────────────────

    override fun onStatusChanged(status: String) {
        _statusText.postValue(status)
    }

    override fun onPeerConnected(endpointId: String, endpointName: String) {
        synchronized(peersLock) {
            peersMap[endpointId] = endpointName
            _connectedPeers.postValue(peersMap.values.toList())
        }
        appendLog("⬤ $endpointName joined the mesh")
    }

    override fun onPeerDisconnected(endpointId: String) {
        synchronized(peersLock) {
            val name = peersMap.remove(endpointId) ?: endpointId.take(8)
            _connectedPeers.postValue(peersMap.values.toList())
            appendLog("○ $name left the mesh")
        }
    }

    override fun onMessageReceived(senderName: String, message: String) {
        appendLog("$senderName: $message")
    }

    override fun onError(message: String) {
        _toastEvent.postValue(message)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun appendLog(entry: String) {
        val current = _messageLog.value?.toMutableList() ?: mutableListOf()
        current.add(entry)
        _messageLog.postValue(current)
    }

    // ── ViewModel lifecycle ───────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        nearbyManager.stopMesh()
    }
}
