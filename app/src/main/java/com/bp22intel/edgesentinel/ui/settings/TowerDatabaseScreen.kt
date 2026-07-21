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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    val countries by viewModel.countries.collectAsState()
    val totalTowerCount by viewModel.totalTowerCount.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val importSuccess by viewModel.importSuccess.collectAsState()
    val downloadingCountry by viewModel.downloadingCountry.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()

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

    // Figure out which MCCs are already installed and compute grouped country list
    val installedMccs = countries.filter { it.isInstalled }.map { it.mcc }.toSet()
    val countryGroups = TowerDatabaseViewModel.AVAILABLE_COUNTRIES.map { group ->
        group.copy(
            isInstalled = group.mccs.any { it in installedMccs },
            towerCount = group.mccs.sumOf { mcc ->
                countries.find { it.mcc == mcc }?.towerCount ?: 0
            }
        )
    }
    val installedCountryCount = countryGroups.count { it.isInstalled }

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
                        text = "$totalTowerCount",
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
                            text = "$installedCountryCount countries installed",
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

        // Error/Success messages
        error?.let { msg ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFEF4444).copy(alpha = 0.15f))) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, style = MaterialTheme.typography.bodySmall, color = Color(0xFFEF4444), modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.clearError() }) { Text("OK", color = Color(0xFFEF4444)) }
                    }
                }
            }
        }
        importSuccess?.let { msg ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = StatusClear.copy(alpha = 0.15f))) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, style = MaterialTheme.typography.bodySmall, color = StatusClear, modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.clearSuccess() }) { Text("OK", color = StatusClear) }
                    }
                }
            }
        }

        // Download progress
        if (downloadingCountry != null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = AccentBlue.copy(alpha = 0.1f))) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AccentBlue, strokeWidth = 2.dp)
                        Text(downloadProgress, style = MaterialTheme.typography.bodySmall, color = AccentBlue)
                    }
                }
            }
        }

        // API Token section
        item {
            SectionHeader(title = "OpenCelliD API Token")
        }

        item {
            val currentToken by viewModel.apiToken.collectAsState()
            var tokenInput by remember { mutableStateOf(currentToken ?: "") }

            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "Required for downloading tower data. Get a free token at opencellid.org",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.OutlinedTextField(
                            value = tokenInput,
                            onValueChange = { tokenInput = it },
                            label = { Text("API Token", color = TextSecondary) },
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
                    if (!currentToken.isNullOrBlank()) {
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

        // One-tap install section
        item {
            SectionHeader(title = "Install Tower Database")
        }

        item {
            Text(
                "Tap a country to download and install its tower database. This verifies your connected towers against known legitimate ones.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }

        // Country install buttons — grouped by country (multi-MCC)
        items(countryGroups, key = { it.name }) { country ->
            val isDownloading = downloadingCountry == country.name
            
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Country info
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = country.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = if (country.isInstalled) "${country.towerCount} towers" else "MCC ${country.mccs.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (country.isInstalled) StatusClear else TextSecondary
                        )
                    }

                    if (country.isInstalled) {
                        // Installed — show checkmark + delete
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Installed",
                            tint = StatusClear,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = { viewModel.deleteCountry(country.mccs) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Remove",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else if (isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = AccentBlue,
                            strokeWidth = 2.dp
                        )
                    } else {
                        // Not installed — install button
                        Button(
                            onClick = { viewModel.installCountry(country.mccs, country.name) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentBlue,
                                contentColor = BackgroundPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Install", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        // Manual import as fallback (collapsed section)
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
            body = "Edge Sentinel ships with a database of 1.3 million known cell towers in the United States. When your phone connects to a tower, the app checks it against this database to determine if it's a recognized piece of infrastructure or something unknown."
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
            title = "What about towers outside the US?",
            body = "The bundled database covers the United States (MCC 310/311). For other countries, you can download additional tower data from the Tower Database screen. Travel Mode also uses country-specific threat profiles to adjust detection sensitivity when you're abroad."
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
