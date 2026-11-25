package dev.jason.gboardpatches.extension.aibar;

/** A failure whose message is short enough for a toast. */
public final class AiBarException extends Exception {
    public AiBarException(String message) {
        super(message);
    }
}
