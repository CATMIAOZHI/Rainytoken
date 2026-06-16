package com.rainy.token.data.repository;

import com.rainy.token.data.cache.BalanceCache;
import com.rainy.token.data.remote.DeepSeekApi;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata("javax.inject.Singleton")
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
public final class DeepSeekRepository_Factory implements Factory<DeepSeekRepository> {
  private final Provider<DeepSeekApi> deepSeekApiProvider;

  private final Provider<CredentialRepository> credentialRepositoryProvider;

  private final Provider<BalanceCache> balanceCacheProvider;

  private DeepSeekRepository_Factory(Provider<DeepSeekApi> deepSeekApiProvider,
      Provider<CredentialRepository> credentialRepositoryProvider,
      Provider<BalanceCache> balanceCacheProvider) {
    this.deepSeekApiProvider = deepSeekApiProvider;
    this.credentialRepositoryProvider = credentialRepositoryProvider;
    this.balanceCacheProvider = balanceCacheProvider;
  }

  @Override
  public DeepSeekRepository get() {
    return newInstance(deepSeekApiProvider.get(), credentialRepositoryProvider.get(), balanceCacheProvider.get());
  }

  public static DeepSeekRepository_Factory create(Provider<DeepSeekApi> deepSeekApiProvider,
      Provider<CredentialRepository> credentialRepositoryProvider,
      Provider<BalanceCache> balanceCacheProvider) {
    return new DeepSeekRepository_Factory(deepSeekApiProvider, credentialRepositoryProvider, balanceCacheProvider);
  }

  public static DeepSeekRepository newInstance(DeepSeekApi deepSeekApi,
      CredentialRepository credentialRepository, BalanceCache balanceCache) {
    return new DeepSeekRepository(deepSeekApi, credentialRepository, balanceCache);
  }
}
