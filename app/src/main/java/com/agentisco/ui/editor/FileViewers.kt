package com.agentisco.ui.editor

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.view.ViewGroup
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.agentisco.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Human readable byte size, e.g. 1.4 MB. */
fun formatFileSize(bytes: Long): String = when {
  bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
  bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
  bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
  else -> "$bytes B"
}

/**
 * Shared frame for every non-text viewer: centered content above a slim
 * footer with the file name and size.
 */
@Composable
private fun ViewerScaffold(
  fileName: String,
  subtitle: String,
  content: @Composable BoxScope.() -> Unit
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(DarkBackground)
  ) {
    Box(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth(),
      contentAlignment = Alignment.Center,
      content = content
    )
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(DarkSurface)
        .padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Text(fileName, color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
      Text(subtitle, color = TextMuted, fontSize = 11.sp)
    }
  }
}

@Composable
private fun ViewerMessage(icon: ImageVector, title: String, subtitle: String) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier.padding(24.dp)
  ) {
    Icon(icon, null, tint = TextMuted, modifier = Modifier.size(40.dp))
    Text(title, color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    Text(subtitle, color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
  }
}

// -------------------------------------------------------------------- image

/**
 * Image viewer backed by Coil: decoding is downsampled to the viewport so
 * large photos stay light on memory; pinch/drag zoom, double-tap toggles.
 */
@Composable
fun ImagePreviewPane(
  file: File?,
  fileName: String,
  fileSize: Long,
  modifier: Modifier = Modifier
) {
  var scale by remember(fileName) { mutableFloatStateOf(1f) }
  var offsetX by remember(fileName) { mutableFloatStateOf(0f) }
  var offsetY by remember(fileName) { mutableFloatStateOf(0f) }

  ViewerScaffold(fileName = fileName, subtitle = formatFileSize(fileSize)) {
    if (file == null || !file.isFile) {
      ViewerMessage(Icons.Default.BrokenImage, "File not found on disk", fileName)
    } else {
      SubcomposeAsyncImage(
        model = file,
        contentDescription = fileName,
        contentScale = ContentScale.Fit,
        modifier = modifier
          .fillMaxSize()
          .graphicsLayer(
            scaleX = scale,
            scaleY = scale,
            translationX = offsetX,
            translationY = offsetY
          )
          .pointerInput(fileName) {
            detectTransformGestures { _, pan, zoom, _ ->
              scale = (scale * zoom).coerceIn(1f, 6f)
              if (scale > 1f) {
                offsetX += pan.x
                offsetY += pan.y
              } else {
                offsetX = 0f
                offsetY = 0f
              }
            }
          }
          .pointerInput(fileName) {
            detectTapGestures(onDoubleTap = {
              if (scale > 1f) {
                scale = 1f; offsetX = 0f; offsetY = 0f
              } else {
                scale = 2.5f
              }
            })
          },
        loading = {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            CircularProgressIndicator(color = CyanAccent, strokeWidth = 2.dp)
            Text("Loading ${formatFileSize(fileSize)}…", color = TextMuted, fontSize = 11.sp)
          }
        },
        error = {
          ViewerMessage(Icons.Default.BrokenImage, "Cannot render this image", "$fileName  •  ${formatFileSize(fileSize)}")
        }
      )
    }
  }
}

// ---------------------------------------------------------------------- pdf

/**
 * PDF viewer on Android's built-in PdfRenderer — no extra dependency.
 * One page bitmap is alive at a time, decoded near viewport width.
 */
