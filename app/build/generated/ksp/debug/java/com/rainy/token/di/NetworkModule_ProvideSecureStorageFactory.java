package com.rainy.token.di;

import androidx.datastore.core.DataStore;
import androidx.datastore.preferences.core.Preferences;
import com.rainy.token.data.local.SecureStorage;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import kotlinx.serialization.json.Json;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata("javax.inject.Named")
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
public final class NetworkModule_ProvideSecureStorageFactory implements Factory<SecureStorage> {
  private final Provider<DataStore<Preferences>> dataStoreProvider;

  private final Provider<Json> jsonProvider;

  private NetworkModule_ProvideSecureStorageFactory(
      Provider<DataStore<Preferences>> dataStoreProvider, Provider<Json> jsonProvider) {
    this.dataStoreProvider = dataStoreProvider;
    this.jsonProvider = jsonProvider;
  }

  @Override
  public SecureStorage get() {
    return provideSecureStorage(dataStoreProvider.get(), jsonProvider.get());
  }

  public static NetworkModule_ProvideSecureStorageFactory create(
      Provider<DataStore<Preferences>> dataStoreProvider, Provider<Json> jsonProvider) {
    return new NetworkModule_ProvideSecureStorageFactory(dataStoreProvider, jsonProvider);
  }

  public static SecureStorage provideSecureStorage(DataStore<Preferences> dataStore, Json json) {
    return Preconditions.checkNotNullFromProvides(NetworkModule.INSTANCE.provideSecureStorage(dataStore, json));
  }
}
