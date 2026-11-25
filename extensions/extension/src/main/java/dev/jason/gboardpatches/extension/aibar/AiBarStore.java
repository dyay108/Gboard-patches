package dev.jason.gboardpatches.extension.aibar;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import dev.jason.gboardpatches.extension.settings.GboardPatchesSettings;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.function.Function;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AI Bar settings in the Gboard Patches preferences: the {@link AiBarConfig} as JSON, the on/off
 * switch, and API keys encrypted with an AES-GCM key that never leaves the Android Keystore.
 */
public final class AiBarStore {
    static final String KEY_ENABLED = "ai_bar_enabled";
    static final String KEY_CONFIG = "ai_bar_config_json";
    private static final String KEY_SECRET_PREFIX = "ai_bar_api_key_";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "gboard-patches-ai-bar";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;

    private static String cachedJson;
    private static AiBarConfig cachedConfig;

    private AiBarStore() {}

    /** Before the first unlock the settings can't be read, so AI Bar stays off (stock behavior). */
    private static <T> T read(Context context, Function<SharedPreferences, T> reader, T fallback) {
        return GboardPatchesSettings.readRuntimeSetting(context, reader, fallback);
    }

    private static SharedPreferences.Editor editor(Context context) {
        SharedPreferences preferences = GboardPatchesSettings.preferencesOrNull(context);
        if (preferences == null) {
            throw new IllegalStateException("Gboard Patches settings are unavailable until the phone is unlocked");
        }
        return preferences.edit();
    }

    public static boolean enabled(Context context) {
        return read(context, preferences -> preferences.getBoolean(KEY_ENABLED, true), false);
    }

    @SuppressLint("ApplySharedPref")
    public static void setEnabled(Context context, boolean enabled) {
        if (!editor(context).putBoolean(KEY_ENABLED, enabled).commit()) {
            throw new IllegalStateException("Unable to save AI Bar");
        }
    }

    /** The saved settings, or the defaults when nothing is saved or the data is unreadable. */
    public static synchronized AiBarConfig config(Context context) {
        String json = read(context, preferences -> preferences.getString(KEY_CONFIG, null), null);
        if (json == null) return AiBarConfig.defaults();
        if (json.equals(cachedJson) && cachedConfig != null) return cachedConfig.copy();
        try {
            cachedConfig = AiBarConfig.fromJson(json);
            cachedJson = json;
            return cachedConfig.copy();
        } catch (AiBarException e) {
            return AiBarConfig.defaults();
        }
    }

    @SuppressLint("ApplySharedPref")
    public static synchronized void save(Context context, AiBarConfig config) {
        if (!editor(context).putString(KEY_CONFIG, config.toJson()).commit()) {
            throw new IllegalStateException("Unable to save AI Bar settings");
        }
    }

    public static boolean hasApiKey(Context context, AiBarConfig.Provider provider) {
        return read(context, preferences -> preferences.contains(secretKey(provider)), false);
    }

    /** The decrypted key, or "" when none is saved or the Keystore key is gone. */
    public static String apiKey(Context context, AiBarConfig.Provider provider) {
        try {
            String sealed = read(context, preferences -> preferences.getString(secretKey(provider), null), null);
            return sealed == null ? "" : decrypt(sealed);
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Saves {@code key} encrypted, or removes it when blank. */
    @SuppressLint("ApplySharedPref")
    public static void setApiKey(Context context, AiBarConfig.Provider provider, String key) throws Exception {
        SharedPreferences.Editor editor = editor(context);
        if (key == null || key.isBlank()) editor.remove(secretKey(provider));
        else editor.putString(secretKey(provider), encrypt(key.trim()));
        if (!editor.commit()) throw new IllegalStateException("Unable to save the API key");
    }

    private static String secretKey(AiBarConfig.Provider provider) {
        return KEY_SECRET_PREFIX + provider.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static SecretKey keystoreKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (keyStore.getKey(KEY_ALIAS, null) instanceof SecretKey existing) return existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static String encrypt(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey());
        byte[] iv = cipher.getIV();
        byte[] body = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] sealed = new byte[iv.length + body.length];
        System.arraycopy(iv, 0, sealed, 0, iv.length);
        System.arraycopy(body, 0, sealed, iv.length, body.length);
        return Base64.encodeToString(sealed, Base64.NO_WRAP);
    }

    private static String decrypt(String encoded) throws Exception {
        byte[] sealed = Base64.decode(encoded, Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), new GCMParameterSpec(128, sealed, 0, IV_BYTES));
        return new String(cipher.doFinal(sealed, IV_BYTES, sealed.length - IV_BYTES), StandardCharsets.UTF_8);
    }

    /** Builds the client for the chosen provider; {@code modelOverride} replaces its model when set. */
    public static AiBarLlm.Client client(Context context, AiBarConfig config, String modelOverride) {
        int timeout = 30_000;
        if (config.provider == AiBarConfig.Provider.ANTHROPIC) {
            return new AiBarLlm.Anthropic("https://api.anthropic.com", apiKey(context, config.provider),
                    modelOverride != null ? modelOverride : config.anthropicModel, timeout);
        }
        return new AiBarLlm.OpenAiCompatible(config.openAiBaseUrl, apiKey(context, config.provider),
                modelOverride != null ? modelOverride : config.openAiModel, timeout);
    }
}
