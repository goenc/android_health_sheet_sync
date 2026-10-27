package com.goenc.healthsheetsync.ui

import android.content.ActivityNotFoundException
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.goenc.healthsheetsync.data.buildLabResultsPrompt

@Composable
internal fun LabResultsPhotoShareButton(itemNames: List<String>, enabled: Boolean) {
    PhotoShareButton(prompt = buildLabResultsPrompt(itemNames), buttonLabel = "写真をChatGPTで解析", enabled = enabled)
}

@Composable
internal fun BodyCompositionPhotoShareButton(enabled: Boolean) {
    PhotoShareButton(prompt = buildBodyCompositionPrompt(), buttonLabel = "体組成の写真をChatGPTで解析", enabled = enabled)
}

@Composable
private fun PhotoShareButton(prompt: String, buttonLabel: String, enabled: Boolean) {
    val context = LocalContext.current
    val shareContext = context.findActivity() ?: context
    var status by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { images ->
        if (images.isNotEmpty()) {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("検査票の解析指示", prompt))
            val intent = Intent(if (images.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                type = images.map { context.contentResolver.getType(it) ?: "image/*" }.distinct()
                    .singleOrNull() ?: "image/*"
                putExtra(Intent.EXTRA_TEXT, prompt)
                if (images.size == 1) putExtra(Intent.EXTRA_STREAM, images.single())
                else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(images))
                clipData = ClipData.newUri(context.contentResolver, "検査票", images.first()).apply {
                    images.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (shareContext !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                shareContext.startActivity(Intent(intent).setPackage("com.openai.chatgpt"))
                status = "解析指示をコピー済み。ChatGPTに本文が入らない場合は貼り付けて送信する。"
            } catch (_: ActivityNotFoundException) {
                try {
                    shareContext.startActivity(Intent.createChooser(intent, "ChatGPTで検査票を解析"))
                    status = "解析指示をコピー済み。共有先でChatGPTを選び、必要なら指示を貼り付ける。"
                } catch (_: ActivityNotFoundException) {
                    status = "共有先がありません。ChatGPTを開き、コピー済みの指示と写真を添付してください。"
                } catch (_: SecurityException) {
                    status = "写真を共有できません。ChatGPTで写真を添付し、コピー済みの指示を貼り付けてください。"
                }
            } catch (_: SecurityException) {
                status = "写真を共有できません。ChatGPTで写真を添付し、コピー済みの指示を貼り付けてください。"
            }
        }
    }
    Column {
        OutlinedButton(onClick = { picker.launch("image/*") }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(buttonLabel)
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val base = current.baseContext
        if (base === current) return null
        current = base
    }
    return current as? Activity
}
