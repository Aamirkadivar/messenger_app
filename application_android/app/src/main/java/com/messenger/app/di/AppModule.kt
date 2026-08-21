package com.messenger.app.di

import android.content.Context
import com.messenger.app.BuildConfig
import com.messenger.app.data.local.AuthDatabase
import com.messenger.app.data.local.dao.CachedChatDao
import com.messenger.app.data.local.dao.ConversationDao
import com.messenger.app.data.local.dao.MessageDao
import com.messenger.app.data.local.dao.UserDao
import com.messenger.app.data.remote.TokenRefreshAuthenticator
import com.messenger.app.data.remote.api.AuthApiService
import com.messenger.app.data.remote.api.ChatApiService
import com.messenger.app.data.remote.websocket.WebSocketManager
import com.messenger.app.data.repository.AttachmentRepository
import com.messenger.app.data.repository.AuthRepository
import com.messenger.app.data.repository.ChatRepository
import com.messenger.app.data.repository.MlsRepository
import com.messenger.app.data.repository.E2EEVaultRepository
import com.messenger.app.data.repository.AvatarRepository
import com.messenger.app.data.repository.GroupRepository
import com.messenger.app.data.repository.VoiceRepository
import com.messenger.app.data.voice.VoicePlayer
import com.messenger.app.data.voice.VoiceRecorder
import com.messenger.app.data.settings.SettingsRepository
import com.messenger.app.data.storage.StorageAnalyzer
import com.messenger.app.security.KeyStoreManager
import com.messenger.app.security.KeyStoreManagerImpl
import com.messenger.app.security.TokenManager
import com.messenger.app.security.TokenManagerImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    fun provideOkHttpClient(
        authenticator: TokenRefreshAuthenticator,
        tokenManager: TokenManager
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        val deviceIdInterceptor = okhttp3.Interceptor { chain ->
            val deviceId = kotlinx.coroutines.runBlocking {
                tokenManager.getOrCreateDeviceId().getOrNull().orEmpty()
            }
            val req = if (deviceId.isNotBlank()) {
                chain.request().newBuilder().header("X-Device-Id", deviceId).build()
            } else chain.request()
            chain.proceed(req)
        }
        return OkHttpClient.Builder()
            .addInterceptor(deviceIdInterceptor)
            .addInterceptor(logging)
            // Renews an expired access token and replays the request, instead
            // of letting every call fail until the user signs in again.
            .authenticator(authenticator)
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
    fun provideAuthDatabase(@ApplicationContext context: Context): AuthDatabase =
        AuthDatabase.getDatabase(context)

    @Provides
    fun provideMessageDao(db: AuthDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideConversationDao(db: AuthDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideUserDao(db: AuthDatabase): UserDao = db.userDao()

    @Provides
    fun provideCachedChatDao(db: AuthDatabase): CachedChatDao = db.cachedChatDao()

    @Provides
    @Singleton
    fun provideWebSocketManager(tokenManager: TokenManager): WebSocketManager =
        WebSocketManager.getInstance(
            serverUrl = BuildConfig.API_BASE_URL,
            tokenProvider = {
                kotlinx.coroutines.runBlocking { tokenManager.getAccessToken().getOrNull() }
            },
            deviceIdProvider = {
                kotlinx.coroutines.runBlocking { tokenManager.getOrCreateDeviceId().getOrNull() }
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

    /**
     * Scope for work that must outlive any one screen - notably the storage
     * scan, which the user is allowed to walk away from mid-flight.
     * SupervisorJob so one failed task doesn't cancel the others.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepository =
        SettingsRepository(context)

    @Provides
    @Singleton
    fun provideGroupRepository(chatApiService: ChatApiService): GroupRepository =
        GroupRepository(chatApiService)

    @Provides
    @Singleton
    fun provideAvatarRepository(
        @ApplicationContext context: Context,
        chatApiService: ChatApiService
    ): AvatarRepository = AvatarRepository(context, chatApiService)

    @Provides
    @Singleton
    fun provideVoiceRepository(
        @ApplicationContext context: Context,
        chatApiService: ChatApiService,
        chatRepository: ChatRepository,
        okHttpClient: OkHttpClient
    ): VoiceRepository = VoiceRepository(context, chatApiService, chatRepository, okHttpClient)

    @Provides
    fun provideVoiceRecorder(@ApplicationContext context: Context): VoiceRecorder =
        VoiceRecorder(context)

    @Provides
    @Singleton
    fun provideVoicePlayer(@ApplicationContext context: Context): VoicePlayer =
        VoicePlayer(context)

    @Provides
    @Singleton
    fun provideStorageAnalyzer(
        @ApplicationContext context: Context,
        chatRepository: ChatRepository,
        @ApplicationScope appScope: CoroutineScope
    ): StorageAnalyzer = StorageAnalyzer(context, chatRepository, appScope)

    @Provides
    @Singleton
    fun provideMlsRepository(
        chatApiService: ChatApiService,
        tokenManager: TokenManager
    ): MlsRepository = MlsRepository(chatApiService, tokenManager)

    @Provides
    @Singleton
    fun provideChatRepository(
        chatApiService: ChatApiService,
        messageDao: MessageDao,
        conversationDao: ConversationDao,
        cachedChatDao: CachedChatDao,
        webSocketManager: WebSocketManager,
        tokenManager: TokenManager,
        groupRepository: GroupRepository,
        json: Json,
        vaultRepository: dagger.Lazy<E2EEVaultRepository>
    ): ChatRepository = ChatRepository(
        chatApiService, messageDao, conversationDao, cachedChatDao, webSocketManager, tokenManager,
        groupRepository, json,
        onVaultMaterialChanged = { token -> vaultRepository.get().scheduleRefreshVaultContents(token) },
        onVaultPullNeeded = { token, force -> vaultRepository.get().pullAndMergeVault(token, force) }
    )

    @Provides
    @Singleton
    fun provideAttachmentRepository(
        @ApplicationContext context: Context,
        chatApiService: ChatApiService,
        chatRepository: ChatRepository,
        okHttpClient: OkHttpClient
    ): AttachmentRepository = AttachmentRepository(context, chatApiService, chatRepository, okHttpClient)
}
