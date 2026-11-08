package ch.bojovic.lichtkasten.ui

import android.content.Context
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.bojovic.lichtkasten.R
import ch.bojovic.lichtkasten.core.DecodeException
import ch.bojovic.lichtkasten.core.MetaRow
import ch.bojovic.lichtkasten.core.Metadata
import ch.bojovic.lichtkasten.core.readMetadata
import ch.bojovic.lichtkasten.media.MediaItem
import java.io.FileNotFoundException
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Overview from MediaStore at once; EXIF/IPTC/XMP from Rust when they arrive. */
@Composable
fun InfoSheet(item: MediaItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    val meta by produceState<MetaLoad>(initialValue = MetaLoad.Loading, item.id) {
        value = if (item.isVideo) MetaLoad.Done(null) else MetaLoad.Done(loadMetadata(context, item))
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp)) {
            item {
                Text(stringResource(R.string.info_title), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
            }
            items(overviewRows(context, item)) { (k, v) -> InfoRow(k, v) }

            when (val m = meta) {
                MetaLoad.Loading -> item {
                    Spacer(Modifier.height(16.dp))
                    CircularProgressIndicator(Modifier.padding(8.dp))
                }
                is MetaLoad.Done -> {
                    val md = m.metadata
                    if (md == null || (md.exif.isEmpty() && md.iptc.isEmpty() && md.xmp.isEmpty())) {
                        if (!item.isVideo) item {
                            Spacer(Modifier.height(16.dp))
                            Text(stringResource(R.string.info_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        section(R.string.info_section_exif, md.exif) { exifLabel(context, it) }
                        section(R.string.info_section_iptc, md.iptc) { iptcLabel(context, it) }
                        section(R.string.info_section_xmp, md.xmp) { it }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private sealed interface MetaLoad {
    data object Loading : MetaLoad
    data class Done(val metadata: Metadata?) : MetaLoad
}

private suspend fun loadMetadata(context: Context, item: MediaItem): Metadata? = withContext(Dispatchers.IO) {
    val fd = try {
        context.contentResolver.openFileDescriptor(item.uri, "r")?.detachFd()
    } catch (e: FileNotFoundException) {
        null
    } catch (e: SecurityException) {
        null
    } ?: return@withContext null
    try {
        readMetadata(fd) // Rust closes fd.
    } catch (e: DecodeException) {
        null
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    titleRes: Int,
    rows: List<MetaRow>,
    label: (String) -> String,
) {
    if (rows.isEmpty()) return
    item {
        Spacer(Modifier.height(16.dp))
        Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
    }
    items(rows) { row -> InfoRow(label(row.key), row.value) }
}

@Composable
private fun InfoRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            key,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.58f),
        )
    }
}

private fun overviewRows(context: Context, item: MediaItem): List<Pair<String, String>> {
    val rows = ArrayList<Pair<String, String>>(8)
    rows += context.getString(R.string.info_file) to item.displayName
    if (item.relativePath.isNotEmpty()) rows += context.getString(R.string.info_folder) to item.relativePath.trimEnd('/')
    rows += context.getString(R.string.info_size) to Formatter.formatShortFileSize(context, item.sizeBytes)
    if (item.sortMillis > 0) {
        rows += context.getString(R.string.info_taken) to formatDate(context, item.sortMillis)
    }
    if (item.dateModifiedSeconds > 0) {
        rows += context.getString(R.string.info_modified) to formatDate(context, item.dateModifiedSeconds * 1000)
    }
    if (item.width > 0 && item.height > 0) {
        rows += context.getString(R.string.info_pixels) to "${item.width} × ${item.height}"
    }
    rows += context.getString(R.string.info_format) to item.mimeType
    if (item.isVideo && item.durationMillis > 0) {
        rows += context.getString(R.string.info_duration) to formatDuration(item.durationMillis)
    }
    return rows
}

private fun formatDate(context: Context, millis: Long): String {
    val date = Date(millis)
    return DateFormat.getMediumDateFormat(context).format(date) + " " + DateFormat.getTimeFormat(context).format(date)
}

private val EXIF_LABELS = mapOf(
    "DateTimeOriginal" to R.string.exif_DateTimeOriginal,
    "CreateDate" to R.string.exif_CreateDate,
    "ModifyDate" to R.string.exif_ModifyDate,
    "Make" to R.string.exif_Make,
    "Model" to R.string.exif_Model,
    "LensModel" to R.string.exif_LensModel,
    "LensMake" to R.string.exif_LensMake,
    "ExposureTime" to R.string.exif_ExposureTime,
    "FNumber" to R.string.exif_FNumber,
    "FocalLength" to R.string.exif_FocalLength,
    "FocalLengthIn35mmFormat" to R.string.exif_FocalLengthIn35mmFormat,
    "ExposureCompensation" to R.string.exif_ExposureCompensation,
    "Flash" to R.string.exif_Flash,
    "WhiteBalance" to R.string.exif_WhiteBalance,
    "ColorSpace" to R.string.exif_ColorSpace,
    "Artist" to R.string.exif_Artist,
    "ImageDescription" to R.string.exif_ImageDescription,
    "Orientation" to R.string.exif_Orientation,
    "ExifImageWidth" to R.string.exif_ExifImageWidth,
    "ExifImageHeight" to R.string.exif_ExifImageHeight,
    "UserComment" to R.string.exif_UserComment,
)

private val IPTC_LABELS = mapOf(
    "ObjectName" to R.string.iptc_ObjectName,
    "Keywords" to R.string.iptc_Keywords,
    "By-line" to R.string.iptc_By_line,
    "City" to R.string.iptc_City,
    "Country-PrimaryLocationName" to R.string.iptc_Country_PrimaryLocationName,
    "Headline" to R.string.iptc_Headline,
    "CopyrightNotice" to R.string.iptc_CopyrightNotice,
    "Caption-Abstract" to R.string.iptc_Caption_Abstract,
    "DateCreated" to R.string.iptc_DateCreated,
    "Source" to R.string.iptc_Source,
)

private fun exifLabel(context: Context, key: String): String =
    EXIF_LABELS[key]?.let(context::getString) ?: key

private fun iptcLabel(context: Context, key: String): String =
    IPTC_LABELS[key]?.let(context::getString) ?: key
