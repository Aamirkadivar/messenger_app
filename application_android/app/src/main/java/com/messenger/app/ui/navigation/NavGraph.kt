package com.messenger.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.messenger.app.ui.screen.ChatListScreen
import com.messenger.app.ui.screen.ChatScreen
import com.messenger.app.ui.screen.LoginScreen
import com.messenger.app.ui.screen.RegisterScreen
import com.messenger.app.ui.viewmodel.AuthViewModel
import com.messenger.app.ui.viewmodel.ChatViewModel
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Screen routes for the navigation graph.
 */
object Routes {
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val CHAT_LIST = "chat_list"
    const val CHAT = "chat/{chatId}/{chatName}"

    fun chat(chatId: String, chatName: String): String {
        val encodedName = URLEncoder.encode(chatName, "UTF-8")
        return "chat/$chatId/$encodedName"
    }
}

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
        composable(Routes.LOGIN) {
            val viewModel: AuthViewModel = hiltViewModel()
            LoginScreen(
                viewModel = viewModel,
                onLoginSuccess = {
                    navController.navigate(Routes.CHAT_LIST) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                onNavigateToRegister = {
                    navController.navigate(Routes.REGISTER)
                }
            )
        }

        composable(Routes.REGISTER) {
            val viewModel: AuthViewModel = hiltViewModel()
            RegisterScreen(
                viewModel = viewModel,
                onRegisterSuccess = {
                    navController.navigate(Routes.CHAT_LIST) {
                        popUpTo(Routes.REGISTER) { inclusive = true }
                    }
                },
                onNavigateToLogin = {
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.CHAT_LIST) {
            val authViewModel: AuthViewModel = hiltViewModel()
            val chatViewModel: ChatViewModel = hiltViewModel()
            ChatListScreen(
                chatViewModel = chatViewModel,
                onChatClick = { chatId, chatName ->
                    navController.navigate(Routes.chat(chatId, chatName))
                },
                onLogout = {
                    authViewModel.logout()
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.CHAT_LIST) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument("chatId") { type = NavType.StringType },
                navArgument("chatName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val chatId = backStackEntry.arguments?.getString("chatId") ?: return@composable
            val chatNameEncoded = backStackEntry.arguments?.getString("chatName") ?: ""
            val chatName = URLDecoder.decode(chatNameEncoded, "UTF-8")
            val chatViewModel: ChatViewModel = hiltViewModel()

            ChatScreen(
                chatId = chatId,
                chatName = chatName,
                viewModel = chatViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
