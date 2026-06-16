package com.rainy.token.di;

import com.rainy.token.data.cache.BalanceCache;
import com.rainy.token.data.repository.CredentialRepository;
import com.rainy.token.data.repository.OpenCodeGoRepository;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import okhttp3.OkHttpClient;

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
public final class NetworkModule_ProvideOpenCodeGoRepositoryFactory implements Factory<OpenCodeGoRepository> {
  private final Provider<OkHttpClient> okHttpClientProvider;

  private final Provider<CredentialRepository> credentialRepositoryProvider;

  private final Provider<BalanceCache> balanceCacheProvider;

  private NetworkModule_ProvideOpenCodeGoRepositoryFactory(
      Provider<OkHttpClient> okHttpClientProvider,
      Provider<CredentialRepository> credentialRepositoryProvider,
      Provider<BalanceCache> balanceCacheProvider) {
    this.okHttpClientProvider = okHttpClientProvider;
    this.credentialRepositoryProvider = credentialRepositoryProvider;
    this.balanceCacheProvider = balanceCacheProvider;
  }

  @Override
  public OpenCodeGoRepository get() {
    return provideOpenCodeGoRepository(okHttpClientProvider.get(), credentialRepositoryProvider.get(), balanceCacheProvider.get());
  }

  public static NetworkModule_ProvideOpenCodeGoRepositoryFactory create(
      Provider<OkHttpClient> okHttpClientProvider,
      Provider<CredentialRepository> credentialRepositoryProvider,
      Provider<BalanceCache> balanceCacheProvider) {
    return new NetworkModule_ProvideOpenCodeGoRepositoryFactory(okHttpClientProvider, credentialRepositoryProvider, balanceCacheProvider);
  }

  public static OpenCodeGoRepository provideOpenCodeGoRepository(OkHttpClient okHttpClient,
      CredentialRepository credentialRepository, BalanceCache balanceCache) {
    return Preconditions.checkNotNullFromProvides(NetworkModule.INSTANCE.provideOpenCodeGoRepository(okHttpClient, credentialRepository, balanceCache));
  }
}
