package com.goenc.healthsheetsync.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.goenc.healthsheetsync.R

@Composable
internal fun BackIconButton(onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            painter = painterResource(R.drawable.ic_arrow_back_24),
            contentDescription = "戻る",
            tint = if (enabled) Color(0xFF182B3A) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.size(20.dp),
        )
    }
}
