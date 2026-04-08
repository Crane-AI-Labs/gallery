package com.google.ai.edge.gallery.healthdemo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.healthdemo.data.PatientRole
import com.google.ai.edge.gallery.healthdemo.viewmodel.HealthDemoViewModel

private val NavyBlue = Color(0xFF0D1B5E)

@Composable
fun SelectRoleSheet(
    viewModel: HealthDemoViewModel,
    rememberRole: Boolean,
    onRememberRoleChange: (Boolean) -> Unit,
    onContinue: () -> Unit,
    onDismiss: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val canContinue = uiState.role != null &&
            (uiState.role != PatientRole.Other || uiState.customRole.isNotBlank())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .navigationBarsPadding()
    ) {
        // Header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Select Your Role",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1F1F1F)
                )
                Text(
                    "Required before starting a consultation",
                    fontSize = 13.sp,
                    color = Color(0xFF9E9E9E)
                )
            }
            IconButton(onClick = onDismiss) {
                Text("✕", fontSize = 18.sp, color = Color(0xFF9E9E9E))
            }
        }

        // Role list
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            PatientRole.entries.forEach { role ->
                val isSelected = uiState.role == role
                RoleRow(
                    role = role,
                    isSelected = isSelected,
                    onClick = { viewModel.setRole(role) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Custom role input for Other
            if (uiState.role == PatientRole.Other) {
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = uiState.customRole,
                    onValueChange = { viewModel.setCustomRole(it) },
                    placeholder = { Text("Enter your role", color = Color(0xFF9E9E9E)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NavyBlue,
                        unfocusedBorderColor = Color(0xFFE0E0E0),
                        focusedTextColor = Color.Black,
                        unfocusedTextColor = Color.Black
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Remember my role toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(
                checked = rememberRole,
                onCheckedChange = onRememberRoleChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = NavyBlue,
                    uncheckedTrackColor = Color(0xFFE0E0E0)
                )
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    "Remember my role",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF1F1F1F)
                )
                Text(
                    "Skip this step in future sessions",
                    fontSize = 12.sp,
                    color = Color(0xFF9E9E9E)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onContinue,
            enabled = canContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 20.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = NavyBlue,
                disabledContainerColor = Color(0xFF9E9E9E)
            )
        ) {
            Text(
                "Continue to Consultation",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun RoleRow(role: PatientRole, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) NavyBlue else Color(0xFFE0E0E0),
                shape = RoundedCornerShape(8.dp)
            )
            .background(
                color = if (isSelected) Color(0xFFF0F1FA) else Color.White,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon circle
        Row(
            modifier = Modifier
                .size(38.dp)
                .background(
                    color = if (isSelected) NavyBlue else Color(0xFFF5F5F5),
                    shape = CircleShape
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = roleIcon(role),
                contentDescription = null,
                tint = if (isSelected) Color.White else Color(0xFF666666),
                modifier = Modifier
                    .size(20.dp)
                    .align(Alignment.CenterVertically)
                    .padding(start = 9.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Text(
            text = role.label,
            fontSize = 15.sp,
            color = if (isSelected) NavyBlue else Color(0xFF1F1F1F),
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )

        if (isSelected) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = NavyBlue,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun roleIcon(role: PatientRole): ImageVector = when (role) {
    PatientRole.Doctor -> Icons.Default.MedicalServices
    PatientRole.Nurse -> Icons.Default.LocalHospital
    PatientRole.MedicalOfficer -> Icons.Default.Badge
    PatientRole.Midwife -> Icons.Default.Favorite
    PatientRole.Other -> Icons.Default.Person
}
