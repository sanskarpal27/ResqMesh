package com.example.resqmesh

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.resqmesh.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MeshViewModel by viewModels()

    // ── Permission request ────────────────────────────────────────────────────

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val denied = results.filterValues { !it }.keys
            if (denied.isEmpty()) {
                viewModel.startMesh()
            } else {
                val missing = denied.joinToString(", ") { it.substringAfterLast('.') }
                Toast.makeText(
                    this,
                    "Missing permissions: $missing. Cannot start mesh.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }

        setupObservers()
        setupClickListeners()
    }

    // ── Observers ─────────────────────────────────────────────────────────────

    private fun setupObservers() {
        viewModel.statusText.observe(this) { status ->
            binding.tvStatus.text = status
        }

        viewModel.connectedPeers.observe(this) { peers ->
            if (peers.isEmpty()) {
                binding.tvPeers.text = "No peers connected"
            } else {
                binding.tvPeers.text = peers.joinToString("\n") { "• $it" }
            }
            binding.tvPeerCount.text = "${peers.size} peer(s)"
        }

        viewModel.messageLog.observe(this) { log ->
            binding.tvMessageLog.text = log.joinToString("\n")
            // Auto-scroll to bottom
            binding.scrollViewLog.post {
                binding.scrollViewLog.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }

        viewModel.toastEvent.observe(this) { msg ->
            if (!msg.isNullOrBlank()) {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                viewModel.clearToast()
            }
        }
    }

    // ── Click listeners ───────────────────────────────────────────────────────

    private fun setupClickListeners() {
        binding.btnStartMesh.setOnClickListener {
            requestPermissionsAndStart()
        }

        binding.btnStopMesh.setOnClickListener {
            viewModel.stopMesh()
        }

        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString().trim()
            if (text.isBlank()) {
                Toast.makeText(this, "Message cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.sendMessage(text)
            binding.etMessage.setText("")
        }
    }

    // ── Permission handling ───────────────────────────────────────────────────

    private fun requestPermissionsAndStart() {
        val needed = requiredPermissions().filter { perm ->
            ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            viewModel.startMesh()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    /**
     * Returns the set of permissions actually needed on the current API level.
     * Mirrors the manifest declarations — only request what the OS understands.
     */
    private fun requiredPermissions(): List<String> {
        val perms = mutableListOf<String>()
        val sdk = Build.VERSION.SDK_INT

        // Diagnostic test: Coarse and Fine location required for Nearby Connections discovery
        perms += Manifest.permission.ACCESS_COARSE_LOCATION
        perms += Manifest.permission.ACCESS_FINE_LOCATION

        // Bluetooth — modern (API 31+)
        if (sdk >= 31) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_ADVERTISE
            perms += Manifest.permission.BLUETOOTH_CONNECT
        }

        // Nearby Wi-Fi (API 33+)
        if (sdk >= 33) {
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
        }

        return perms
    }
}