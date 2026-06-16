package com.rainy.token.data.repository;

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
public final class WebViewSessionSaver_Factory implements Factory<WebViewSessionSaver> {
  private final Provider<CredentialRepository> credentialRepositoryProvider;

  private WebViewSessionSaver_Factory(Provider<CredentialRepository> credentialRepositoryProvider) {
    this.credentialRepositoryProvider = credentialRepositoryProvider;
  }

  @Override
  public WebViewSessionSaver get() {
    return newInstance(credentialRepositoryProvider.get());
  }

  public static WebViewSessionSaver_Factory create(
      Provider<CredentialRepository> credentialRepositoryProvider) {
    return new WebViewSessionSaver_Factory(credentialRepositoryProvider);
  }

  public static WebViewSessionSaver newInstance(CredentialRepository credentialRepository) {
    return new WebViewSessionSaver(credentialRepository);
  }
}
