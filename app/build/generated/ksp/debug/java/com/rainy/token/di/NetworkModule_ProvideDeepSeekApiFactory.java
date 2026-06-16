package com.rainy.token.di;

import com.rainy.token.data.remote.DeepSeekApi;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import retrofit2.Retrofit;

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
public final class NetworkModule_ProvideDeepSeekApiFactory implements Factory<DeepSeekApi> {
  private final Provider<Retrofit> retrofitProvider;

  private NetworkModule_ProvideDeepSeekApiFactory(Provider<Retrofit> retrofitProvider) {
    this.retrofitProvider = retrofitProvider;
  }

  @Override
  public DeepSeekApi get() {
    return provideDeepSeekApi(retrofitProvider.get());
  }

  public static NetworkModule_ProvideDeepSeekApiFactory create(
      Provider<Retrofit> retrofitProvider) {
    return new NetworkModule_ProvideDeepSeekApiFactory(retrofitProvider);
  }

  public static DeepSeekApi provideDeepSeekApi(Retrofit retrofit) {
    return Preconditions.checkNotNullFromProvides(NetworkModule.INSTANCE.provideDeepSeekApi(retrofit));
  }
}
