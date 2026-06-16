package com.rainy.token.di;

import com.rainy.token.data.repository.CredentialRepository;
import com.rainy.token.data.repository.OpenCodeUsageRepository;
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
public final class NetworkModule_ProvideOpenCodeUsageRepositoryFactory implements Factory<OpenCodeUsageRepository> {
  private final Provider<OkHttpClient> okHttpClientProvider;

  private final Provider<CredentialRepository> credentialRepositoryProvider;

  private NetworkModule_ProvideOpenCodeUsageRepositoryFactory(
      Provider<OkHttpClient> okHttpClientProvider,
      Provider<CredentialRepository> credentialRepositoryProvider) {
    this.okHttpClientProvider = okHttpClientProvider;
    this.credentialRepositoryProvider = credentialRepositoryProvider;
  }

  @Override
  public OpenCodeUsageRepository get() {
    return provideOpenCodeUsageRepository(okHttpClientProvider.get(), credentialRepositoryProvider.get());
  }

  public static NetworkModule_ProvideOpenCodeUsageRepositoryFactory create(
      Provider<OkHttpClient> okHttpClientProvider,
      Provider<CredentialRepository> credentialRepositoryProvider) {
    return new NetworkModule_ProvideOpenCodeUsageRepositoryFactory(okHttpClientProvider, credentialRepositoryProvider);
  }

  public static OpenCodeUsageRepository provideOpenCodeUsageRepository(OkHttpClient okHttpClient,
      CredentialRepository credentialRepository) {
    return Preconditions.checkNotNullFromProvides(NetworkModule.INSTANCE.provideOpenCodeUsageRepository(okHttpClient, credentialRepository));
  }
}
