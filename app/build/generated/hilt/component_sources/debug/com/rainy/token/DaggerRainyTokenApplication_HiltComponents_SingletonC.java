package com.rainy.token;

import android.app.Activity;
import android.app.Service;
import android.view.View;
import androidx.datastore.core.DataStore;
import androidx.datastore.preferences.core.Preferences;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import com.rainy.token.data.cache.BalanceCache;
import com.rainy.token.data.local.SecureStorage;
import com.rainy.token.data.local.UsageCache;
import com.rainy.token.data.remote.DeepSeekApi;
import com.rainy.token.data.repository.CredentialRepository;
import com.rainy.token.data.repository.DeepSeekRepository;
import com.rainy.token.data.repository.OpenCodeGoRepository;
import com.rainy.token.data.repository.OpenCodeUsageRepository;
import com.rainy.token.data.repository.WebViewSessionSaver;
import com.rainy.token.di.NetworkModule_ProvideBalanceCacheDataStoreFactory;
import com.rainy.token.di.NetworkModule_ProvideBalanceCacheFactory;
import com.rainy.token.di.NetworkModule_ProvideDeepSeekApiFactory;
import com.rainy.token.di.NetworkModule_ProvideDeepSeekRepositoryFactory;
import com.rainy.token.di.NetworkModule_ProvideJsonFactory;
import com.rainy.token.di.NetworkModule_ProvideOkHttpClientFactory;
import com.rainy.token.di.NetworkModule_ProvideOpenCodeGoRepositoryFactory;
import com.rainy.token.di.NetworkModule_ProvideOpenCodeUsageRepositoryFactory;
import com.rainy.token.di.NetworkModule_ProvideRetrofitFactory;
import com.rainy.token.di.NetworkModule_ProvideSecureStorageFactory;
import com.rainy.token.di.NetworkModule_ProvideUsageCacheFactory;
import com.rainy.token.di.StorageModule_ProvideSecureStorageDataStoreFactory;
import com.rainy.token.di.StorageModule_ProvideUsageCacheDataStoreFactory;
import com.rainy.token.domain.usecase.RefreshBalanceUseCase;
import com.rainy.token.domain.usecase.SyncUsageUseCase;
import com.rainy.token.ui.dashboard.DashboardViewModel;
import com.rainy.token.ui.dashboard.DashboardViewModel_HiltModules;
import com.rainy.token.ui.dashboard.DashboardViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import com.rainy.token.ui.dashboard.DashboardViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import com.rainy.token.ui.dashboard.UsageChartViewModel;
import com.rainy.token.ui.dashboard.UsageChartViewModel_HiltModules;
import com.rainy.token.ui.dashboard.UsageChartViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import com.rainy.token.ui.dashboard.UsageChartViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import com.rainy.token.ui.dashboard.UsageDataViewModel;
import com.rainy.token.ui.dashboard.UsageDataViewModel_HiltModules;
import com.rainy.token.ui.dashboard.UsageDataViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import com.rainy.token.ui.dashboard.UsageDataViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import com.rainy.token.ui.dashboard.UsageViewModel;
import com.rainy.token.ui.dashboard.UsageViewModel_HiltModules;
import com.rainy.token.ui.dashboard.UsageViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import com.rainy.token.ui.dashboard.UsageViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import com.rainy.token.ui.servicedetail.ServiceDetailViewModel;
import com.rainy.token.ui.servicedetail.ServiceDetailViewModel_HiltModules;
import com.rainy.token.ui.servicedetail.ServiceDetailViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import com.rainy.token.ui.servicedetail.ServiceDetailViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import com.rainy.token.ui.settings.CredentialEditViewModel;
import com.rainy.token.ui.settings.CredentialEditViewModel_HiltModules;
import com.rainy.token.ui.settings.CredentialEditViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import com.rainy.token.ui.settings.CredentialEditViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import com.rainy.token.ui.settings.SettingsViewModel;
import com.rainy.token.ui.settings.SettingsViewModel_HiltModules;
import com.rainy.token.ui.settings.SettingsViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import com.rainy.token.ui.settings.SettingsViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import com.rainy.token.ui.webview.WebViewLoginViewModel;
import com.rainy.token.ui.webview.WebViewLoginViewModel_HiltModules;
import com.rainy.token.ui.webview.WebViewLoginViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import com.rainy.token.ui.webview.WebViewLoginViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import dagger.hilt.android.ActivityRetainedLifecycle;
import dagger.hilt.android.ViewModelLifecycle;
import dagger.hilt.android.internal.builders.ActivityComponentBuilder;
import dagger.hilt.android.internal.builders.ActivityRetainedComponentBuilder;
import dagger.hilt.android.internal.builders.FragmentComponentBuilder;
import dagger.hilt.android.internal.builders.ServiceComponentBuilder;
import dagger.hilt.android.internal.builders.ViewComponentBuilder;
import dagger.hilt.android.internal.builders.ViewModelComponentBuilder;
import dagger.hilt.android.internal.builders.ViewWithFragmentComponentBuilder;
import dagger.hilt.android.internal.lifecycle.DefaultViewModelFactories;
import dagger.hilt.android.internal.lifecycle.DefaultViewModelFactories_InternalFactoryFactory_Factory;
import dagger.hilt.android.internal.managers.ActivityRetainedComponentManager_LifecycleModule_ProvideActivityRetainedLifecycleFactory;
import dagger.hilt.android.internal.managers.SavedStateHandleHolder;
import dagger.hilt.android.internal.modules.ApplicationContextModule;
import dagger.hilt.android.internal.modules.ApplicationContextModule_ProvideContextFactory;
import dagger.internal.DaggerGenerated;
import dagger.internal.DoubleCheck;
import dagger.internal.LazyClassKeyMap;
import dagger.internal.MapBuilder;
import dagger.internal.Preconditions;
import dagger.internal.Provider;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Generated;
import kotlinx.serialization.json.Json;
import okhttp3.OkHttpClient;
import retrofit2.Retrofit;

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
public final class DaggerRainyTokenApplication_HiltComponents_SingletonC {
  private DaggerRainyTokenApplication_HiltComponents_SingletonC() {
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private ApplicationContextModule applicationContextModule;

    private Builder() {
    }

    public Builder applicationContextModule(ApplicationContextModule applicationContextModule) {
      this.applicationContextModule = Preconditions.checkNotNull(applicationContextModule);
      return this;
    }

    public RainyTokenApplication_HiltComponents.SingletonC build() {
      Preconditions.checkBuilderRequirement(applicationContextModule, ApplicationContextModule.class);
      return new SingletonCImpl(applicationContextModule);
    }
  }

