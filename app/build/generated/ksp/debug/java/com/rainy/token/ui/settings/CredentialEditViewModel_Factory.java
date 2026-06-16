package com.rainy.token.ui.settings;

import com.rainy.token.data.repository.CredentialRepository;
import com.rainy.token.data.repository.OpenCodeGoRepository;
import com.rainy.token.domain.usecase.RefreshBalanceUseCase;
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
public final class CredentialEditViewModel_Factory implements Factory<CredentialEditViewModel> {
  private final Provider<CredentialRepository> credentialRepositoryProvider;

  private final Provider<OpenCodeGoRepository> openCodeGoRepositoryProvider;

  private final Provider<RefreshBalanceUseCase> refreshBalanceUseCaseProvider;

  private CredentialEditViewModel_Factory(
      Provider<CredentialRepository> credentialRepositoryProvider,
      Provider<OpenCodeGoRepository> openCodeGoRepositoryProvider,
      Provider<RefreshBalanceUseCase> refreshBalanceUseCaseProvider) {
    this.credentialRepositoryProvider = credentialRepositoryProvider;
    this.openCodeGoRepositoryProvider = openCodeGoRepositoryProvider;
    this.refreshBalanceUseCaseProvider = refreshBalanceUseCaseProvider;
  }

  @Override
  public CredentialEditViewModel get() {
    return newInstance(credentialRepositoryProvider.get(), openCodeGoRepositoryProvider, refreshBalanceUseCaseProvider);
  }

  public static CredentialEditViewModel_Factory create(
      Provider<CredentialRepository> credentialRepositoryProvider,
      Provider<OpenCodeGoRepository> openCodeGoRepositoryProvider,
      Provider<RefreshBalanceUseCase> refreshBalanceUseCaseProvider) {
    return new CredentialEditViewModel_Factory(credentialRepositoryProvider, openCodeGoRepositoryProvider, refreshBalanceUseCaseProvider);
  }

  public static CredentialEditViewModel newInstance(CredentialRepository credentialRepository,
      javax.inject.Provider<OpenCodeGoRepository> openCodeGoRepositoryProvider,
      javax.inject.Provider<RefreshBalanceUseCase> refreshBalanceUseCaseProvider) {
    return new CredentialEditViewModel(credentialRepository, openCodeGoRepositoryProvider, refreshBalanceUseCaseProvider);
  }
}
