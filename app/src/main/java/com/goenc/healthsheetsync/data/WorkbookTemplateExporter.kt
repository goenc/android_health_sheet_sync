package com.goenc.healthsheetsync.data

import android.content.Context
import android.net.Uri
import android.util.Log

class WorkbookTemplateExporter(
    private val context: Context,
) {
    fun saveTemplate(uri: Uri): String {
        return runCatching {
            context.assets.open(WORKBOOK_ASSET_NAME).use { input ->
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    input.copyTo(output)
                } ?: error("保存先を開けませんでした")
            }
            "外部保存が完了しました"
        }.getOrElse { error ->
            Log.e(TAG, "Failed to save workbook template externally.", error)
            "外部保存に失敗しました: ${error.message ?: "原因不明"}"
        }
    }
}

private const val TAG = "HealthSheetSync"
private const val WORKBOOK_ASSET_NAME = "health_sheet_sync_work_branch_template.xlsx"
