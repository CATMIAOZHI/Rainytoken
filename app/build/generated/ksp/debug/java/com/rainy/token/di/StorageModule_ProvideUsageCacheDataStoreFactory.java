package com.rainy.token.di;

import android.content.Context;
import androidx.datastore.core.DataStore;
import androidx.datastore.preferences.core.Preferences;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata({
    "javax.inject.Named",
    "dagger.hilt.android.qualifiers.ApplicationContext"
})
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
public final class StorageModule_ProvideUsageCacheDataStoreFactory implements Factory<DataStore<Preferences>> {
  private final Provider<Context> contextProvider;

  private StorageModule_ProvideUsageCacheDataStoreFactory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public DataStore<Preferences> get() {
    return provideUsageCacheDataStore(contextProvider.get());
  }

  public static StorageModule_ProvideUsageCacheDataStoreFactory create(
      Provider<Context> contextProvider) {
    return new StorageModule_ProvideUsageCacheDataStoreFactory(contextProvider);
  }

  public static DataStore<Preferences> provideUsageCacheDataStore(Context context) {
    return Preconditions.checkNotNullFromProvides(StorageModule.INSTANCE.provideUsageCacheDataStore(context));
  }
}
