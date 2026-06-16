package com.rainy.token.ui.dashboard;

import com.rainy.token.data.local.UsageCache;
import com.rainy.token.data.repository.CredentialRepository;
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
public final class UsageDataViewModel_Factory implements Factory<UsageDataViewModel> {
  private final Provider<UsageCache> cacheProvider;

  private final Provider<CredentialRepository> credentialRepositoryProvider;

  private UsageDataViewModel_Factory(Provider<UsageCache> cacheProvider,
      Provider<CredentialRepository> credentialRepositoryProvider) {
    this.cacheProvider = cacheProvider;
    this.credentialRepositoryProvider = credentialRepositoryProvider;
  }

  @Override
  public UsageDataViewModel get() {
    return newInstance(cacheProvider, credentialRepositoryProvider.get());
  }

  public static UsageDataViewModel_Factory create(Provider<UsageCache> cacheProvider,
      Provider<CredentialRepository> credentialRepositoryProvider) {
    return new UsageDataViewModel_Factory(cacheProvider, credentialRepositoryProvider);
  }

  public static UsageDataViewModel newInstance(javax.inject.Provider<UsageCache> cacheProvider,
      CredentialRepository credentialRepository) {
    return new UsageDataViewModel(cacheProvider, credentialRepository);
  }
}
