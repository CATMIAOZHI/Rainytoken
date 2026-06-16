package com.rainy.token.domain.usecase;

import com.rainy.token.data.repository.DeepSeekRepository;
import com.rainy.token.data.repository.OpenCodeGoRepository;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata
@QualifierMetadata
@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava",
    "cast",
    "deprecation",
    "nullness:initialization.field.uninitialized"
})
public final class RefreshBalanceUseCase_Factory implements Factory<RefreshBalanceUseCase> {
  private final Provider<DeepSeekRepository> deepSeekRepositoryProvider;

  private final Provider<OpenCodeGoRepository> openCodeGoRepositoryProvider;

  private RefreshBalanceUseCase_Factory(Provider<DeepSeekRepository> deepSeekRepositoryProvider,
      Provider<OpenCodeGoRepository> openCodeGoRepositoryProvider) {
    this.deepSeekRepositoryProvider = deepSeekRepositoryProvider;
    this.openCodeGoRepositoryProvider = openCodeGoRepositoryProvider;
  }

  @Override
  public RefreshBalanceUseCase get() {
    return newInstance(deepSeekRepositoryProvider, openCodeGoRepositoryProvider);
  }

  public static RefreshBalanceUseCase_Factory create(
      Provider<DeepSeekRepository> deepSeekRepositoryProvider,
      Provider<OpenCodeGoRepository> openCodeGoRepositoryProvider) {
    return new RefreshBalanceUseCase_Factory(deepSeekRepositoryProvider, openCodeGoRepositoryProvider);
  }

  public static RefreshBalanceUseCase newInstance(
      javax.inject.Provider<DeepSeekRepository> deepSeekRepositoryProvider,
      javax.inject.Provider<OpenCodeGoRepository> openCodeGoRepositoryProvider) {
    return new RefreshBalanceUseCase(deepSeekRepositoryProvider, openCodeGoRepositoryProvider);
  }
}
