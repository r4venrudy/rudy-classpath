package dev.rudy.classpath;

import java.io.IOException;

/** Safe, fixed diagnostics for rejected input; arbitrary parser messages stay private. */
public final class InputError extends IOException {
  private static final long serialVersionUID = 1L;

  public InputError(String message) {
    super(message);
  }
}
