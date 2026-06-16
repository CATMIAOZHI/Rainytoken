package com.rainy.token.ui.settings;

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
public final class SettingsViewModel_Factory implements Factory<SettingsViewModel> {
  private final Provider<CredentialRepository> credentialRepositoryProvider;

  private SettingsViewModel_Factory(Provider<CredentialRepository> credentialRepositoryProvider) {
    this.credentialRepositoryProvider = credentialRepositoryProvider;
  }

  @Override
  public SettingsViewModel get() {
    return newInstance(credentialRepositoryProvider.get());
  }

  public static SettingsViewModel_Factory create(
      Provider<CredentialRepository> credentialRepositoryProvider) {
    return new SettingsViewModel_Factory(credentialRepositoryProvider);
  }

  public static SettingsViewModel newInstance(CredentialRepository credentialRepository) {
    return new SettingsViewModel(credentialRepository);
  }
}
