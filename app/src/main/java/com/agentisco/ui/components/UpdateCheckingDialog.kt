package com.agentisco.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.agentisco.ui.theme.*

/**
 * Indeterminate dialog shown while checking GitHub for a new release
 * (triggered manually from Settings).
 */
@Composable
fun UpdateCheckingDialog() {
    Dialog(onDismissRequest = {}) {
        Row(
            modifier = Modifier
                .background(DarkSurface, RoundedCornerShape(12.dp))
                .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = ElectricBlue,
                trackColor = DarkSurfaceHighlight
            )
            Spacer(Modifier.width(14.dp))
            Text("Checking for updates…", color = TextSecondary, fontSize = 13.sp)
        }
    }
}
