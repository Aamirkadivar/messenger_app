package com.messenger.app.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.messenger.app.ui.components.AmbientGlow
import com.messenger.app.ui.components.GlassSurface
import com.messenger.app.ui.pairing.PairingQr
import com.messenger.app.ui.theme.AccentGreen
import com.messenger.app.ui.theme.CardShape
import com.messenger.app.ui.theme.MessengerExtendedColors
import com.messenger.app.ui.viewmodel.AuthViewModel

@Composable
private fun AuthBackground(content: @Composable BoxScope.() -> Unit) {
    val dark = MessengerExtendedColors.isDark
    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        AmbientGlow(
            modifier = Modifier.matchParentSize(),
            baseColor = MaterialTheme.colorScheme.background,
            primaryGlow = MaterialTheme.colorScheme.primary,
            secondaryGlow = if (dark) Color(0xFFA6863F) else Color(0xFF8A6A2E),
            intensity = if (dark) 1f else 0.6f
        )
        content()
    }
}

@Composable
private fun AuthIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .background(tint, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun AuthPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    accent: Color,
    imeAction: ImeAction = ImeAction.Done,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = accent) },
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (visible) "Hide password" else "Show password",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = keyboardActions,
        shape = CardShape,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, focusedLabelColor = accent),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun LoginScreen(
    viewModel: AuthViewModel,
    onLoginSuccess: () -> Unit,
    onNavigateToRegister: () -> Unit
) {
    val state by viewModel.loginState.collectAsStateWithLifecycle()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    LaunchedEffect(state.isLoggedIn, state.recoveryKeyToShow) {
        if (state.isLoggedIn && state.recoveryKeyToShow == null) onLoginSuccess()
    }

    state.recoveryKeyToShow?.let { recoveryKey ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRecoveryKey,
            title = { Text("Save your recovery key") },
            text = {
                Column {
                    Text(
                        "Store this key somewhere safe. It unlocks your encrypted messages if you forget your password.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        recoveryKey,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissRecoveryKey) {
                    Text("I've saved it")
                }
            }
        )
    }

    AuthBackground {
        GlassSurface(
            shape = CardShape,
            modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp)
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AuthIcon(Icons.AutoMirrored.Filled.Chat, MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text(
                    when {
                        state.awaitingDevicePairing -> "Link this device"
                        state.awaitingRecoveryKey -> "Recovery key"
                        state.awaiting2FA -> "Two-factor code"
                        state.awaitingPasswordReset && state.resetCodeSent -> "Reset password"
                        state.awaitingPasswordReset -> "Forgot password"
                        else -> "Welcome Back"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        state.awaitingDevicePairing ->
                            "On your already-unlocked device open Settings → Link a device and paste this code."
                        state.awaitingRecoveryKey ->
                            "Your vault couldn't be unlocked with this password. Enter the recovery key you saved when encryption was set up."
                        state.awaiting2FA ->
                            state.twoFactorHint ?: "Enter the 6-digit code from the DEV 2FA bot"
                        state.awaitingPasswordReset && state.resetCodeSent ->
                            state.twoFactorHint
                                ?: "Enter the 6-digit code, then a new login password. History unlocks with your recovery key after sign-in."
                        state.awaitingPasswordReset ->
                            state.resetInfo
                                ?: "Enter your account email. Resetting login does not decrypt old messages without the recovery key."
                        else -> "Sign in to continue to Messenger"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))

                when {
                    state.awaitingDevicePairing -> {
                        val qr = remember(state.pairingCode) {
                            PairingQr.toImageBitmap(state.pairingCode)
                        }
                        if (qr != null) {
                            Image(
                                bitmap = qr,
                                contentDescription = "Pairing QR code",
                                modifier = Modifier
                                    .size(220.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                        Text(
                            text = state.pairingCode,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Waiting for approval…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    state.awaitingRecoveryKey -> {
                        OutlinedTextField(
                            value = state.recoveryKeyInput,
                            onValueChange = viewModel::setRecoveryKeyInput,
                            label = { Text("Recovery key") },
                            singleLine = false,
                            minLines = 2,
                            leadingIcon = {
                                Icon(Icons.Default.VpnKey, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                keyboard?.hide()
                                viewModel.submitRecoveryKey()
                            }),
                            shape = CardShape,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                focusedLabelColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = viewModel::startLinkFromOtherDevice) {
                            Text("Link from another device instead")
                        }
                    }
                    !state.awaiting2FA && !state.awaitingPasswordReset -> {
                    OutlinedTextField(
                        value = state.email,
                        onValueChange = viewModel::setLoginEmail,
                        label = { Text("Email") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        shape = CardShape,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, focusedLabelColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    AuthPasswordField(
                        value = state.password,
                        onValueChange = viewModel::setLoginPassword,
                        label = "Password",
                        accent = MaterialTheme.colorScheme.primary,
                        keyboardActions = KeyboardActions(onDone = {
                            keyboard?.hide()
                            viewModel.login()
                        })
                    )
                    }
                    state.awaitingPasswordReset && !state.resetCodeSent -> {
                    OutlinedTextField(
                        value = state.email,
                        onValueChange = viewModel::setLoginEmail,
                        label = { Text("Email") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            keyboard?.hide()
                            viewModel.startPasswordReset()
                        }),
                        shape = CardShape,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, focusedLabelColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                    }
                    state.awaitingPasswordReset -> {
                    OutlinedTextField(
                        value = state.otpCode,
                        onValueChange = viewModel::setOtpCode,
                        label = { Text("6-digit reset code") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Next
                        ),
                        shape = CardShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            focusedLabelColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (state.resetTotpRequired) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = state.resetTotpCode,
                            onValueChange = viewModel::setResetTotpCode,
                            label = { Text("Authenticator or backup code") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            shape = CardShape,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                focusedLabelColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    AuthPasswordField(
                        value = state.resetNewPassword,
                        onValueChange = viewModel::setResetNewPassword,
                        label = "New password",
                        accent = MaterialTheme.colorScheme.primary,
                        keyboardActions = KeyboardActions(onDone = {
                            keyboard?.hide()
                            viewModel.completePasswordReset()
                        })
                    )
                    }
                    else -> {
                    OutlinedTextField(
                        value = state.otpCode,
                        onValueChange = viewModel::setOtpCode,
                        label = { Text("Authenticator or backup code") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            keyboard?.hide()
                            viewModel.verify2FA()
                        }),
                        shape = CardShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            focusedLabelColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    }
                }

                AnimatedVisibility(visible = state.error != null) {
                    Text(
                        state.error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 10.dp).fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(20.dp))
                if (!state.awaitingDevicePairing) {
                Button(
                    onClick = {
                        keyboard?.hide()
                        when {
                            state.awaitingRecoveryKey -> viewModel.submitRecoveryKey()
                            state.awaiting2FA -> viewModel.verify2FA()
                            state.awaitingPasswordReset && state.resetCodeSent -> viewModel.completePasswordReset()
                            state.awaitingPasswordReset -> viewModel.startPasswordReset()
                            else -> viewModel.login()
                        }
                    },
                    enabled = !state.isLoading && when {
                        state.awaitingRecoveryKey -> state.recoveryKeyInput.isNotBlank()
                        state.awaiting2FA -> state.otpCode.length >= 6
                        state.awaitingPasswordReset && state.resetCodeSent ->
                            state.otpCode.length == 6 && state.resetNewPassword.length >= 8 &&
                                (!state.resetTotpRequired || state.resetTotpCode.length >= 6)
                        state.awaitingPasswordReset -> state.email.isNotBlank()
                        else -> state.email.isNotBlank() && state.password.isNotBlank()
                    },
                    shape = CardShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text(
                            when {
                                state.awaitingRecoveryKey -> "Unlock vault"
                                state.awaiting2FA -> "Verify"
                                state.awaitingPasswordReset && state.resetCodeSent -> "Set new password"
                                state.awaitingPasswordReset -> "Send reset code"
                                else -> "Sign In"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                } else if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp
                    )
                }

                if (state.awaiting2FA) {
                    TextButton(onClick = viewModel::cancelTwoFactor) {
                        Text("Back", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (state.awaitingPasswordReset) {
                    TextButton(onClick = viewModel::cancelPasswordReset) {
                        Text("Back", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!state.awaiting2FA && !state.awaitingRecoveryKey && !state.awaitingDevicePairing && !state.awaitingPasswordReset) {
                    TextButton(onClick = viewModel::beginPasswordReset) {
                        Text("Forgot password?", color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (state.awaitingRecoveryKey || state.awaitingDevicePairing) {
                    TextButton(onClick = viewModel::cancelRecoveryUnlock) {
                        Text("Back", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (!state.awaiting2FA && !state.awaitingRecoveryKey && !state.awaitingDevicePairing && !state.awaitingPasswordReset) {
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(modifier = Modifier.weight(1f))
                    Text("  or  ", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    HorizontalDivider(modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text("Don't have an account? ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onNavigateToRegister) {
                        Text("Sign Up", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
                }
            }
        }
    }
}

@Composable
fun RegisterScreen(
    viewModel: AuthViewModel,
    onRegisterSuccess: () -> Unit,
    onNavigateToLogin: () -> Unit
) {
    val state by viewModel.registerState.collectAsStateWithLifecycle()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    LaunchedEffect(state.isRegistered) {
        if (state.isRegistered) onRegisterSuccess()
    }

    AuthBackground {
        GlassSurface(
            shape = CardShape,
            modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp)
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AuthIcon(Icons.Default.PersonAdd, AccentGreen)
                Spacer(Modifier.height(16.dp))
                Text("Create Account", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Join Messenger today",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))

                OutlinedTextField(
                    value = state.username,
                    onValueChange = viewModel::setRegisterUsername,
                    label = { Text("Username") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = AccentGreen) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    shape = CardShape,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentGreen, focusedLabelColor = AccentGreen),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.email,
                    onValueChange = viewModel::setRegisterEmail,
                    label = { Text("Email") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = AccentGreen) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    shape = CardShape,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentGreen, focusedLabelColor = AccentGreen),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                AuthPasswordField(
                    value = state.password,
                    onValueChange = viewModel::setRegisterPassword,
                    label = "Password (min. 8 characters)",
                    accent = AccentGreen,
                    imeAction = ImeAction.Next
                )
                Spacer(Modifier.height(12.dp))
                AuthPasswordField(
                    value = state.confirmPassword,
                    onValueChange = viewModel::setRegisterConfirmPassword,
                    label = "Confirm Password",
                    accent = AccentGreen,
                    keyboardActions = KeyboardActions(onDone = {
                        keyboard?.hide()
                        viewModel.register()
                    })
                )

                AnimatedVisibility(visible = state.error != null) {
                    Text(
                        state.error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp).fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { keyboard?.hide(); viewModel.register() },
                    enabled = !state.isLoading,
                    shape = CardShape,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("Create Account", fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text("Already have an account? ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onNavigateToLogin) {
                        Text("Sign In", fontWeight = FontWeight.Bold, color = AccentGreen)
                    }
                }
            }
        }
    }
}
