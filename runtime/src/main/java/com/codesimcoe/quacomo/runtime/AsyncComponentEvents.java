package com.codesimcoe.quacomo.runtime;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.eclipse.microprofile.context.ManagedExecutor;

/**
 * Internal scheduling support for build-time generated event dispatchers.
 */
public final class AsyncComponentEvents {
  private AsyncComponentEvents() {
  }

  public static CompletableFuture<Void> submit(ManagedExecutor executor, Supplier<CompletionStage<Void>> delivery) {
    CompletableFuture<Void> result = new CompletableFuture<>();
    try {
      executor.execute(() -> {
        try {
          Objects
            .requireNonNull(delivery.get(), "An async event subscriber returned null")
            .whenComplete((_, failure) -> {
              if (failure == null) {
                result.complete(null);
              } else {
                result.completeExceptionally(failure);
              }
            });
        } catch (Throwable failure) {
          result.completeExceptionally(failure);
        }
      });
    } catch (Throwable failure) {
      // A rejected delivery must not prevent scheduling the other subscribers.
      result.completeExceptionally(failure);
    }
    return result;
  }

  public static CompletionStage<Void> all(CompletableFuture<?>[] deliveries) {
    return CompletableFuture.allOf(deliveries);
  }
}
