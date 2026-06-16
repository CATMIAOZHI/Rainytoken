package com.rainy.token.domain.usecase;

import com.rainy.token.data.local.UsageCache;
import com.rainy.token.data.repository.OpenCodeUsageRepository;
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
public final class SyncUsageUseCase_Factory implements Factory<SyncUsageUseCase> {
  private final Provider<OpenCodeUsageRepository> usageRepoProvider;

  private final Provider<UsageCache> cacheProvider;

  private SyncUsageUseCase_Factory(Provider<OpenCodeUsageRepository> usageRepoProvider,
      Provider<UsageCache> cacheProvider) {
    this.usageRepoProvider = usageRepoProvider;
    this.cacheProvider = cacheProvider;
  }

  @Override
  public SyncUsageUseCase get() {
    return newInstance(usageRepoProvider, cacheProvider);
  }

  public static SyncUsageUseCase_Factory create(Provider<OpenCodeUsageRepository> usageRepoProvider,
      Provider<UsageCache> cacheProvider) {
    return new SyncUsageUseCase_Factory(usageRepoProvider, cacheProvider);
  }

  public static SyncUsageUseCase newInstance(
      javax.inject.Provider<OpenCodeUsageRepository> usageRepoProvider,
      javax.inject.Provider<UsageCache> cacheProvider) {
    return new SyncUsageUseCase(usageRepoProvider, cacheProvider);
  }
}
