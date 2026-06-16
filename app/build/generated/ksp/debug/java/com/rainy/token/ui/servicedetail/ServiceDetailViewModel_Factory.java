package com.rainy.token.ui.servicedetail;

import com.rainy.token.data.cache.BalanceCache;
import com.rainy.token.data.repository.CredentialRepository;
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
public final class ServiceDetailViewModel_Factory implements Factory<ServiceDetailViewModel> {
  private final Provider<CredentialRepository> credentialRepositoryProvider;

  private final Provider<BalanceCache> balanceCacheProvider;

  private final Provider<RefreshBalanceUseCase> refreshBalanceUseCaseProvider;

  private ServiceDetailViewModel_Factory(
      Provider<CredentialRepository> credentialRepositoryProvider,
      Provider<BalanceCache> balanceCacheProvider,
      Provider<RefreshBalanceUseCase> refreshBalanceUseCaseProvider) {
    this.credentialRepositoryProvider = credentialRepositoryProvider;
    this.balanceCacheProvider = balanceCacheProvider;
    this.refreshBalanceUseCaseProvider = refreshBalanceUseCaseProvider;
  }

  @Override
  public ServiceDetailViewModel get() {
    return newInstance(credentialRepositoryProvider.get(), balanceCacheProvider.get(), refreshBalanceUseCaseProvider.get());
  }

  public static ServiceDetailViewModel_Factory create(
      Provider<CredentialRepository> credentialRepositoryProvider,
      Provider<BalanceCache> balanceCacheProvider,
      Provider<RefreshBalanceUseCase> refreshBalanceUseCaseProvider) {
    return new ServiceDetailViewModel_Factory(credentialRepositoryProvider, balanceCacheProvider, refreshBalanceUseCaseProvider);
  }

  public static ServiceDetailViewModel newInstance(CredentialRepository credentialRepository,
      BalanceCache balanceCache, RefreshBalanceUseCase refreshBalanceUseCase) {
    return new ServiceDetailViewModel(credentialRepository, balanceCache, refreshBalanceUseCase);
  }
}
