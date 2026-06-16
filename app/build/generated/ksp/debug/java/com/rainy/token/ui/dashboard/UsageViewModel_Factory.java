package com.rainy.token.ui.dashboard;

import com.rainy.token.data.local.UsageCache;
import com.rainy.token.data.repository.CredentialRepository;
import com.rainy.token.domain.usecase.SyncUsageUseCase;
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
public final class UsageViewModel_Factory implements Factory<UsageViewModel> {
  private final Provider<UsageCache> cacheProvider;

  private final Provider<SyncUsageUseCase> syncUseCaseProvider;

  private final Provider<CredentialRepository> credentialRepositoryProvider;

  private UsageViewModel_Factory(Provider<UsageCache> cacheProvider,
      Provider<SyncUsageUseCase> syncUseCaseProvider,
      Provider<CredentialRepository> credentialRepositoryProvider) {
    this.cacheProvider = cacheProvider;
    this.syncUseCaseProvider = syncUseCaseProvider;
    this.credentialRepositoryProvider = credentialRepositoryProvider;
  }

  @Override
  public UsageViewModel get() {
    return newInstance(cacheProvider, syncUseCaseProvider, credentialRepositoryProvider.get());
  }

  public static UsageViewModel_Factory create(Provider<UsageCache> cacheProvider,
      Provider<SyncUsageUseCase> syncUseCaseProvider,
      Provider<CredentialRepository> credentialRepositoryProvider) {
    return new UsageViewModel_Factory(cacheProvider, syncUseCaseProvider, credentialRepositoryProvider);
  }

  public static UsageViewModel newInstance(javax.inject.Provider<UsageCache> cacheProvider,
      javax.inject.Provider<SyncUsageUseCase> syncUseCaseProvider,
      CredentialRepository credentialRepository) {
    return new UsageViewModel(cacheProvider, syncUseCaseProvider, credentialRepository);
  }
}
