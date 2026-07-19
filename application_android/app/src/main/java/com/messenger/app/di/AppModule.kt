package com.messenger.app.di

import com.messenger.app.BuildConfig
import com.messenger.app.data.local.AuthDatabase
import com.messenger.app.data.local.dao.ConversationDao
import com.messenger.app.data.local.dao.MessageDao
import com.messenger.app.data.local.dao.UserDao
import com.messenger.app.data.preference.AppPreferences
import com.messenger.app.data.preference.AppPreferencesImpl
import com.messenger.app.data.preference.EncryptedStorage
import com.messenger.app.data.preference.EncryptedStorageImpl
import com.messenger.app.data.remote.AuthApiService
import com.messenger.app.data.remote.ChatApiService
import com.messenger.app.data.repository.AuthRepository
import com.messenger.app.data.repository.AuthRepositoryImpl
import com.messenger.app.data.repository.ChatRepository
import com.messenger.app.data.repository.ChatRepositoryImpl
import com.messenger.app.encryption.MessageEncryptionManager
import com.messenger.app.encryption.MessageEncryptionManagerImpl
import com.messenger.app.security.KeyStoreManager
import com.messenger.app.security.KeyStoreManagerImpl
import com.messenger.app.security.TokenManager
import com.messenger.app.security.TokenManagerImpl
import com.messenger.app.service.WebSocketForegroundService
import com.messenger.app.service.WebSocketService
import com.messenger.app.ui.auth.viewmodel.AuthViewModel
import com.messenger.app.ui.chat.viewmodel.ChatViewModel
import com.messenger.app.websocket.WebSocketManager
import com.messenger.app.websocket.WebSocketManagerImpl
import kotlinx.coroutines.Dispatchers
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.component.KoinComponent
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Koin dependency injection module for the application.
 * Provides all dependencies using Koin DI framework.
 */

val appModule = module {
    // ====================
    // Preferences & Storage
    // ====================
    single<AppPreferences> { AppPreferencesImpl(androidContext()) }
    single<EncryptedStorage> { EncryptedStorageImpl(androidContext()) }

    // ====================
    // Security
    // ====================
    single<KeyStoreManager> { KeyStoreManagerImpl(androidContext()) }
    single<TokenManager> { TokenManagerImpl(get(), get()) }

    // ====================
    // Retrofit - Base API Client
    // ====================
    single {
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    single<AuthApiService> {
        val retrofit = get<Retrofit>()
        retrofit.create(AuthApiService::class.java)
    }

    single<ChatApiService> {
        val retrofit = get<Retrofit>()
        retrofit.create(ChatApiService::class.java)
    }

    // ====================
    // WebSocket
    // ====================
    single<WebSocketService> { WebSocketForegroundService() }
    single<WebSocketManager> { WebSocketManagerImpl(androidContext(), get()) }

    // ====================
    // Encryption
    // ====================
    single<MessageEncryptionManager> { MessageEncryptionManagerImpl(get()) }

    // ====================
    // Room Database
    // ====================
    single<AuthDatabase> {
        AuthDatabase.getDatabase(androidContext(), get())
    }
    single<MessageDao> { get<AuthDatabase>().messageDao() }
    single<ConversationDao> { get<AuthDatabase>().conversationDao() }
    single<UserDao> { get<AuthDatabase>().userDao() }

    // ====================
    // Repository Layer
    // ====================
    single<AuthRepository> { AuthRepositoryImpl(get(), get(), get()) }
    single<ChatRepository> { ChatRepositoryImpl(get(), get(), get(), get(), get()) }

    // ====================
    // ViewModels
    // ====================
    viewModelOf(::AuthViewModel)
    viewModelOf(::ChatViewModel)
}

/**
 * Initialize all Koin modules
 */
fun initKoin() {
    // Koin is initialized via the KoinApplication in the Application class
    // This function is called from MessengerApplication.onCreate()
}