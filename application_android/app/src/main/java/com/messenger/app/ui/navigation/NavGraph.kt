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
import com.messenger.app.ui.screen.CreateGroupScreen
import com.messenger.app.ui.screen.GroupInfoScreen
import com.messenger.app.ui.screen.LoginScreen
import com.messenger.app.ui.screen.RegisterScreen
import com.messenger.app.ui.screen.SettingsScreen
import com.messenger.app.ui.viewmodel.AuthViewModel
import com.messenger.app.ui.viewmodel.CallViewModel
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
    const val CHAT = "chat/{chatId}/{chatName}?isGroup={isGroup}"
    const val SETTINGS = "settings"
    const val CREATE_GROUP = "create_group"
    const val GROUP_INFO = "group_info/{chatId}"

    fun groupInfo(chatId: String) = "group_info/$chatId"

    fun chat(chatId: String, chatName: String, isGroup: Boolean = false): String {
        val encodedName = URLEncoder.encode(chatName, "UTF-8")
        return "chat/$chatId/$encodedName?isGroup=$isGroup"
    }
}

@Composable
fun MainNavGraph(
    navController: NavHostController,
    callViewModel: CallViewModel,
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
                onChatClick = { chatId, chatName, isGroup ->
                    navController.navigate(Routes.chat(chatId, chatName, isGroup))
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onCreateGroup = { navController.navigate(Routes.CREATE_GROUP) },
                onSessionExpired = {
                    // Same teardown as an explicit logout: the stored token is
                    // dead, so clear it rather than leaving it to fail again.
                    authViewModel.logout()
                    navController.navigate(Routes.LOGIN) {
                        // popUpTo(0) clears the whole back stack - pressing back
                        // from login must not return to a signed-out chat list.
                        popUpTo(0) { inclusive = true }
                    }
                },
                onLogout = {
                    authViewModel.logout()
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.CHAT_LIST) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.CREATE_GROUP) {
            CreateGroupScreen(
                onNavigateBack = { navController.popBackStack() },
                onGroupCreated = { chatId, chatName ->
                    // Replace this screen in the stack: backing out of the new
                    // group should land on the chat list, not the create form.
                    navController.navigate(Routes.chat(chatId, chatName, isGroup = true)) {
                        popUpTo(Routes.CREATE_GROUP) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Routes.GROUP_INFO,
            arguments = listOf(navArgument("chatId") { type = NavType.StringType })
        ) { backStackEntry ->
            val chatId = backStackEntry.arguments?.getString("chatId") ?: return@composable
            GroupInfoScreen(
                chatId = chatId,
                onNavigateBack = { navController.popBackStack() },
                onGroupExited = {
                    // Left or deleted - the chat behind this screen is gone too,
                    // so drop both and return to the list.
                    navController.navigate(Routes.CHAT_LIST) {
                        popUpTo(Routes.CHAT_LIST) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument("chatId") { type = NavType.StringType },
                navArgument("chatName") { type = NavType.StringType },
                navArgument("isGroup") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            val chatId = backStackEntry.arguments?.getString("chatId") ?: return@composable
            val chatNameEncoded = backStackEntry.arguments?.getString("chatName") ?: ""
            val chatName = URLDecoder.decode(chatNameEncoded, "UTF-8")
            val isGroup = backStackEntry.arguments?.getBoolean("isGroup") ?: false
            val chatViewModel: ChatViewModel = hiltViewModel()

            ChatScreen(
                chatId = chatId,
                chatName = chatName,
                isGroup = isGroup,
                viewModel = chatViewModel,
                onOpenGroupInfo = if (isGroup) {
                    { navController.navigate(Routes.groupInfo(chatId)) }
                } else {
                    null
                },
                onNavigateBack = { navController.popBackStack() },
                onStartCall = if (isGroup) null else { calleeId, calleeName ->
                    callViewModel.startCall(chatId, calleeId, calleeName)
                }
            )
        }
    }
}