@Composable
fun PdfPreviewPane(
  file: File?,
  fileName: String,
  fileSize: Long,
  modifier: Modifier = Modifier
) {
  var pageCount by remember(fileName) { mutableIntStateOf(0) }
  var pageIndex by remember(fileName) { mutableIntStateOf(0) }
  var bitmap by remember(fileName) { mutableStateOf<ImageBitmap?>(null) }
  var error by remember(fileName) { mutableStateOf<String?>(null) }

  val viewportPx = with(LocalDensity.current) {
    (LocalConfiguration.current.screenWidthDp.dp.toPx() * 0.94f).toInt()
  }

  LaunchedEffect(file?.absolutePath, pageIndex, viewportPx) {
    if (file == null || !file.isFile) {
      pageCount = 0; bitmap = null
      error = "File not found on disk"
      return@LaunchedEffect
    }
    error = null
    withContext(Dispatchers.IO) {
      runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
          PdfRenderer(pfd).use { renderer ->
            val total = renderer.pageCount
            if (total == 0) return@runCatching
            val safeIndex = pageIndex.coerceIn(0, total - 1)
            renderer.openPage(safeIndex).use { page ->
              // A full-size page would decode several MB for no visible
              // benefit — render near the width it is shown at.
              val w = viewportPx.coerceAtMost(page.width * 4)
              val h = (page.height.toFloat() * w / page.width).toInt().coerceAtLeast(1)
              val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
              bmp.eraseColor(AndroidColor.WHITE)
              page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
              withContext(Dispatchers.Main) {
                pageCount = total
                bitmap = bmp.asImageBitmap()
              }
            }
          }
        }
      }.onFailure { e ->
        withContext(Dispatchers.Main) {
          error = "Cannot open PDF: ${e.message ?: "unsupported file"}"
          bitmap = null
        }
      }
    }
  }

  ViewerScaffold(fileName = fileName, subtitle = formatFileSize(fileSize)) {
    Column(
      modifier = Modifier.fillMaxSize(),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
      ) {
        val shown = bitmap
        when {
          error != null -> ViewerMessage(Icons.Default.PictureAsPdf, error!!, fileName)
          shown != null -> Image(
            bitmap = shown,
            contentDescription = fileName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
          )
          else -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            CircularProgressIndicator(color = CyanAccent, strokeWidth = 2.dp)
            Text("Rendering page ${pageIndex + 1}…", color = TextMuted, fontSize = 12.sp)
          }
        }
      }

      if (pageCount > 0 && error == null) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurface)
            .padding(horizontal = 12.dp, vertical = 4.dp),
          horizontalArrangement = Arrangement.Center,
          verticalAlignment = Alignment.CenterVertically
        ) {
          IconButton(
            onClick = { pageIndex = (pageIndex - 1).coerceAtLeast(0) },
            enabled = pageIndex > 0,
            modifier = Modifier.size(32.dp)
          ) {
            Icon(Icons.Default.ChevronLeft, "Previous page", tint = TextPrimary, modifier = Modifier.size(18.dp))
          }
          Text(
            "Page ${pageIndex + 1} / $pageCount",
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 8.dp)
          )
          IconButton(
            onClick = { pageIndex = (pageIndex + 1).coerceAtMost(pageCount - 1) },
            enabled = pageIndex < pageCount - 1,
            modifier = Modifier.size(32.dp)
          ) {
            Icon(Icons.Default.ChevronRight, "Next page", tint = TextPrimary, modifier = Modifier.size(18.dp))
          }
        }
      }
    }
  }
}

// -------------------------------------------------------------------- media

/**
 * Inline audio/video playback on the platform MediaPlayer: video renders to
 * a VideoView, audio gets compact transport controls.
 */
@Composable
fun MediaPreviewPane(
  file: File?,
  fileName: String,
  fileSize: Long,
  modifier: Modifier = Modifier
) {
  val isAudio = fileName.substringAfterLast('.', "").lowercase() in
    setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "flac", "opus", "amr", "mid", "midi")

  ViewerScaffold(fileName = fileName, subtitle = formatFileSize(fileSize)) {
    if (file == null || !file.isFile) {
      ViewerMessage(Icons.Default.PlayCircle, "File not found on disk", fileName)
    } else if (isAudio) {
      AudioPlayerCard(file = file, fileName = fileName)
    } else {
      key(file.absolutePath) {
        AndroidView(
          factory = { ctx ->
            VideoView(ctx).apply {
              layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
              )
              setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
              setVideoPath(file.absolutePath)
              setOnPreparedListener { start() }
              setOnErrorListener { _, _, _ -> true }
            }
          },
          modifier = modifier.fillMaxSize()
        )
      }
    }
  }
}

