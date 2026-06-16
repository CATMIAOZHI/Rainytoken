package com.rainy.token.data.repository;

import com.rainy.token.data.local.SecureStorage;
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
public final class CredentialRepository_Factory implements Factory<CredentialRepository> {
  private final Provider<SecureStorage> secureStorageProvider;

  private CredentialRepository_Factory(Provider<SecureStorage> secureStorageProvider) {
    this.secureStorageProvider = secureStorageProvider;
  }

  @Override
  public CredentialRepository get() {
    return newInstance(secureStorageProvider.get());
  }

  public static CredentialRepository_Factory create(Provider<SecureStorage> secureStorageProvider) {
    return new CredentialRepository_Factory(secureStorageProvider);
  }

  public static CredentialRepository newInstance(SecureStorage secureStorage) {
    return new CredentialRepository(secureStorage);
  }
}
