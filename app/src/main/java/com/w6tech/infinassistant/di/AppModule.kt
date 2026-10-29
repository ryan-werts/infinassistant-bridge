package com.w6tech.infinassistant.di

import android.content.Context
import android.media.AudioManager
import com.w6tech.infinassistant.BuildConfig
import com.w6tech.infinassistant.core.common.AndroidLogger
import com.w6tech.infinassistant.core.common.AndroidNetworkDetector
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.common.NetworkDetector
import com.w6tech.infinassistant.core.data.AudioRecorder
import com.w6tech.infinassistant.core.data.BridgeEngine
import com.w6tech.infinassistant.core.data.DataStoreSettingsRepository
import com.w6tech.infinassistant.core.data.DiagnosticLogger
import com.w6tech.infinassistant.core.data.DiagnosticTeeLogger
import com.w6tech.infinassistant.core.data.HaApi
import com.w6tech.infinassistant.core.data.HaTokenStore
import com.w6tech.infinassistant.core.data.InfinassistantWebhookClient
import com.w6tech.infinassistant.core.data.MdnsManager
import com.w6tech.infinassistant.core.data.MediaSpeechPlayer
import com.w6tech.infinassistant.core.data.HomeSubnets
import com.w6tech.infinassistant.core.data.SettingsDefaults
import com.w6tech.infinassistant.core.data.SettingsRepository
import com.w6tech.infinassistant.core.data.UrlSelector
import com.w6tech.infinassistant.core.data.VoiceActivityDetector
import com.w6tech.infinassistant.core.data.VoiceAuthGuard
import com.w6tech.infinassistant.core.data.VoiceAssistantManager
import com.w6tech.infinassistant.core.data.appearance.ThemeModeStore
import com.w6tech.infinassistant.core.data.appearance.createThemeModeStore
import com.w6tech.infinassistant.core.data.bridge.PhoneNotificationRegistry
import com.w6tech.infinassistant.core.ha.AssistPipelineClient
import com.w6tech.infinassistant.core.ha.lanOnlyCleartext
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Logcat as before, and the core modules' warnings and errors (with a few events) in the
     * diagnostic log too, so they reach a debug bundle. Through the one DiagnosticLogger, which
     * BridgeEngine also writes to.
     */
    @Provides
    @Singleton
    fun provideLogger(diagnosticLogger: DiagnosticLogger): Logger {
        val tee = DiagnosticTeeLogger(
            delegate = AndroidLogger(),
            sink = DiagnosticTeeLogger.Sink { level, tag, msg, extra ->
                diagnosticLogger.log(level = level, tag = tag, msg = msg, extra = extra)
            },
        )
        diagnosticLogger.beforeExport = tee::flush
        return tee
    }

    @Provides
    @Singleton
    fun provideTokenStore(@ApplicationContext context: Context): HaTokenStore = HaTokenStore(context)

    /**
     * The settings' starting values, from the build: the home subnets and the debug SMB share of
     * the owner's local file when the build had one, else empty. The core modules never see the file.
     */
    @Provides
    @Singleton
    fun provideSettingsDefaults(): SettingsDefaults = SettingsDefaults(
        homeSubnets = HomeSubnets.parseInput(BuildConfig.HOME_SUBNETS).first,
        debugSharePath = BuildConfig.DEBUG_SMB_SHARE,
    )

    @Provides
    @Singleton
    fun provideSettingsRepository(
        @ApplicationContext context: Context,
        tokenStore: HaTokenStore,
        defaults: SettingsDefaults,
    ): SettingsRepository = DataStoreSettingsRepository(context, tokenStore, defaults)

    @Provides
    @Singleton
    fun provideNetworkDetector(@ApplicationContext context: Context): NetworkDetector =
        AndroidNetworkDetector(context)

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .lanOnlyCleartext()
            .build()

    @Provides
    @Singleton
    fun provideUrlSelector(
        networkDetector: NetworkDetector,
        httpClient: OkHttpClient,
    ): UrlSelector = UrlSelector(networkDetector, httpClient)

    @Provides
    @Singleton
    fun provideInfinassistantWebhookClient(
        logger: Logger,
        settingsRepo: SettingsRepository,
        urlSelector: UrlSelector,
        httpClient: OkHttpClient,
    ): InfinassistantWebhookClient = InfinassistantWebhookClient(logger, settingsRepo, urlSelector, httpClient)

    @Provides
    @Singleton
    fun provideMdnsManager(
        @ApplicationContext context: Context,
        logger: Logger,
    ): MdnsManager = MdnsManager(context, logger)

    @Provides
    @Singleton
    fun provideAudioManager(@ApplicationContext context: Context): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Provides
    @Singleton
    fun provideAudioRecorder(audioManager: AudioManager): AudioRecorder =
        AudioRecorder(audioManager)

    @Provides
    @Singleton
    fun provideVoiceActivityDetector(): VoiceActivityDetector = VoiceActivityDetector()

    @Provides
    @Singleton
    fun provideAssistPipelineClient(okHttpClient: OkHttpClient): AssistPipelineClient =
        // The app-wide client times a read out after 5 s, which would drop a conversation's socket
        // while HA thinks or the reply plays. No read timeout; a ping every 15 s finds a dead peer.
        // newBuilder keeps the LAN-only cleartext guard.
        AssistPipelineClient(
            okHttpClient.newBuilder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                // The token must not follow a redirect to another address.
                .followRedirects(false)
                .followSslRedirects(false)
                .build(),
        )

    @Provides
    @Singleton
    fun provideSpeechPlayer(
        @ApplicationContext context: Context,
        audioManager: AudioManager,
        logger: Logger,
    ): MediaSpeechPlayer = MediaSpeechPlayer(context, audioManager, logger)

    @Provides
    @Singleton
    fun provideVoiceAuthGuard(): VoiceAuthGuard = VoiceAuthGuard()

    @Provides
    @Singleton
    fun provideVoiceAssistantManager(
        logger: Logger,
        audioRecorder: AudioRecorder,
        voiceActivityDetector: VoiceActivityDetector,
        speechPlayer: MediaSpeechPlayer,
        assistPipelineClient: AssistPipelineClient,
        voiceAuthGuard: VoiceAuthGuard,
    ): VoiceAssistantManager = VoiceAssistantManager(
        logger,
        audioRecorder,
        voiceActivityDetector,
        speechPlayer,
        assistPipelineClient,
        voiceAuthGuard,
    )

    @Provides
    @Singleton
    fun provideDiagnosticLogger(@ApplicationContext context: Context): DiagnosticLogger =
        DiagnosticLogger(context)

    @Provides
    @Singleton
    fun provideThemeModeStore(
        @ApplicationContext context: Context,
        diagnosticLogger: DiagnosticLogger,
    ): ThemeModeStore = createThemeModeStore(context) { message ->
        diagnosticLogger.log(
            level = "WARN",
            tag = "Appearance",
            msg = message,
        )
    }

    @Provides
    @Singleton
    fun provideBridgeEngine(
        @ApplicationContext context: Context,
        logger: Logger,
        settingsRepo: SettingsRepository,
        tokenStore: HaTokenStore,
        urlSelector: UrlSelector,
        webhookClient: InfinassistantWebhookClient,
        voiceAssistantManager: VoiceAssistantManager,
        diagnosticLogger: DiagnosticLogger,
        phoneNotificationRegistry: PhoneNotificationRegistry,
    ): BridgeEngine = BridgeEngine(
        context, logger, settingsRepo, tokenStore, urlSelector, webhookClient, voiceAssistantManager, diagnosticLogger,
        phoneNotificationRegistry,
    )

    @Provides
    @Singleton
    fun providePhoneNotificationRegistry(): PhoneNotificationRegistry = PhoneNotificationRegistry()

    @Provides
    @Singleton
    fun provideHaApi(
        logger: Logger,
        settingsRepo: SettingsRepository,
        tokenStore: HaTokenStore,
        urlSelector: UrlSelector,
    ): HaApi = HaApi(logger, settingsRepo, tokenStore, urlSelector)
}
