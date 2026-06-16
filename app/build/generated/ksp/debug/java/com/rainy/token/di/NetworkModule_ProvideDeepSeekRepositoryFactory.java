package com.rainy.token.di;

import com.rainy.token.data.cache.BalanceCache;
import com.rainy.token.data.remote.DeepSeekApi;
import com.rainy.token.data.repository.CredentialRepository;
import com.rainy.token.data.repository.DeepSeekRepository;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
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
public final class NetworkModule_ProvideDeepSeekRepositoryFactory implements Factory<DeepSeekRepository> {
  private final Provider<DeepSeekApi> deepSeekApiProvider;

  private final Provider<CredentialRepository> credentialRepositoryProvider;

  private final Provider<BalanceCache> balanceCacheProvider;

  private NetworkModule_ProvideDeepSeekRepositoryFactory(Provider<DeepSeekApi> deepSeekApiProvider,
      Provider<CredentialRepository> credentialRepositoryProvider,
      Provider<BalanceCache> balanceCacheProvider) {
    this.deepSeekApiProvider = deepSeekApiProvider;
    this.credentialRepositoryProvider = credentialRepositoryProvider;
    this.balanceCacheProvider = balanceCacheProvider;
  }

  @Override
  public DeepSeekRepository get() {
    return provideDeepSeekRepository(deepSeekApiProvider.get(), credentialRepositoryProvider.get(), balanceCacheProvider.get());
  }

  public static NetworkModule_ProvideDeepSeekRepositoryFactory create(
      Provider<DeepSeekApi> deepSeekApiProvider,
      Provider<CredentialRepository> credentialRepositoryProvider,
      Provider<BalanceCache> balanceCacheProvider) {
    return new NetworkModule_ProvideDeepSeekRepositoryFactory(deepSeekApiProvider, credentialRepositoryProvider, balanceCacheProvider);
  }

  public static DeepSeekRepository provideDeepSeekRepository(DeepSeekApi deepSeekApi,
      CredentialRepository credentialRepository, BalanceCache balanceCache) {
    return Preconditions.checkNotNullFromProvides(NetworkModule.INSTANCE.provideDeepSeekRepository(deepSeekApi, credentialRepository, balanceCache));
  }
}
