package com.rainy.token.ui.webview;

import com.rainy.token.data.repository.WebViewSessionSaver;
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
public final class WebViewLoginViewModel_Factory implements Factory<WebViewLoginViewModel> {
  private final Provider<WebViewSessionSaver> sessionSaverProvider;

  private WebViewLoginViewModel_Factory(Provider<WebViewSessionSaver> sessionSaverProvider) {
    this.sessionSaverProvider = sessionSaverProvider;
  }

  @Override
  public WebViewLoginViewModel get() {
    return newInstance(sessionSaverProvider.get());
  }

  public static WebViewLoginViewModel_Factory create(
      Provider<WebViewSessionSaver> sessionSaverProvider) {
    return new WebViewLoginViewModel_Factory(sessionSaverProvider);
  }

  public static WebViewLoginViewModel newInstance(WebViewSessionSaver sessionSaver) {
    return new WebViewLoginViewModel(sessionSaver);
  }
}
