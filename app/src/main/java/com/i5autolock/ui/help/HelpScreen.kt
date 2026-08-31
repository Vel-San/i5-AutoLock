package com.i5autolock.ui.help

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.i5autolock.R
import com.i5autolock.ui.components.HeroBanner
import com.i5autolock.ui.theme.ambientBackground

/**
 * Static help / tutorial page. Explains every setting and how detection works. No ViewModel:
 * pure documentation rendered in-app so users never have to leave to understand a control.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize().background(ambientBackground()),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.help_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HeroBanner(
                title = stringResource(R.string.help_hero_title),
                subtitle = stringResource(R.string.help_hero_body),
                icon = Icons.AutoMirrored.Filled.HelpOutline,
                eyebrow = stringResource(R.string.help_hero_eyebrow),
            )

            HelpSection(
                stringResource(R.string.help_sec_flow),
                listOf(
                    stringResource(R.string.help_flow_1_t) to stringResource(R.string.help_flow_1_d),
                    stringResource(R.string.help_flow_2_t) to stringResource(R.string.help_flow_2_d),
                    stringResource(R.string.help_flow_3_t) to stringResource(R.string.help_flow_3_d),
                    stringResource(R.string.help_flow_4_t) to stringResource(R.string.help_flow_4_d),
                    stringResource(R.string.help_flow_5_t) to stringResource(R.string.help_flow_5_d),
                ),
            )

            HelpSection(
                stringResource(R.string.help_sec_home),
                listOf(
                    stringResource(R.string.help_home_1_t) to stringResource(R.string.help_home_1_d),
                    stringResource(R.string.help_home_2_t) to stringResource(R.string.help_home_2_d),
                    stringResource(R.string.help_home_3_t) to stringResource(R.string.help_home_3_d),
                    stringResource(R.string.help_home_4_t) to stringResource(R.string.help_home_4_d),
                    stringResource(R.string.help_home_5_t) to stringResource(R.string.help_home_5_d),
                ),
            )

            HelpSection(
                stringResource(R.string.help_sec_safety),
                listOf(
                    stringResource(R.string.help_safety_1_t) to stringResource(R.string.help_safety_1_d),
                    stringResource(R.string.help_safety_2_t) to stringResource(R.string.help_safety_2_d),
                    stringResource(R.string.help_safety_3_t) to stringResource(R.string.help_safety_3_d),
                    stringResource(R.string.help_safety_4_t) to stringResource(R.string.help_safety_4_d),
                ),
            )

            HelpSection(
                stringResource(R.string.help_sec_account),
                listOf(
                    stringResource(R.string.help_account_1_t) to stringResource(R.string.help_account_1_d),
                    stringResource(R.string.help_account_2_t) to stringResource(R.string.help_account_2_d),
                    stringResource(R.string.help_account_3_t) to stringResource(R.string.help_account_3_d),
                    stringResource(R.string.help_account_4_t) to stringResource(R.string.help_account_4_d),
                ),
            )

            HelpSection(
                stringResource(R.string.help_sec_vehbt),
                listOf(
                    stringResource(R.string.help_vehbt_1_t) to stringResource(R.string.help_vehbt_1_d),
                    stringResource(R.string.help_vehbt_2_t) to stringResource(R.string.help_vehbt_2_d),
                    stringResource(R.string.help_vehbt_3_t) to stringResource(R.string.help_vehbt_3_d),
                ),
            )

            HelpSection(
                stringResource(R.string.help_sec_detect),
                listOf(
                    stringResource(R.string.help_detect_1_t) to stringResource(R.string.help_detect_1_d),
                    stringResource(R.string.help_detect_2_t) to stringResource(R.string.help_detect_2_d),
                    stringResource(R.string.help_detect_3_t) to stringResource(R.string.help_detect_3_d),
                    stringResource(R.string.help_detect_4_t) to stringResource(R.string.help_detect_4_d),
                ),
            )

            HelpSection(
                stringResource(R.string.help_sec_timing),
                listOf(
                    stringResource(R.string.help_timing_1_t) to stringResource(R.string.help_timing_1_d),
                ),
            )

            HelpSection(
                stringResource(R.string.help_sec_diag),
                listOf(
                    stringResource(R.string.help_diag_1_t) to stringResource(R.string.help_diag_1_d),
                    stringResource(R.string.help_diag_2_t) to stringResource(R.string.help_diag_2_d),
                    stringResource(R.string.help_diag_3_t) to stringResource(R.string.help_diag_3_d),
                    stringResource(R.string.help_diag_4_t) to stringResource(R.string.help_diag_4_d),
                    stringResource(R.string.help_diag_5_t) to stringResource(R.string.help_diag_5_d),
                ),
            )

            HelpSection(
                stringResource(R.string.help_sec_perms),
                listOf(
                    stringResource(R.string.help_perms_1_t) to stringResource(R.string.help_perms_1_d),
                    stringResource(R.string.help_perms_2_t) to stringResource(R.string.help_perms_2_d),
                    stringResource(R.string.help_perms_3_t) to stringResource(R.string.help_perms_3_d),
                    stringResource(R.string.help_perms_4_t) to stringResource(R.string.help_perms_4_d),
                ),
            )

            HelpSection(
                stringResource(R.string.help_sec_signin),
                listOf(
                    stringResource(R.string.help_signin_1_t) to stringResource(R.string.help_signin_1_d),
                    stringResource(R.string.help_signin_2_t) to stringResource(R.string.help_signin_2_d),
                    stringResource(R.string.help_signin_3_t) to stringResource(R.string.help_signin_3_d),
                    stringResource(R.string.help_signin_5_t) to stringResource(R.string.help_signin_5_d),
                    stringResource(R.string.help_signin_6_t) to stringResource(R.string.help_signin_6_d),
                ),
            )

            HelpSection(
                stringResource(R.string.help_sec_reliab),
                listOf(
                    stringResource(R.string.help_reliab_1_t) to stringResource(R.string.help_reliab_1_d),
                    stringResource(R.string.help_reliab_2_t) to stringResource(R.string.help_reliab_2_d),
                    stringResource(R.string.help_reliab_3_t) to stringResource(R.string.help_reliab_3_d),
                    stringResource(R.string.help_reliab_4_t) to stringResource(R.string.help_reliab_4_d),
                ),
            )
        }
    }
}

@Composable
private fun HelpSection(title: String, items: List<Pair<String, String>>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            items.forEachIndexed { index, (name, desc) ->
                if (index > 0) HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(desc, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