  private static final class ActivityRetainedCBuilder implements RainyTokenApplication_HiltComponents.ActivityRetainedC.Builder {
    private final SingletonCImpl singletonCImpl;

    private SavedStateHandleHolder savedStateHandleHolder;

    private ActivityRetainedCBuilder(SingletonCImpl singletonCImpl) {
      this.singletonCImpl = singletonCImpl;
    }

    @Override
    public ActivityRetainedCBuilder savedStateHandleHolder(
        SavedStateHandleHolder savedStateHandleHolder) {
      this.savedStateHandleHolder = Preconditions.checkNotNull(savedStateHandleHolder);
      return this;
    }

    @Override
    public RainyTokenApplication_HiltComponents.ActivityRetainedC build() {
      Preconditions.checkBuilderRequirement(savedStateHandleHolder, SavedStateHandleHolder.class);
      return new ActivityRetainedCImpl(singletonCImpl, savedStateHandleHolder);
    }
  }

  private static final class ActivityCBuilder implements RainyTokenApplication_HiltComponents.ActivityC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private Activity activity;

    private ActivityCBuilder(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
    }

    @Override
    public ActivityCBuilder activity(Activity activity) {
      this.activity = Preconditions.checkNotNull(activity);
      return this;
    }

    @Override
    public RainyTokenApplication_HiltComponents.ActivityC build() {
      Preconditions.checkBuilderRequirement(activity, Activity.class);
      return new ActivityCImpl(singletonCImpl, activityRetainedCImpl, activity);
    }
  }

  private static final class FragmentCBuilder implements RainyTokenApplication_HiltComponents.FragmentC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private Fragment fragment;

    private FragmentCBuilder(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, ActivityCImpl activityCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;
    }

    @Override
    public FragmentCBuilder fragment(Fragment fragment) {
      this.fragment = Preconditions.checkNotNull(fragment);
      return this;
    }

    @Override
    public RainyTokenApplication_HiltComponents.FragmentC build() {
      Preconditions.checkBuilderRequirement(fragment, Fragment.class);
      return new FragmentCImpl(singletonCImpl, activityRetainedCImpl, activityCImpl, fragment);
    }
  }

  private static final class ViewWithFragmentCBuilder implements RainyTokenApplication_HiltComponents.ViewWithFragmentC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private final FragmentCImpl fragmentCImpl;

    private View view;

    private ViewWithFragmentCBuilder(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, ActivityCImpl activityCImpl,
        FragmentCImpl fragmentCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;
      this.fragmentCImpl = fragmentCImpl;
    }

    @Override
    public ViewWithFragmentCBuilder view(View view) {
      this.view = Preconditions.checkNotNull(view);
      return this;
    }

    @Override
    public RainyTokenApplication_HiltComponents.ViewWithFragmentC build() {
      Preconditions.checkBuilderRequirement(view, View.class);
      return new ViewWithFragmentCImpl(singletonCImpl, activityRetainedCImpl, activityCImpl, fragmentCImpl, view);
    }
  }

  private static final class ViewCBuilder implements RainyTokenApplication_HiltComponents.ViewC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private View view;

    private ViewCBuilder(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
        ActivityCImpl activityCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;
    }

    @Override
    public ViewCBuilder view(View view) {
      this.view = Preconditions.checkNotNull(view);
      return this;
    }

    @Override
    public RainyTokenApplication_HiltComponents.ViewC build() {
      Preconditions.checkBuilderRequirement(view, View.class);
      return new ViewCImpl(singletonCImpl, activityRetainedCImpl, activityCImpl, view);
    }
  }

  private static final class ViewModelCBuilder implements RainyTokenApplication_HiltComponents.ViewModelC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private SavedStateHandle savedStateHandle;

    private ViewModelLifecycle viewModelLifecycle;

    private ViewModelCBuilder(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
    }

    @Override
    public ViewModelCBuilder savedStateHandle(SavedStateHandle handle) {
      this.savedStateHandle = Preconditions.checkNotNull(handle);
      return this;
    }

    @Override
    public ViewModelCBuilder viewModelLifecycle(ViewModelLifecycle viewModelLifecycle) {
      this.viewModelLifecycle = Preconditions.checkNotNull(viewModelLifecycle);
      return this;
    }

    @Override
    public RainyTokenApplication_HiltComponents.ViewModelC build() {
      Preconditions.checkBuilderRequirement(savedStateHandle, SavedStateHandle.class);
      Preconditions.checkBuilderRequirement(viewModelLifecycle, ViewModelLifecycle.class);
      return new ViewModelCImpl(singletonCImpl, activityRetainedCImpl, savedStateHandle, viewModelLifecycle);
    }
  }

  private static final class ServiceCBuilder implements RainyTokenApplication_HiltComponents.ServiceC.Builder {
    private final SingletonCImpl singletonCImpl;

    private Service service;

    private ServiceCBuilder(SingletonCImpl singletonCImpl) {
      this.singletonCImpl = singletonCImpl;
    }

    @Override
    public ServiceCBuilder service(Service service) {
      this.service = Preconditions.checkNotNull(service);
      return this;
    }

    @Override
    public RainyTokenApplication_HiltComponents.ServiceC build() {
      Preconditions.checkBuilderRequirement(service, Service.class);
      return new ServiceCImpl(singletonCImpl, service);
    }
  }

  private static final class ViewWithFragmentCImpl extends RainyTokenApplication_HiltComponents.ViewWithFragmentC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private final FragmentCImpl fragmentCImpl;

    private final ViewWithFragmentCImpl viewWithFragmentCImpl = this;

    ViewWithFragmentCImpl(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, ActivityCImpl activityCImpl,
        FragmentCImpl fragmentCImpl, View viewParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;
      this.fragmentCImpl = fragmentCImpl;


    }
  }

  private static final class FragmentCImpl extends RainyTokenApplication_HiltComponents.FragmentC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private final FragmentCImpl fragmentCImpl = this;

    FragmentCImpl(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
        ActivityCImpl activityCImpl, Fragment fragmentParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;


    }

    @Override
    public DefaultViewModelFactories.InternalFactoryFactory getHiltInternalFactoryFactory() {
      return activityCImpl.getHiltInternalFactoryFactory();
    }

    @Override
    public ViewWithFragmentComponentBuilder viewWithFragmentComponentBuilder() {
      return new ViewWithFragmentCBuilder(singletonCImpl, activityRetainedCImpl, activityCImpl, fragmentCImpl);
    }
  }

  private static final class ViewCImpl extends RainyTokenApplication_HiltComponents.ViewC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private final ViewCImpl viewCImpl = this;

    ViewCImpl(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
        ActivityCImpl activityCImpl, View viewParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;


    }
  }

  private static final class ActivityCImpl extends RainyTokenApplication_HiltComponents.ActivityC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl = this;

    ActivityCImpl(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
        Activity activityParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;


    }

    Map keySetMapOfClassOfAndBooleanBuilder() {
      MapBuilder mapBuilder = MapBuilder.<String, Boolean>newMapBuilder(8);
      mapBuilder.put(CredentialEditViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, CredentialEditViewModel_HiltModules.KeyModule.provide());
      mapBuilder.put(DashboardViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, DashboardViewModel_HiltModules.KeyModule.provide());
      mapBuilder.put(ServiceDetailViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, ServiceDetailViewModel_HiltModules.KeyModule.provide());
      mapBuilder.put(SettingsViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, SettingsViewModel_HiltModules.KeyModule.provide());
      mapBuilder.put(UsageChartViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, UsageChartViewModel_HiltModules.KeyModule.provide());
      mapBuilder.put(UsageDataViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, UsageDataViewModel_HiltModules.KeyModule.provide());
      mapBuilder.put(UsageViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, UsageViewModel_HiltModules.KeyModule.provide());
      mapBuilder.put(WebViewLoginViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, WebViewLoginViewModel_HiltModules.KeyModule.provide());
      return mapBuilder.build();
    }

    @Override
    public void injectMainActivity(MainActivity arg0) {
    }

    @Override
    public DefaultViewModelFactories.InternalFactoryFactory getHiltInternalFactoryFactory() {
      return DefaultViewModelFactories_InternalFactoryFactory_Factory.newInstance(getViewModelKeys(), new ViewModelCBuilder(singletonCImpl, activityRetainedCImpl));
    }

    @Override
    public Map<Class<?>, Boolean> getViewModelKeys() {
      return LazyClassKeyMap.<Boolean>of(keySetMapOfClassOfAndBooleanBuilder());
    }

    @Override
    public ViewModelComponentBuilder getViewModelComponentBuilder() {
      return new ViewModelCBuilder(singletonCImpl, activityRetainedCImpl);
    }

    @Override
    public FragmentComponentBuilder fragmentComponentBuilder() {
      return new FragmentCBuilder(singletonCImpl, activityRetainedCImpl, activityCImpl);
    }

    @Override
    public ViewComponentBuilder viewComponentBuilder() {
      return new ViewCBuilder(singletonCImpl, activityRetainedCImpl, activityCImpl);
    }
  }

  private static final class ViewModelCImpl extends RainyTokenApplication_HiltComponents.ViewModelC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ViewModelCImpl viewModelCImpl = this;

    Provider<CredentialEditViewModel> credentialEditViewModelProvider;

    Provider<DashboardViewModel> dashboardViewModelProvider;

    Provider<ServiceDetailViewModel> serviceDetailViewModelProvider;

    Provider<SettingsViewModel> settingsViewModelProvider;

    Provider<UsageChartViewModel> usageChartViewModelProvider;

    Provider<UsageDataViewModel> usageDataViewModelProvider;

    Provider<SyncUsageUseCase> syncUsageUseCaseProvider;

    Provider<UsageViewModel> usageViewModelProvider;

    Provider<WebViewLoginViewModel> webViewLoginViewModelProvider;

    ViewModelCImpl(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
        SavedStateHandle savedStateHandleParam, ViewModelLifecycle viewModelLifecycleParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;

      initialize(savedStateHandleParam, viewModelLifecycleParam);

    }

    Map hiltViewModelMapMapOfClassOfAndProviderOfViewModelBuilder() {
      MapBuilder mapBuilder = MapBuilder.<String, javax.inject.Provider<ViewModel>>newMapBuilder(8);
      mapBuilder.put(CredentialEditViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) (credentialEditViewModelProvider)));
      mapBuilder.put(DashboardViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) (dashboardViewModelProvider)));
      mapBuilder.put(ServiceDetailViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) (serviceDetailViewModelProvider)));
      mapBuilder.put(SettingsViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) (settingsViewModelProvider)));
      mapBuilder.put(UsageChartViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) (usageChartViewModelProvider)));
      mapBuilder.put(UsageDataViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) (usageDataViewModelProvider)));
      mapBuilder.put(UsageViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) (usageViewModelProvider)));
      mapBuilder.put(WebViewLoginViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) (webViewLoginViewModelProvider)));
      return mapBuilder.build();
    }

    @SuppressWarnings("unchecked")
    private void initialize(final SavedStateHandle savedStateHandleParam,
        final ViewModelLifecycle viewModelLifecycleParam) {
      this.credentialEditViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 0);
      this.dashboardViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 1);
      this.serviceDetailViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 2);
      this.settingsViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 3);
      this.usageChartViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 4);
      this.usageDataViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 5);
      this.syncUsageUseCaseProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 7);
      this.usageViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 6);
      this.webViewLoginViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 8);
    }

    @Override
    public Map<Class<?>, javax.inject.Provider<ViewModel>> getHiltViewModelMap() {
      return LazyClassKeyMap.<javax.inject.Provider<ViewModel>>of(hiltViewModelMapMapOfClassOfAndProviderOfViewModelBuilder());
    }

    @Override
    public Map<Class<?>, Object> getHiltViewModelAssistedMap() {
      return Collections.<Class<?>, Object>emptyMap();
    }

    private static final class SwitchingProvider<T> implements Provider<T> {
      private final SingletonCImpl singletonCImpl;

      private final ActivityRetainedCImpl activityRetainedCImpl;

      private final ViewModelCImpl viewModelCImpl;

      private final int id;

      SwitchingProvider(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
          ViewModelCImpl viewModelCImpl, int id) {
        this.singletonCImpl = singletonCImpl;
        this.activityRetainedCImpl = activityRetainedCImpl;
        this.viewModelCImpl = viewModelCImpl;
        this.id = id;
      }

      @Override
      @SuppressWarnings("unchecked")
      public T get() {
        switch (id) {
          case 0: // com.rainy.token.ui.settings.CredentialEditViewModel
          return (T) new CredentialEditViewModel(singletonCImpl.credentialRepositoryProvider.get(), singletonCImpl.provideOpenCodeGoRepositoryProvider, singletonCImpl.refreshBalanceUseCaseProvider);

          case 1: // com.rainy.token.ui.dashboard.DashboardViewModel
          return (T) new DashboardViewModel(singletonCImpl.credentialRepositoryProvider.get(), singletonCImpl.provideBalanceCacheProvider.get(), singletonCImpl.refreshBalanceUseCaseProvider.get(), ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 2: // com.rainy.token.ui.servicedetail.ServiceDetailViewModel
          return (T) new ServiceDetailViewModel(singletonCImpl.credentialRepositoryProvider.get(), singletonCImpl.provideBalanceCacheProvider.get(), singletonCImpl.refreshBalanceUseCaseProvider.get());

          case 3: // com.rainy.token.ui.settings.SettingsViewModel
          return (T) new SettingsViewModel(singletonCImpl.credentialRepositoryProvider.get());

          case 4: // com.rainy.token.ui.dashboard.UsageChartViewModel
          return (T) new UsageChartViewModel(singletonCImpl.provideUsageCacheProvider, singletonCImpl.credentialRepositoryProvider.get());

          case 5: // com.rainy.token.ui.dashboard.UsageDataViewModel
          return (T) new UsageDataViewModel(singletonCImpl.provideUsageCacheProvider, singletonCImpl.credentialRepositoryProvider.get());

          case 6: // com.rainy.token.ui.dashboard.UsageViewModel
          return (T) new UsageViewModel(singletonCImpl.provideUsageCacheProvider, viewModelCImpl.syncUsageUseCaseProvider, singletonCImpl.credentialRepositoryProvider.get());

          case 7: // com.rainy.token.domain.usecase.SyncUsageUseCase
          return (T) new SyncUsageUseCase(singletonCImpl.provideOpenCodeUsageRepositoryProvider, singletonCImpl.provideUsageCacheProvider);

          case 8: // com.rainy.token.ui.webview.WebViewLoginViewModel
          return (T) new WebViewLoginViewModel(singletonCImpl.webViewSessionSaverProvider.get());

          default: throw new AssertionError(id);
        }
      }
    }
  }

  private static final class ActivityRetainedCImpl extends RainyTokenApplication_HiltComponents.ActivityRetainedC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl = this;

    Provider<ActivityRetainedLifecycle> provideActivityRetainedLifecycleProvider;

    ActivityRetainedCImpl(SingletonCImpl singletonCImpl,
        SavedStateHandleHolder savedStateHandleHolderParam) {
      this.singletonCImpl = singletonCImpl;

      initialize(savedStateHandleHolderParam);

    }

    @SuppressWarnings("unchecked")
    private void initialize(final SavedStateHandleHolder savedStateHandleHolderParam) {
      this.provideActivityRetainedLifecycleProvider = DoubleCheck.provider(new SwitchingProvider<ActivityRetainedLifecycle>(singletonCImpl, activityRetainedCImpl, 0));
    }

    @Override
    public ActivityComponentBuilder activityComponentBuilder() {
      return new ActivityCBuilder(singletonCImpl, activityRetainedCImpl);
    }

    @Override
    public ActivityRetainedLifecycle getActivityRetainedLifecycle() {
      return provideActivityRetainedLifecycleProvider.get();
    }

    private static final class SwitchingProvider<T> implements Provider<T> {
      private final SingletonCImpl singletonCImpl;

      private final ActivityRetainedCImpl activityRetainedCImpl;

      private final int id;

      SwitchingProvider(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
          int id) {
        this.singletonCImpl = singletonCImpl;
        this.activityRetainedCImpl = activityRetainedCImpl;
        this.id = id;
      }

      @Override
      @SuppressWarnings("unchecked")
      public T get() {
        switch (id) {
          case 0: // dagger.hilt.android.ActivityRetainedLifecycle
          return (T) ActivityRetainedComponentManager_LifecycleModule_ProvideActivityRetainedLifecycleFactory.provideActivityRetainedLifecycle();

          default: throw new AssertionError(id);
        }
      }
    }
  }

  private static final class ServiceCImpl extends RainyTokenApplication_HiltComponents.ServiceC {
    private final SingletonCImpl singletonCImpl;

    private final ServiceCImpl serviceCImpl = this;

    ServiceCImpl(SingletonCImpl singletonCImpl, Service serviceParam) {
      this.singletonCImpl = singletonCImpl;


    }
  }

  private static final class SingletonCImpl extends RainyTokenApplication_HiltComponents.SingletonC {
    private final ApplicationContextModule applicationContextModule;

    private final SingletonCImpl singletonCImpl = this;

    Provider<OkHttpClient> provideOkHttpClientProvider;

    Provider<Json> provideJsonProvider;

    Provider<Retrofit> provideRetrofitProvider;

    Provider<DeepSeekApi> provideDeepSeekApiProvider;

    Provider<DataStore<Preferences>> provideSecureStorageDataStoreProvider;

    Provider<SecureStorage> provideSecureStorageProvider;

    Provider<CredentialRepository> credentialRepositoryProvider;

    Provider<DataStore<Preferences>> provideBalanceCacheDataStoreProvider;

    Provider<BalanceCache> provideBalanceCacheProvider;

    Provider<DeepSeekRepository> provideDeepSeekRepositoryProvider;

    Provider<OpenCodeGoRepository> provideOpenCodeGoRepositoryProvider;

    Provider<RefreshBalanceUseCase> refreshBalanceUseCaseProvider;

    Provider<DataStore<Preferences>> provideUsageCacheDataStoreProvider;

    Provider<UsageCache> provideUsageCacheProvider;

    Provider<OpenCodeUsageRepository> provideOpenCodeUsageRepositoryProvider;

    Provider<WebViewSessionSaver> webViewSessionSaverProvider;

    SingletonCImpl(ApplicationContextModule applicationContextModuleParam) {
      this.applicationContextModule = applicationContextModuleParam;
      initialize(applicationContextModuleParam);

    }

    @SuppressWarnings("unchecked")
    private void initialize(final ApplicationContextModule applicationContextModuleParam) {
      this.provideOkHttpClientProvider = DoubleCheck.provider(new SwitchingProvider<OkHttpClient>(singletonCImpl, 4));
      this.provideJsonProvider = DoubleCheck.provider(new SwitchingProvider<Json>(singletonCImpl, 5));
      this.provideRetrofitProvider = DoubleCheck.provider(new SwitchingProvider<Retrofit>(singletonCImpl, 3));
      this.provideDeepSeekApiProvider = DoubleCheck.provider(new SwitchingProvider<DeepSeekApi>(singletonCImpl, 2));
      this.provideSecureStorageDataStoreProvider = DoubleCheck.provider(new SwitchingProvider<DataStore<Preferences>>(singletonCImpl, 8));
      this.provideSecureStorageProvider = DoubleCheck.provider(new SwitchingProvider<SecureStorage>(singletonCImpl, 7));
      this.credentialRepositoryProvider = DoubleCheck.provider(new SwitchingProvider<CredentialRepository>(singletonCImpl, 6));
      this.provideBalanceCacheDataStoreProvider = DoubleCheck.provider(new SwitchingProvider<DataStore<Preferences>>(singletonCImpl, 10));
      this.provideBalanceCacheProvider = DoubleCheck.provider(new SwitchingProvider<BalanceCache>(singletonCImpl, 9));
      this.provideDeepSeekRepositoryProvider = DoubleCheck.provider(new SwitchingProvider<DeepSeekRepository>(singletonCImpl, 1));
      this.provideOpenCodeGoRepositoryProvider = DoubleCheck.provider(new SwitchingProvider<OpenCodeGoRepository>(singletonCImpl, 11));
      this.refreshBalanceUseCaseProvider = new SwitchingProvider<>(singletonCImpl, 0);
      this.provideUsageCacheDataStoreProvider = DoubleCheck.provider(new SwitchingProvider<DataStore<Preferences>>(singletonCImpl, 13));
      this.provideUsageCacheProvider = DoubleCheck.provider(new SwitchingProvider<UsageCache>(singletonCImpl, 12));
      this.provideOpenCodeUsageRepositoryProvider = DoubleCheck.provider(new SwitchingProvider<OpenCodeUsageRepository>(singletonCImpl, 14));
      this.webViewSessionSaverProvider = DoubleCheck.provider(new SwitchingProvider<WebViewSessionSaver>(singletonCImpl, 15));
    }

    @Override
    public void injectRainyTokenApplication(RainyTokenApplication rainyTokenApplication) {
    }

    @Override
    public RefreshBalanceUseCase refreshBalanceUseCase() {
      return refreshBalanceUseCaseProvider.get();
    }

    @Override
    public Set<Boolean> getDisableFragmentGetContextFix() {
      return Collections.<Boolean>emptySet();
    }

    @Override
    public ActivityRetainedComponentBuilder retainedComponentBuilder() {
      return new ActivityRetainedCBuilder(singletonCImpl);
    }

    @Override
    public ServiceComponentBuilder serviceComponentBuilder() {
      return new ServiceCBuilder(singletonCImpl);
    }

    private static final class SwitchingProvider<T> implements Provider<T> {
      private final SingletonCImpl singletonCImpl;

      private final int id;

      SwitchingProvider(SingletonCImpl singletonCImpl, int id) {
        this.singletonCImpl = singletonCImpl;
        this.id = id;
      }

      @Override
      @SuppressWarnings("unchecked")
      public T get() {
        switch (id) {
          case 0: // com.rainy.token.domain.usecase.RefreshBalanceUseCase
          return (T) new RefreshBalanceUseCase(singletonCImpl.provideDeepSeekRepositoryProvider, singletonCImpl.provideOpenCodeGoRepositoryProvider);

          case 1: // com.rainy.token.data.repository.DeepSeekRepository
          return (T) NetworkModule_ProvideDeepSeekRepositoryFactory.provideDeepSeekRepository(singletonCImpl.provideDeepSeekApiProvider.get(), singletonCImpl.credentialRepositoryProvider.get(), singletonCImpl.provideBalanceCacheProvider.get());

          case 2: // com.rainy.token.data.remote.DeepSeekApi
          return (T) NetworkModule_ProvideDeepSeekApiFactory.provideDeepSeekApi(singletonCImpl.provideRetrofitProvider.get());

          case 3: // retrofit2.Retrofit
          return (T) NetworkModule_ProvideRetrofitFactory.provideRetrofit(singletonCImpl.provideOkHttpClientProvider.get(), singletonCImpl.provideJsonProvider.get());

          case 4: // okhttp3.OkHttpClient
          return (T) NetworkModule_ProvideOkHttpClientFactory.provideOkHttpClient();

          case 5: // kotlinx.serialization.json.Json
          return (T) NetworkModule_ProvideJsonFactory.provideJson();

          case 6: // com.rainy.token.data.repository.CredentialRepository
          return (T) new CredentialRepository(singletonCImpl.provideSecureStorageProvider.get());

          case 7: // com.rainy.token.data.local.SecureStorage
          return (T) NetworkModule_ProvideSecureStorageFactory.provideSecureStorage(singletonCImpl.provideSecureStorageDataStoreProvider.get(), singletonCImpl.provideJsonProvider.get());

          case 8: // @javax.inject.Named("dataStore.secureStorage") androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
          return (T) StorageModule_ProvideSecureStorageDataStoreFactory.provideSecureStorageDataStore(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 9: // com.rainy.token.data.cache.BalanceCache
          return (T) NetworkModule_ProvideBalanceCacheFactory.provideBalanceCache(singletonCImpl.provideBalanceCacheDataStoreProvider.get(), singletonCImpl.provideJsonProvider.get());

          case 10: // @javax.inject.Named("dataStore.balanceCache") androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
          return (T) NetworkModule_ProvideBalanceCacheDataStoreFactory.provideBalanceCacheDataStore(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 11: // com.rainy.token.data.repository.OpenCodeGoRepository
          return (T) NetworkModule_ProvideOpenCodeGoRepositoryFactory.provideOpenCodeGoRepository(singletonCImpl.provideOkHttpClientProvider.get(), singletonCImpl.credentialRepositoryProvider.get(), singletonCImpl.provideBalanceCacheProvider.get());

          case 12: // com.rainy.token.data.local.UsageCache
          return (T) NetworkModule_ProvideUsageCacheFactory.provideUsageCache(singletonCImpl.provideUsageCacheDataStoreProvider.get(), singletonCImpl.provideJsonProvider.get());

          case 13: // @javax.inject.Named("dataStore.usageCache") androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
          return (T) StorageModule_ProvideUsageCacheDataStoreFactory.provideUsageCacheDataStore(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 14: // com.rainy.token.data.repository.OpenCodeUsageRepository
          return (T) NetworkModule_ProvideOpenCodeUsageRepositoryFactory.provideOpenCodeUsageRepository(singletonCImpl.provideOkHttpClientProvider.get(), singletonCImpl.credentialRepositoryProvider.get());

          case 15: // com.rainy.token.data.repository.WebViewSessionSaver
          return (T) new WebViewSessionSaver(singletonCImpl.credentialRepositoryProvider.get());

          default: throw new AssertionError(id);
        }
      }
    }
  }
}
