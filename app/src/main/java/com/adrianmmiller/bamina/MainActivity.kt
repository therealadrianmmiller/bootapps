package com.adrianmmiller.bamina

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var repository: BootAppsRepository
    private lateinit var adapter: BootAppsAdapter
    private lateinit var recyclerView: RecyclerView

    private var apps: MutableList<BootAppInfo> = mutableListOf()

    // Registers activity-result launchers for SAF file picking.
    private val exportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            repository.exportDisabledList(it, apps)
            Toast.makeText(this, "Exported disabled-apps list", Toast.LENGTH_SHORT).show()
        }
    }

    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { applyImportedDisableList(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repository = BootAppsRepository(applicationContext)

        recyclerView = findViewById(R.id.recycler_boot_apps)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = BootAppsAdapter(
            items = apps,
            onSelectionChanged = { _, _ -> /* selection tracked on the item itself */ },
            onRowClicked = { item -> repository.openAppInfoScreen(item.packageName) }
        )
        recyclerView.adapter = adapter

        loadApps()

        findViewById<Button>(R.id.button_select_all).setOnClickListener {
            val allSelected = apps.isNotEmpty() && apps.all { it.isSelected }
            adapter.selectAll(!allSelected)
        }

        findViewById<Button>(R.id.button_disable_selected).setOnClickListener {
            applyToSelected(enable = false)
        }

        findViewById<Button>(R.id.button_enable_selected).setOnClickListener {
            applyToSelected(enable = true)
        }

        findViewById<Button>(R.id.button_export).setOnClickListener {
            exportLauncher.launch("boot_apps_selection.json")
        }

        findViewById<Button>(R.id.button_import).setOnClickListener {
            importLauncher.launch(arrayOf("application/json"))
        }
    }

    private fun loadApps() {
        CoroutineScope(Dispatchers.Main).launch {
            val scanned = withContext(Dispatchers.IO) { repository.scanBootApps() }
            apps.clear()
            apps.addAll(scanned)
            adapter.updateData(apps)
            if (apps.isEmpty()) {
                Toast.makeText(
                    this@MainActivity,
                    "No third-party boot-start apps found",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Reads the block list from the given file and immediately disables every
     * matching app's boot receiver(s) — no separate button press needed.
     * Packages in the file that aren't installed (or have no boot receiver on
     * this device) are skipped and reported.
     */
    private fun applyImportedDisableList(uri: Uri) {
        CoroutineScope(Dispatchers.Main).launch {
            val wantedPackages = withContext(Dispatchers.IO) {
                repository.readDisabledListFromFile(uri)
            }
            if (wantedPackages.isEmpty()) {
                Toast.makeText(this@MainActivity, "File had no packages", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val matched = apps.filter { it.packageName in wantedPackages }
            val unmatched = wantedPackages - matched.map { it.packageName }.toSet()

            if (matched.isEmpty()) {
                Toast.makeText(
                    this@MainActivity,
                    "None of the ${wantedPackages.size} imported package(s) are installed here",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            matched.forEach { it.isSelected = true }
            adapter.notifyDataSetChanged()

            val results = withContext(Dispatchers.IO) {
                repository.applyEnabledState(matched, enable = false)
            }

            var successCount = 0
            var manualCount = 0
            var failCount = 0
            results.forEach { result ->
                when (result) {
                    is BootAppsRepository.ApplyResult.Success -> successCount++
                    is BootAppsRepository.ApplyResult.RequiresManualAction -> {
                        manualCount++
                        repository.openAppInfoScreen(result.packageName)
                    }
                    is BootAppsRepository.ApplyResult.Failed -> failCount++
                }
            }

            val msg = buildString {
                append("Disabled $successCount of ${matched.size} imported app(s). ")
                if (manualCount > 0) append("$manualCount need manual toggle. ")
                if (failCount > 0) append("$failCount failed. ")
                if (unmatched.isNotEmpty()) append("${unmatched.size} not installed here.")
            }
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()

            loadApps()
        }
    }

    private fun applyToSelected(enable: Boolean) {
        val selectedApps = apps.filter { it.isSelected }
        if (selectedApps.isEmpty()) {
            Toast.makeText(this, "No apps selected", Toast.LENGTH_SHORT).show()
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            val results = withContext(Dispatchers.IO) {
                repository.applyEnabledState(selectedApps, enable)
            }

            var successCount = 0
            var manualCount = 0
            var failCount = 0

            results.forEach { result ->
                when (result) {
                    is BootAppsRepository.ApplyResult.Success -> successCount++
                    is BootAppsRepository.ApplyResult.RequiresManualAction -> {
                        manualCount++
                        repository.openAppInfoScreen(result.packageName)
                    }
                    is BootAppsRepository.ApplyResult.Failed -> failCount++
                }
            }

            val msg = buildString {
                if (successCount > 0) append("$successCount applied via root. ")
                if (manualCount > 0) append("$manualCount need manual toggle (opened App Info). ")
                if (failCount > 0) append("$failCount failed.")
            }
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()

            loadApps() // refresh enabled/disabled badges
        }
    }
}
