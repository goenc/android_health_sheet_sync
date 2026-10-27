package com.goenc.healthsheetsync.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.goenc.healthsheetsync.data.buildLabResultsPrompt

@Composable
internal fun LabResultsPhotoShareButton(itemNames: List<String>, enabled: Boolean) {
    PhotoShareButton(prompt = buildLabResultsPrompt(itemNames), buttonLabel = "ChatGPTを準備して写真を共有", enabled = enabled)
}

@Composable
internal fun BodyCompositionPhotoShareButton(enabled: Boolean) {
    PhotoShareButton(prompt = buildBodyCompositionPrompt(), buttonLabel = "ChatGPTを準備して写真を共有", enabled = enabled)
}

@Composable
private fun PhotoShareButton(prompt: String, buttonLabel: String, enabled: Boolean) {
    val context = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }
    var showPreparation by rememberSaveable { mutableStateOf(false) }
    var waitingForChatGpt by rememberSaveable { mutableStateOf(false) }
    var openPickerAfterChatGpt by rememberSaveable { mutableStateOf(false) }
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
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(Intent(intent).setPackage("com.openai.chatgpt"))
                status = "ChatGPTへ画像と解析指示を渡した。本文が表示されない場合はクリップボードから貼り付ける。"
            } catch (_: ActivityNotFoundException) {
                try {
                    context.startActivity(Intent.createChooser(intent, "ChatGPTで検査票を解析"))
                    status = "共有先を開いた。ChatGPTを選び、本文が表示されない場合はクリップボードから貼り付ける。"
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
    // ChatGPTを先に一度起動し、利用者が戻った後に画像選択を開く。
    // picker.launchはActivityResultのコールバック後、Compose effectから呼ぶ。
    val chatGptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        waitingForChatGpt = false
        openPickerAfterChatGpt = true
        status = "ChatGPTから戻った。写真を選ぶと、画像と解析指示を共有する。"
    }
    LaunchedEffect(openPickerAfterChatGpt) {
        if (openPickerAfterChatGpt) {
            openPickerAfterChatGpt = false
            picker.launch("image/*")
        }
    }
    Column {
        OutlinedButton(onClick = { showPreparation = true }, enabled = enabled && !waitingForChatGpt, modifier = Modifier.fillMaxWidth()) {
            Text(buttonLabel)
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (showPreparation) AlertDialog(
        onDismissRequest = { showPreparation = false },
        title = { Text("ChatGPTを準備") },
        text = { Text("先にChatGPTを開く。画面が表示されたら戻る操作でこのアプリに戻り、写真を選ぶ。") },
        confirmButton = { TextButton(onClick = {
            showPreparation = false
            waitingForChatGpt = true
            status = "ChatGPTを起動中。ChatGPTから戻ると写真選択を開く。"
            try {
                chatGptLauncher.launch(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage("com.openai.chatgpt")
                })
            } catch (_: ActivityNotFoundException) {
                waitingForChatGpt = false
                status = "ChatGPTを起動できないため、写真を選んで共有先を指定する。"
                openPickerAfterChatGpt = true
            } catch (_: SecurityException) {
                waitingForChatGpt = false
                status = "ChatGPTを起動できないため、写真を選んで共有先を指定する。"
                openPickerAfterChatGpt = true
            }
        }) { Text("ChatGPTを開く") } },
        dismissButton = { TextButton(onClick = { showPreparation = false }) { Text("キャンセル") } },
    )
}
