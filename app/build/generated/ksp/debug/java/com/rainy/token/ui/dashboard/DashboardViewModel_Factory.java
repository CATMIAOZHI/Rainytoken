package com.rainy.token.ui.dashboard;

import android.content.Context;
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
@QualifierMetadata("dagger.hilt.android.qualifiers.ApplicationContext")
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
public final class DashboardViewModel_Factory implements Factory<DashboardViewModel> {
  private final Provider<CredentialRepository> credentialRepositoryProvider;

  private final Provider<BalanceCache> balanceCacheProvider;

  private final Provider<RefreshBalanceUseCase> refreshBalanceUseCaseProvider;

  private final Provider<Context> appContextProvider;

  private DashboardViewModel_Factory(Provider<CredentialRepository> credentialRepositoryProvider,
      Provider<BalanceCache> balanceCacheProvider,
      Provider<RefreshBalanceUseCase> refreshBalanceUseCaseProvider,
      Provider<Context> appContextProvider) {
    this.credentialRepositoryProvider = credentialRepositoryProvider;
    this.balanceCacheProvider = balanceCacheProvider;
    this.refreshBalanceUseCaseProvider = refreshBalanceUseCaseProvider;
    this.appContextProvider = appContextProvider;
  }

  @Override
  public DashboardViewModel get() {
    return newInstance(credentialRepositoryProvider.get(), balanceCacheProvider.get(), refreshBalanceUseCaseProvider.get(), appContextProvider.get());
  }

  public static DashboardViewModel_Factory create(
      Provider<CredentialRepository> credentialRepositoryProvider,
      Provider<BalanceCache> balanceCacheProvider,
      Provider<RefreshBalanceUseCase> refreshBalanceUseCaseProvider,
      Provider<Context> appContextProvider) {
    return new DashboardViewModel_Factory(credentialRepositoryProvider, balanceCacheProvider, refreshBalanceUseCaseProvider, appContextProvider);
  }

  public static DashboardViewModel newInstance(CredentialRepository credentialRepository,
      BalanceCache balanceCache, RefreshBalanceUseCase refreshBalanceUseCase, Context appContext) {
    return new DashboardViewModel(credentialRepository, balanceCache, refreshBalanceUseCase, appContext);
  }
}
