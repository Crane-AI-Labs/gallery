package com.google.ai.edge.gallery.healthdemo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.ui.theme.healthBlue

@Composable
fun HelpCard(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(healthBlue)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = Color(0xFF1976D2),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "How to describe symptoms",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1976D2)
            )
        }
        Spacer(modifier = Modifier.padding(vertical = 4.dp))
        Text(
            text = "Use simple words. Examples:",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF444746)
        )
        Spacer(modifier = Modifier.padding(vertical = 4.dp))
        val examples = listOf(
            "Fever, cough, breathing difficulty",
            "Stomach pain, vomiting, diarrhea",
            "Rash, itching, swelling"
        )
        examples.forEach { example ->
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                Text(text = "• ", color = Color(0xFF1976D2))
                Text(
                    text = example,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFF444746)
                )
            }
        }
    }
}
