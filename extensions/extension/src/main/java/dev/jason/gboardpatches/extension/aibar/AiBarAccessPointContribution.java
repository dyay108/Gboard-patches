package dev.jason.gboardpatches.extension.aibar;

import android.content.Context;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Adds one draggable Gboard toolbar button (Access Point) per AI button, through the same reviewed
 * 18.5.4 catalog/controller hooks the editing buttons use. Button changes apply after Gboard restarts.
 */
public final class AiBarAccessPointContribution {
    public static final AiBarAccessPointContribution INSTANCE = new AiBarAccessPointContribution();
    private static final String TAG = "GboardPatchesAiBar";
    private static volatile Handles handles;

    private AiBarAccessPointContribution() {}

    public Object extendOrderCatalog(Context context, Object original) {
        try {
            if (!(original instanceof Collection<?> stock)) return original;
            if (!AiBarRuntime.active(context)) {
                Log.i(TAG, "AI Bar is off; toolbar catalog unchanged");
                return original;
            }
            prepare(original.getClass().getClassLoader());
            List<String> result = extend(stock, AiBarStore.config(context));
            Log.i(TAG, "AI Bar toolbar catalog: " + stock.size() + " -> " + result.size());
            Class<?> catalog = Class.forName("wpa", false, original.getClass().getClassLoader());
            Method copy = catalog.getDeclaredMethod("n", Collection.class);
            copy.setAccessible(true);
            return copy.invoke(null, result);
        } catch (Throwable failure) {
            Log.w(TAG, "AI Bar toolbar catalog failed", failure);
            return original;
        }
    }

    static List<String> extend(Collection<?> stock, AiBarConfig config) {
        List<String> result = new ArrayList<>();
        if (stock != null) {
            for (Object value : stock) {
                if (value instanceof String token && !result.contains(token)) result.add(token);
            }
        }
        for (AiBarButton button : config.buttons) {
            if (!result.contains(button.toolbarToken())) result.add(button.toolbarToken());
        }
        return result;
    }

    public void register(Object controller, Context context) throws Throwable {
        if (controller == null || context == null) return;
        if (!AiBarRuntime.active(context)) {
            Log.i(TAG, "AI Bar is off; no toolbar buttons");
            return;
        }
        prepare(controller.getClass().getClassLoader());
        Handles binding = handles;
        int registered = 0;
        for (AiBarButton button : AiBarStore.config(context).buttons) {
            int icon = icon(context, button);
            if (icon == 0 || !"drawable".equals(context.getResources().getResourceTypeName(icon))) {
                Log.w(TAG, "No toolbar icon for AI button " + button.label);
                continue;
            }
            String id = button.id;
            binding.register(controller, button.toolbarToken(), icon, button.label, () -> AiBarRuntime.click(id));
            registered++;
        }
        Log.i(TAG, "AI Bar registered " + registered + " toolbar buttons");
    }

    /** The icon drawable the AI Bar patch copied into Gboard, by name. */
    static int icon(Context context, AiBarButton button) {
        String resourcePackage = resourcePackage(context);
        int id = context.getResources().getIdentifier(button.iconResourceName(), "drawable", resourcePackage);
        if (id != 0) return id;
        return context.getResources().getIdentifier("aibar_icon_sparkle", "drawable", resourcePackage);
    }

    /**
     * The package name inside Gboard's resource table. Package Rename changes the app's package but
     * not this, so resources must be looked up under the name the app icon resource carries.
     */
    static String resourcePackage(Context context) {
        try {
            int appIcon = context.getApplicationInfo().icon;
            if (appIcon != 0) return context.getResources().getResourcePackageName(appIcon);
        } catch (RuntimeException ignored) {
            // Fall back to the app's own package name.
        }
        return context.getPackageName();
    }

    static synchronized void prepare(ClassLoader loader) throws ReflectiveOperationException {
        if (handles == null || handles.loader != loader) handles = new Handles(loader);
    }

    /** Reflection handles for Gboard 18.5.4's Access Point descriptor builder and controller. */
    static final class Handles {
        final ClassLoader loader;
        final Method factory;
        final Method token;
        final Method icon;
        final Method runnable;
        final Method build;
        final Method register;
        final Field title;

        Handles(ClassLoader loader) throws ReflectiveOperationException {
            this.loader = loader;
            Class<?> descriptor = Class.forName("mtr", false, loader);
            Class<?> builder = Class.forName("mtm", false, loader);
            Class<?> controller = Class.forName("mwv", false, loader);
            factory = method(descriptor, "c", builder, true);
            token = method(builder, "l", void.class, false, String.class);
            icon = method(builder, "i", void.class, false, int.class);
            runnable = method(builder, "q", void.class, false, Runnable.class);
            build = method(builder, "a", descriptor, false);
            register = method(controller, "g", void.class, false, descriptor, boolean.class);
            title = builder.getDeclaredField("d");
            if (title.getType() != String.class || Modifier.isStatic(title.getModifiers())) {
                throw new NoSuchFieldException("mtm.d");
            }
            title.setAccessible(true);
        }

        void register(Object controller, String tokenValue, int iconId, String text, Runnable callback)
                throws ReflectiveOperationException {
            Object builder = factory.invoke(null);
            token.invoke(builder, tokenValue);
            icon.invoke(builder, iconId);
            title.set(builder, text);
            runnable.invoke(builder, callback);
            register.invoke(controller, build.invoke(builder), false);
        }

        private static Method method(Class<?> owner, String name, Class<?> returnType, boolean isStatic,
                Class<?>... parameters) throws NoSuchMethodException {
            Method method = owner.getDeclaredMethod(name, parameters);
            if (method.getReturnType() != returnType || Modifier.isStatic(method.getModifiers()) != isStatic) {
                throw new NoSuchMethodException(owner.getName() + "." + name);
            }
            method.setAccessible(true);
            return method;
        }
    }
}
