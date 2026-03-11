package com.google.ai.edge.gallery.healthdemo.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.healthdemo.data.PatientRole
import com.google.ai.edge.gallery.healthdemo.viewmodel.HealthDemoViewModel

private val NavyBlue = Color(0xFF0D1B5E)

@Composable
fun SelectRoleScreen(
    viewModel: HealthDemoViewModel,
    onContinue: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val canContinue = uiState.role != null &&
            (uiState.role != PatientRole.Other || uiState.customRole.isNotBlank())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp)
        ) {
            Text(
                text = "Select Role",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F1F1F)
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Select the role you are working in today.",
                fontSize = 15.sp,
                color = Color(0xFF444746)
            )

            Spacer(modifier = Modifier.height(24.dp))

            PatientRole.entries.forEach { role ->
                val isSelected = uiState.role == role
                RoleItem(
                    label = role.label,
                    isSelected = isSelected,
                    onClick = { viewModel.setRole(role) }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Custom role input shown only when "Other" is selected
            if (uiState.role == PatientRole.Other) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Enter your role",
                    fontSize = 14.sp,
                    color = Color(0xFF1F1F1F),
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = uiState.customRole,
                    onValueChange = { viewModel.setCustomRole(it) },
                    placeholder = { Text("Enter your role here", color = Color(0xFF9E9E9E)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NavyBlue,
                        unfocusedBorderColor = Color(0xFFE0E0E0),
                        focusedTextColor = Color.Black,
                        unfocusedTextColor = Color.Black
                    )
                )
            }
        }

        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            Button(
                onClick = onContinue,
                enabled = canContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NavyBlue,
                    disabledContainerColor = Color(0xFF9E9E9E)
                )
            ) {
                Text(
                    text = "Continue",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun RoleItem(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            width = 1.5.dp,
            color = if (isSelected) NavyBlue else Color(0xFFE0E0E0)
        ),
        color = if (isSelected) Color(0xFFF0F1FA) else Color.White,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            fontSize = 15.sp,
            color = if (isSelected) NavyBlue else Color(0xFF1F1F1F),
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
