package com.messenger.app.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.messenger.app.ui.theme.AccentGreen
import com.messenger.app.ui.theme.AccentPurple
import com.messenger.app.ui.theme.CardShape
import com.messenger.app.ui.viewmodel.AuthViewModel

@Composable
private fun AuthBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surface)
                )
            )
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        contentAlignment = Alignment.Center,
        content = content
    )
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

    LaunchedEffect(state.isLoggedIn) {
        if (state.isLoggedIn) onLoginSuccess()
    }

    AuthBackground {
        Card(
            shape = CardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AuthIcon(Icons.AutoMirrored.Filled.Chat, AccentPurple)
                Spacer(Modifier.height(16.dp))
                Text("Welcome Back", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Sign in to continue to Messenger",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))

                OutlinedTextField(
                    value = state.email,
                    onValueChange = viewModel::setLoginEmail,
                    label = { Text("Email") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = AccentPurple) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    shape = CardShape,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentPurple, focusedLabelColor = AccentPurple),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                AuthPasswordField(
                    value = state.password,
                    onValueChange = viewModel::setLoginPassword,
                    label = "Password",
                    accent = AccentPurple,
                    keyboardActions = KeyboardActions(onDone = {
                        keyboard?.hide()
                        viewModel.login()
                    })
                )

                AnimatedVisibility(visible = state.error != null) {
                    Text(
                        state.error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 10.dp).fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { keyboard?.hide(); viewModel.login() },
                    enabled = !state.isLoading && state.email.isNotBlank() && state.password.isNotBlank(),
                    shape = CardShape,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("Sign In", fontWeight = FontWeight.Bold)
                    }
                }

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
                        Text("Sign Up", fontWeight = FontWeight.Bold, color = AccentPurple)
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
        Card(
            shape = CardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            modifier = Modifier.fillMaxWidth()
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
