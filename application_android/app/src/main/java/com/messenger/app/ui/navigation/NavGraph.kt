package com.messenger.app.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.messenger.app.ui.screen.*
import com.messenger.app.ui.theme.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Screen routes for the navigation graph
 */
object Routes {
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val CHAT_LIST = "chat_list"
    const val CHAT = "chat/{conversationId}"
    const val GROUP_CHAT = "group_chat/{groupId}"
    const val PROFILE = "profile/{userId}"
    
    // Arguments
    const val CONVERSATION_ID_ARG = "conversationId"
    const val GROUP_ID_ARG = "groupId"
    const val USER_ID_ARG = "userId"
}

/**
 * Main navigation graph
 */
@Composable
fun MainNavGraph(
    navController: NavHostController,
    startDestination: String = Routes.LOGIN,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        // Login screen
        composable(Routes.LOGIN) {
            val viewModel: AuthViewModel = viewModel()
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Routes.CHAT_LIST) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                onNavigateToRegister = {
                    navController.navigate(Routes.REGISTER)
                },
                viewModel = viewModel
            )
        }

        // Register screen
        composable(Routes.REGISTER) {
            val viewModel: AuthViewModel = viewModel()
            RegisterScreen(
                onRegisterSuccess = {
                    navController.navigate(Routes.CHAT_LIST) {
                        popUpTo(Routes.REGISTER) { inclusive = true }
                    }
                },
                onNavigateToLogin = {
                    navController.popBackStack()
                },
                viewModel = viewModel
            )
        }

        // Chat list screen
        composable(Routes.CHAT_LIST) {
            val viewModel: AuthViewModel = viewModel()
            ChatListScreen(
                onChatClick = { conversationId ->
                    navController.navigate("${Routes.CHAT}/$conversationId")
                },
                onNewChatClick = { /* Navigate to new chat screen */ },
                onLogout = {
                    viewModel.logout()
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.CHAT_LIST) { inclusive = true }
                    }
                },
                viewModel = viewModel
            )
        }

        // Chat screen
        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument(Routes.CONVERSATION_ID_ARG) {
                    type = NavType.StringType
                }
            )
        ) { backStackEntry ->
            val conversationId = backStackEntry.arguments?.getString(Routes.CONVERSATION_ID_ARG) ?: return@composable
            val chatViewModel: ChatViewModel = viewModel()
            
            ChatScreen(
                conversationId = conversationId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Group chat screen
        composable(
            route = Routes.GROUP_CHAT,
            arguments = listOf(
                navArgument(Routes.GROUP_ID_ARG) {
                    type = NavType.StringType
                }
            )
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getString(Routes.GROUP_ID_ARG) ?: return@composable
            
            GroupChatScreen(
                groupId = groupId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Profile screen (placeholder)
        composable(
            route = Routes.PROFILE,
            arguments = listOf(
                navArgument(Routes.USER_ID_ARG) {
                    type = NavType.StringType
                }
            )
        ) { backStackEntry ->
            val userId = backStackEntry.arguments?.getString(Routes.USER_ID_ARG) ?: return@composable
            
            com.m.messenger.app.ui.navigation.ProfileScreen(
                userId = userId,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

/**
 * Profile screen (placeholder)
 */
@Composable
private fun ProfileScreen(
    userId: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = androidx.compose.ui.graphics.Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    text = "User Profile",
                    fontSize = 20.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                IconButton(
                    onClick = onNavigateBack
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Close,
                        contentDescription = "Close"
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(text = "User ID: $userId")
            Text(text = "Profile details coming soon...")
        }
    }
}