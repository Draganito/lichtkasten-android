package ch.bojovic.lichtkasten.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ch.bojovic.lichtkasten.BuildConfig
import ch.bojovic.lichtkasten.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ASSET = "licenses.txt"

/**
 * The third-party notices shipped in the APK (assets/licenses.txt, generated
 * by tools/third_party.py). Read off the main thread, shown paragraph by
 * paragraph so a 40 KB text does not become one layout pass.
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val paragraphs by produceState(initialValue = emptyList<String>()) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(ASSET).bufferedReader().use { it.readText() }
            }.getOrDefault("").split("\n\n").map { it.trimEnd() }.filter { it.isNotEmpty() }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                title = { Text(stringResource(R.string.licenses)) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.licenses_about, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
            items(paragraphs) { p ->
                val heading = p.startsWith("#")
                Text(
                    text = if (heading) p.trimStart('#', ' ') else p,
                    style = if (heading) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodySmall,
                    fontFamily = if (heading) null else FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = if (heading) 8.dp else 12.dp),
                )
            }
        }
    }
}
