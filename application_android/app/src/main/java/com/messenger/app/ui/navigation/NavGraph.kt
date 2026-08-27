package com.messenger.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.messenger.app.ui.adaptive.ConversationEmptyPane
import com.messenger.app.ui.adaptive.LocalTwoPane
import com.messenger.app.ui.adaptive.MessengerTwoPane
import com.messenger.app.ui.adaptive.SelectedConversation
import com.messenger.app.ui.adaptive.SyncListDetailNavigation
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
    val twoPane = LocalTwoPane.current
    var selectedConversation by rememberSaveable(stateSaver = SelectedConversation.Saver) {
        mutableStateOf<SelectedConversation?>(null)
    }
    val conversationStateHolder = rememberSaveableStateHolder()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    SyncListDetailNavigation(
        twoPane = twoPane,
        selected = selectedConversation,
        currentRoute = currentRoute,
        onAbsorbChatRoute = { selectedConversation = it },
        navController = navController
    )

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
            ChatListDestination(
                twoPane = twoPane,
                selectedConversation = selectedConversation,
                onSelectConversation = { selectedConversation = it },
                conversationStateHolder = conversationStateHolder,
                chatViewModel = chatViewModel,
                navController = navController,
                callViewModel = callViewModel,
                onSessionExpired = {
                    // Same teardown as an explicit logout: the stored token is
                    // dead, so clear it rather than leaving it to fail again.
                    selectedConversation = null
                    authViewModel.logout()
                    navController.navigate(Routes.LOGIN) {
                        // popUpTo(0) clears the whole back stack - pressing back
                        // from login must not return to a signed-out chat list.
                        popUpTo(0) { inclusive = true }
                    }
                },
                onLogout = {
                    selectedConversation = null
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
                    if (twoPane) {
                        selectedConversation = SelectedConversation(chatId, chatName, isGroup = true)
                        navController.popBackStack()
                    } else {
                        // Replace this screen in the stack: backing out of the new
                        // group should land on the chat list, not the create form.
                        navController.navigate(Routes.chat(chatId, chatName, isGroup = true)) {
                            popUpTo(Routes.CREATE_GROUP) { inclusive = true }
                        }
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
                    selectedConversation = null
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
                onStartCall = if (isGroup) null else { calleeId, calleeName, video ->
                    callViewModel.startCall(chatId, calleeId, calleeName, video)
                },
                onStartGroupCall = if (isGroup) {
                    { video -> callViewModel.startGroupCall(chatId, chatName, video) }
                } else {
                    null
                }
            )
        }
    }
}

@Composable
private fun ChatListDestination(
    twoPane: Boolean,
    selectedConversation: SelectedConversation?,
    onSelectConversation: (SelectedConversation?) -> Unit,
    conversationStateHolder: SaveableStateHolder,
    chatViewModel: ChatViewModel,
    navController: NavHostController,
    callViewModel: CallViewModel,
    onSessionExpired: () -> Unit,
    onLogout: () -> Unit
) {
    val listState by chatViewModel.chatListState.collectAsStateWithLifecycle()
    LaunchedEffect(listState.chats, selectedConversation?.chatId) {
        val id = selectedConversation?.chatId ?: return@LaunchedEffect
        if (listState.chats.isNotEmpty() && listState.chats.none { it.id == id }) {
            onSelectConversation(null)
        }
    }

    val openSettings = { navController.navigate(Routes.SETTINGS) }
    val createGroup = { navController.navigate(Routes.CREATE_GROUP) }

    if (!twoPane) {
        ChatListScreen(
            chatViewModel = chatViewModel,
            onChatClick = { chatId, chatName, isGroup ->
                navController.navigate(Routes.chat(chatId, chatName, isGroup))
            },
            onOpenSettings = openSettings,
            onCreateGroup = createGroup,
            onSessionExpired = onSessionExpired,
            onLogout = onLogout
        )
        return
    }

    BackHandler(enabled = selectedConversation != null) {
        chatViewModel.abandonInProgressCapture()
        onSelectConversation(null)
    }

    MessengerTwoPane(
        list = {
            ChatListScreen(
                chatViewModel = chatViewModel,
                selectedChatId = selectedConversation?.chatId,
                paneMode = true,
                onChatClick = { chatId, chatName, isGroup ->
                    onSelectConversation(SelectedConversation(chatId, chatName, isGroup))
                },
                onOpenSettings = openSettings,
                onCreateGroup = createGroup,
                onSessionExpired = onSessionExpired,
                onLogout = onLogout
            )
        },
        detail = {
            val current = selectedConversation
            if (current == null) {
                ConversationEmptyPane()
            } else {
                conversationStateHolder.SaveableStateProvider(current.chatId) {
                    ChatScreen(
                        chatId = current.chatId,
                        chatName = current.chatName,
                        isGroup = current.isGroup,
                        viewModel = chatViewModel,
                        showBackButton = false,
                        onOpenGroupInfo = if (current.isGroup) {
                            { navController.navigate(Routes.groupInfo(current.chatId)) }
                        } else {
                            null
                        },
                        onNavigateBack = {
                            chatViewModel.abandonInProgressCapture()
                            onSelectConversation(null)
                        },
                        onStartCall = if (current.isGroup) {
                            null
                        } else { calleeId, calleeName, video ->
                            callViewModel.startCall(current.chatId, calleeId, calleeName, video)
                        },
                        onStartGroupCall = if (current.isGroup) {
                            { video ->
                                callViewModel.startGroupCall(current.chatId, current.chatName, video)
                            }
                        } else {
                            null
                        }
                    )
                }
            }
        }
    )
}