@Composable
private fun AudioPlayerCard(file: File, fileName: String) {
  var player by remember(file.absolutePath) { mutableStateOf<MediaPlayer?>(null) }
  var prepared by remember(file.absolutePath) { mutableStateOf(false) }
  var playing by remember(file.absolutePath) { mutableStateOf(false) }
  var positionMs by remember(file.absolutePath) { mutableIntStateOf(0) }
  var durationMs by remember(file.absolutePath) { mutableIntStateOf(0) }

  DisposableEffect(file.absolutePath) {
    val mp = runCatching {
      MediaPlayer().apply {
        setDataSource(file.absolutePath)
        setOnPreparedListener {
          prepared = true
          durationMs = it.duration
        }
        setOnCompletionListener { playing = false; positionMs = 0 }
        prepareAsync()
      }
    }.getOrNull()
    player = mp
    onDispose { runCatching { mp?.release() } }
  }

  LaunchedEffect(playing, prepared) {
    while (playing && prepared) {
      player?.let { positionMs = it.currentPosition }
      kotlinx.coroutines.delay(500)
    }
  }

  Surface(
    color = DarkSurface,
    shape = RoundedCornerShape(12.dp),
    border = BorderStroke(1.dp, DarkBorder),
    modifier = Modifier.padding(24.dp)
  ) {
    Column(
      modifier = Modifier.padding(18.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Icon(Icons.Default.MusicNote, null, tint = CyanAccent, modifier = Modifier.size(40.dp))
      Text(fileName, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
      Slider(
        value = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
        onValueChange = { frac ->
          val mp = player ?: return@Slider
          if (prepared) {
            val target = (frac * durationMs).toInt()
            @Suppress("DEPRECATION") mp.seekTo(target)
            positionMs = target
          }
        },
        colors = SliderDefaults.colors(thumbColor = CyanAccent, activeTrackColor = CyanAccent)
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Text(formatTime(positionMs), color = TextMuted, fontSize = 11.sp)
        Text(formatTime(durationMs), color = TextMuted, fontSize = 11.sp)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
          onClick = {
            val mp = player ?: return@IconButton
            if (!prepared) return@IconButton
            if (mp.isPlaying) {
              mp.pause(); playing = false
            } else {
              mp.start(); playing = true
            }
          },
          modifier = Modifier
            .size(48.dp)
            .background(DarkSurfaceElevated, RoundedCornerShape(24.dp))
        ) {
          Icon(
            imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = if (playing) "Pause" else "Play",
            tint = CyanAccent,
            modifier = Modifier.size(26.dp)
          )
        }
        IconButton(
          onClick = {
            val mp = player ?: return@IconButton
            if (prepared) {
              @Suppress("DEPRECATION") mp.seekTo(0)
              positionMs = 0
              if (playing) mp.start()
            }
          },
          modifier = Modifier.size(36.dp)
        ) {
          Icon(Icons.Default.Replay, "Restart", tint = TextSecondary, modifier = Modifier.size(20.dp))
        }
      }
    }
  }
}

private fun formatTime(ms: Int): String {
  val total = ms / 1000
  return "%d:%02d".format(total / 60, total % 60)
}

// -------------------------------------------------------------- other files

/**
 * Fallback card for files we cannot render (archives, executables, unknown
 * binaries): metadata plus an "Open Externally" handoff.
 */
@Composable
fun OtherFilePreviewPane(
  file: File?,
  fileName: String,
  fileSize: Long,
  title: String = "No in-app preview for this file type",
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  ViewerScaffold(fileName = fileName, subtitle = formatFileSize(fileSize)) {
    Surface(
      color = DarkSurface,
      shape = RoundedCornerShape(12.dp),
      border = BorderStroke(1.dp, DarkBorder),
      modifier = modifier.padding(16.dp)
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Icon(Icons.Default.InsertDriveFile, null, tint = CyanAccent, modifier = Modifier.size(48.dp))
        Text(fileName, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
        Text(title, color = TextSecondary, fontSize = 12.sp)
        if (file != null) {
          Text(
            text = "Size: ${formatFileSize(fileSize)}  •  Modified: " +
              SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified())),
            color = TextMuted,
            fontSize = 11.sp
          )
          Spacer(modifier = Modifier.height(4.dp))
          Button(
            onClick = {
              val opened = runCatching {
                val uri = FileProvider.getUriForFile(
                  context,
                  "${context.packageName}.fileprovider",
                  file
                )
                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                  fileName.substringAfterLast('.', "").lowercase()
                ) ?: "*/*"
                context.startActivity(
                  Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                  }
                )
              }.isSuccess
              if (!opened) {
                android.widget.Toast.makeText(
                  context, "No app can open this file", android.widget.Toast.LENGTH_SHORT
                ).show()
              }
            },
            colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated),
            modifier = Modifier.height(32.dp)
          ) {
            Icon(Icons.Default.OpenInNew, null, tint = CyanAccent, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Open Externally", color = TextPrimary, fontSize = 12.sp)
          }
        }
      }
    }
  }
}
