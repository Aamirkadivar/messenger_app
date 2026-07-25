package com.messenger.app.di

import android.content.Context
import com.messenger.app.BuildConfig
import com.messenger.app.data.encryption.MessageEncryption
import com.messenger.app.data.local.AuthDatabase
import com.messenger.app.data.local.dao.ConversationDao
import com.messenger.app.data.local.dao.MessageDao
import com.messenger.app.data.local.dao.UserDao
import com.messenger.app.data.remote.api.AuthApiService
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.data.remote.websocket.WebSocketManager
import com.messenger.app.data.repository.AuthRepository
import com.messenger.app.data.repository.ChatRepository
import com.messenger.app.security.KeyStoreManager
import com.messenger.app.security.KeyStoreManagerImpl
import com.messenger.app.security.TokenManager
import com.messenger.app.security.TokenManagerImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, json: Json): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun provideAuthApiService(retrofit: Retrofit): AuthApiService =
        retrofit.create(AuthApiService::class.java)

    @Provides
    @Singleton
    fun provideChatApiService(retrofit: Retrofit): ChatApiService =
        retrofit.create(ChatApiService::class.java)

    @Provides
    @Singleton
    fun provideKeyStoreManager(@ApplicationContext context: Context): KeyStoreManager =
        KeyStoreManagerImpl(context)

    @Provides
    @Singleton
    fun provideTokenManager(
        @ApplicationContext context: Context,
        keyStoreManager: KeyStoreManager
    ): TokenManager = TokenManagerImpl(context, keyStoreManager)

    @Provides
    @Singleton
    fun provideMessageEncryption(): MessageEncryption = MessageEncryption()

    @Provides
    @Singleton
    fun provideAuthDatabase(@ApplicationContext context: Context): AuthDatabase =
        AuthDatabase.getDatabase(context)

    @Provides
    fun provideMessageDao(db: AuthDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideConversationDao(db: AuthDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideUserDao(db: AuthDatabase): UserDao = db.userDao()

    @Provides
    @Singleton
    fun provideWebSocketManager(tokenManager: TokenManager): WebSocketManager =
        WebSocketManager.getInstance(
            serverUrl = BuildConfig.API_BASE_URL,
            tokenProvider = {
                kotlinx.coroutines.runBlocking { tokenManager.getAccessToken().getOrNull() }
            }
        )

    @Provides
    @Singleton
    fun provideAuthRepository(
        authApiService: AuthApiService,
        tokenManager: TokenManager,
        keyStoreManager: KeyStoreManager,
        userDao: UserDao
    ): AuthRepository = AuthRepository(authApiService, tokenManager, keyStoreManager, userDao)

    @Provides
    @Singleton
    fun provideChatRepository(
        chatApiService: ChatApiService,
        messageDao: MessageDao,
        conversationDao: ConversationDao,
        webSocketManager: WebSocketManager,
        tokenManager: TokenManager
    ): ChatRepository = ChatRepository(chatApiService, messageDao, conversationDao, webSocketManager, tokenManager)
}
