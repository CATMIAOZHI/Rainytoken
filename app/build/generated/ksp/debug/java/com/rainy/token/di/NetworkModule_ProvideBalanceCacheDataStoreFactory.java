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
public final class NetworkModule_ProvideBalanceCacheDataStoreFactory implements Factory<DataStore<Preferences>> {
  private final Provider<Context> contextProvider;

  private NetworkModule_ProvideBalanceCacheDataStoreFactory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public DataStore<Preferences> get() {
    return provideBalanceCacheDataStore(contextProvider.get());
  }

  public static NetworkModule_ProvideBalanceCacheDataStoreFactory create(
      Provider<Context> contextProvider) {
    return new NetworkModule_ProvideBalanceCacheDataStoreFactory(contextProvider);
  }

  public static DataStore<Preferences> provideBalanceCacheDataStore(Context context) {
    return Preconditions.checkNotNullFromProvides(NetworkModule.INSTANCE.provideBalanceCacheDataStore(context));
  }
}
