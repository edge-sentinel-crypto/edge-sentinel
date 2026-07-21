/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * A clickable "What's this?" link that opens a bottom sheet with plain-English explanations
 * of any technical terms found in the given text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplainableText(
    text: String,
    modifier: Modifier = Modifier
) {
    val terms = remember(text) { ThreatGlossary.findRelevantTerms(text) }
    var showSheet by remember { mutableStateOf(false) }

    if (terms.isNotEmpty()) {
        Text(
            text = "What's this mean?",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF58A6FF),
            textDecoration = TextDecoration.Underline,
            modifier = modifier.clickable { showSheet = true }
        )

        if (showSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Color(0xFF161B22)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "Plain English",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE6EDF3)
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    terms.forEach { entry ->
                        GlossaryEntryCard(entry)
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

/**
 * Standalone "What's this?" link for a specific known term.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplainTerm(
    term: String,
    modifier: Modifier = Modifier,
    linkText: String = "What's this?"
) {
    val entry = remember(term) { ThreatGlossary.lookup(term) }
    var showSheet by remember { mutableStateOf(false) }

    if (entry != null) {
        Text(
            text = linkText,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF58A6FF),
            textDecoration = TextDecoration.Underline,
            modifier = modifier.clickable { showSheet = true }
        )

        if (showSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Color(0xFF161B22)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    Text(
                        "Plain English",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE6EDF3)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    GlossaryEntryCard(entry)
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun GlossaryEntryCard(entry: ThreatGlossary.GlossaryEntry) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            entry.term,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF58A6FF)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            entry.simple,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFE6EDF3)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            entry.detail,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF8B949E),
            lineHeight = MaterialTheme.typography.bodySmall.lineHeight
        )
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = Color(0xFF21262D))
    }
}
