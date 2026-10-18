/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bp22intel.edgesentinel.ui.components.SectionHeader
import com.bp22intel.edgesentinel.ui.theme.AccentBlue
import com.bp22intel.edgesentinel.ui.theme.BackgroundPrimary
import com.bp22intel.edgesentinel.ui.theme.StatusClear
import com.bp22intel.edgesentinel.ui.theme.StatusElevated
import com.bp22intel.edgesentinel.ui.theme.Surface
import com.bp22intel.edgesentinel.ui.theme.SurfaceVariant
import com.bp22intel.edgesentinel.ui.theme.TextPrimary
import com.bp22intel.edgesentinel.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TowerDatabaseScreen(
    viewModel: TowerDatabaseViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    var showExplainer by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }

    val countries by viewModel.countries.collectAsState()
    val totalTowerCount by viewModel.totalTowerCount.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isOffline by viewModel.isOffline.collectAsState()
    val error by viewModel.error.collectAsState()
    val importSuccess by viewModel.importSuccess.collectAsState()
    val downloadingCountry by viewModel.downloadingCountry.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val apiToken by viewModel.apiToken.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedIso2s by viewModel.selectedIso2s.collectAsState()

    // Fallback: manual CSV import via file picker
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            try {
                context.contentResolver.openInputStream(selectedUri)?.let { inputStream ->
                    viewModel.importFromCsv(inputStream)
                } ?: viewModel.setError("Could not open file")
            } catch (e: Exception) {
                viewModel.setError("Import failed: ${e.message}")
            }
        }
    }

    val installedCountryCount = countries.count { it.isInstalled }
    val hasInstalledNonBaseCountries = countries.any { it.isInstalled && !it.isBase }
    val selectedCount = selectedIso2s.size

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "%,d".format(java.util.Locale.US, totalTowerCount),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (totalTowerCount > 0) StatusClear else TextSecondary
                    )
                    Text(
                        text = "Known Towers",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    if (totalTowerCount > 0) {
                        Text(
                            text = "$installedCountryCount countries installed (US & Canada built in)",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { showExplainer = true }) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "How tower verification works",
                            style = MaterialTheme.typography.bodySmall,
                            color = AccentBlue
                        )
                    }
                }
            }
        }

        // Attribution
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Cell tower data from OpenCelliD, CC BY-SA 4.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentBlue,
                    modifier = Modifier.clickable {
                        try {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://creativecommons.org/licenses/by-sa/4.0/")
                            )
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    }
                )
            }
        }

        // Offline notice
        if (isOffline) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Tower data mirror is unreachable (offline). Showing installed countries.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.loadCountryList(forceRefreshManifest = true) }) {
                            Text("Retry", color = AccentBlue)
                        }
                    }
                }
            }
        }

        // Error message
        error?.let { msg ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFEF4444).copy(alpha = 0.15f))) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFEF4444),
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("OK", color = Color(0xFFEF4444))
                        }
                    }
                }
            }
        }

        // Success message
        importSuccess?.let { msg ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = StatusClear.copy(alpha = 0.15f))) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusClear,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.clearSuccess() }) {
                            Text("OK", color = StatusClear)
                        }
                    }
                }
            }
        }

        // Download progress banner
        if (downloadingCountry != null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = AccentBlue.copy(alpha = 0.1f))) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = AccentBlue,
                            strokeWidth = 2.dp
                        )
                        Text(
                            downloadProgress,
                            style = MaterialTheme.typography.bodySmall,
                            color = AccentBlue
                        )
                    }
                }
            }
        }

        // Batch Action Buttons
        if (selectedCount > 0 || hasInstalledNonBaseCountries) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (selectedCount > 0) {
                        Button(
                            onClick = { viewModel.downloadSelected() },
                            enabled = downloadingCountry == null,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentBlue,
                                contentColor = BackgroundPrimary
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Download Selected ($selectedCount)",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    if (hasInstalledNonBaseCountries) {
                        Button(
                            onClick = { viewModel.updateAllInstalledCountries() },
                            enabled = downloadingCountry == null,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selectedCount > 0) SurfaceVariant else AccentBlue,
                                contentColor = if (selectedCount > 0) TextPrimary else BackgroundPrimary
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Update All Installed",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
        }

        // Search Box
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text("Search countries or MCC...", color = TextSecondary) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Search",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(
                                imageVector = Icons.Filled.Clear,
                                contentDescription = "Clear",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            )
        }

        // Collapsed Advanced section: OpenCelliD API Token
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { advancedExpanded = !advancedExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                "Advanced Settings",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                if (!apiToken.isNullOrBlank()) "OpenCelliD fallback token configured" else "Optional OpenCelliD token fallback",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Icon(
                            imageVector = if (advancedExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (advancedExpanded) "Collapse" else "Expand",
                            tint = TextSecondary
                        )
                    }

                    AnimatedVisibility(visible = advancedExpanded) {
                        var tokenInput by remember(apiToken) { mutableStateOf(apiToken ?: "") }

                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            Text(
                                "Optional fallback token from opencellid.org used only if the mirror download fails.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = tokenInput,
                                    onValueChange = { tokenInput = it },
                                    label = { Text("OpenCelliD API Token", color = TextSecondary) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(color = TextPrimary)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = { viewModel.setApiToken(tokenInput) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = AccentBlue,
                                        contentColor = BackgroundPrimary
                                    ),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Save", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            if (!apiToken.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "✅ Token saved",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StatusClear
                                )
                            }
                        }
                    }
                }
            }
        }

        // Country List Header
        item {
            SectionHeader(title = "Countries")
        }

        // Countries
        items(countries, key = { it.iso2 }) { country ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (country.isBase) {
                        // Base country: not selectable, marked Built in
                        Spacer(modifier = Modifier.width(6.dp))
                    } else {
                        // Checkbox for selection
                        Checkbox(
                            checked = country.isSelected,
                            onCheckedChange = { viewModel.toggleSelection(country.iso2) },
                            colors = CheckboxDefaults.colors(
                                checkedColor = AccentBlue,
                                checkmarkColor = BackgroundPrimary
                            )
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    // Country details
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = country.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            if (country.isBase) {
                                Text(
                                    text = "Built in",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusClear,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        val metaParts = mutableListOf<String>()
                        if (country.towerCount > 0) {
                            metaParts.add("%,d towers".format(java.util.Locale.US, country.towerCount))
                        }
                        if (country.bytes > 0) {
                            metaParts.add(formatBytes(country.bytes))
                        }
                        if (country.manifestDataDate.isNotBlank()) {
                            metaParts.add("Data: ${country.manifestDataDate}")
                        }
                        if (metaParts.isNotEmpty()) {
                            Text(
                                text = metaParts.joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }

                        // Status line
                        Text(
                            text = country.statusDisplay,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (country.status == CountryInstallStatus.UPDATE_AVAILABLE) FontWeight.SemiBold else FontWeight.Normal,
                            color = when (country.status) {
                                CountryInstallStatus.UPDATE_AVAILABLE -> StatusElevated
                                CountryInstallStatus.INSTALLED -> StatusClear
                                CountryInstallStatus.NOT_INSTALLED -> TextSecondary
                            }
                        )
                    }

                    // Delete button for non-base installed countries
                    if (!country.isBase && country.isInstalled) {
                        IconButton(
                            onClick = { viewModel.deleteCountry(country.iso2, country.mccs) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Remove ${country.name}",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // Manual import section (collapsed at bottom)
        item {
            Spacer(modifier = Modifier.height(8.dp))
            SectionHeader(title = "Manual Import")
        }

        item {
            OutlinedButton(
                onClick = { filePickerLauncher.launch("*/*") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.FileUpload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = TextSecondary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Import from CSV file", color = TextSecondary)
            }
        }

        item {
            Text(
                "Download CSV tower data from kaggle.com/datasets/aaborochin/cell-towers-from-opencellid",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item { Spacer(modifier = Modifier.height(32.dp)) }
    }

    // Explainer bottom sheet
    if (showExplainer) {
        ModalBottomSheet(
            onDismissRequest = { showExplainer = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Surface
        ) {
            TowerVerificationExplainer()
        }
    }
}

@Composable
private fun TowerVerificationExplainer() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Text(
            text = "HOW TOWER VERIFICATION WORKS",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = AccentBlue
        )
        Spacer(modifier = Modifier.height(16.dp))

        ExplainerSection(
            title = "What is this database?",
            body = "Edge Sentinel ships with a database of known cell towers in the United States and Canada. When your phone connects to a tower, the app checks it against this database to determine if it's a recognized piece of infrastructure or something unknown."
        )

        ExplainerSection(
            title = "Where does the data come from?",
            body = "The tower data comes from OpenCelliD, the world's largest crowdsourced cell tower database. Thousands of users around the world contribute tower observations by running apps that record which towers their phones connect to, along with GPS coordinates."
        )

        ExplainerSection(
            title = "Can this database be compromised?",
            body = "Yes — and that's important to understand. Because OpenCelliD is crowdsourced, an attacker could theoretically submit fake entries to \"whitelist\" their surveillance equipment. This is why Edge Sentinel uses a trust scoring system rather than a simple yes/no lookup."
        )

        Text(
            text = "TRUST SCORING",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = StatusElevated
        )
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Every tower in the database has a trust score based on how many independent observers confirmed it exists:",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(8.dp))

        TrustScoreRow("100+ observers", "High trust", StatusClear)
        TrustScoreRow("50–99 observers", "Good trust", StatusClear)
        TrustScoreRow("20–49 observers", "Moderate trust", StatusElevated)
        TrustScoreRow("10–19 observers", "Baseline trust", TextSecondary)

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "A tower that hundreds of people have connected to over several years is almost certainly legitimate. A tower with only a few observations could be real — or could be a planted entry.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(16.dp))

        ExplainerSection(
            title = "How does Edge Sentinel use this?",
            body = "The database is a soft signal, not a veto. A known tower with high trust reduces false positive alerts — you won't get warned about your neighborhood cell tower every day.\n\nBut if a known tower starts behaving suspiciously — sending cipher downgrades, stripping encryption, or flooding identity requests — Edge Sentinel will still alert you. Behavioral evidence always overrides database trust.\n\nThink of it like a background check: a clean record is reassuring, but it doesn't mean someone can't act suspiciously today."
        )

        ExplainerSection(
            title = "What about unknown towers?",
            body = "A tower not in the database isn't automatically dangerous. It could be new infrastructure, a temporary cell-on-wheels for an event, or coverage in an area the database doesn't cover well.\n\nEdge Sentinel flags unknown towers as worth watching, but only escalates to a serious alert when combined with other suspicious signals (high signal strength while stationary, cipher anomalies, network downgrades)."
        )

        ExplainerSection(
            title = "What about towers outside the US and Canada?",
            body = "The United States and Canada are built in. For other countries, you can download additional tower data from the Tower Database screen. Travel Mode also uses country-specific threat profiles to adjust detection sensitivity when you're abroad."
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun ExplainerSection(title: String, body: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = TextPrimary
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = body,
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondary,
        lineHeight = MaterialTheme.typography.bodySmall.lineHeight
    )
    Spacer(modifier = Modifier.height(16.dp))
}

@Composable
private fun TrustScoreRow(observers: String, label: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = observers,
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return ""
    return when {
        bytes >= 1_000_000 -> "%.1f MB".format(java.util.Locale.US, bytes / 1_000_000.0)
        bytes >= 1_000 -> "${bytes / 1_000} KB"
        else -> "$bytes B"
    }
}
