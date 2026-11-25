/*
kv4p HT (see http://kv4p.com)
Copyright (C) 2024 Vance Vagell

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.vagell.kv4pht.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.vagell.kv4pht.R;
import com.vagell.kv4pht.data.ChannelMemory;
import com.vagell.kv4pht.radio.RadioAudioService;
import com.vagell.kv4pht.radio.RadioServiceConnector;
import com.vagell.kv4pht.repeaterbook.RepeaterBookApiClient;
import com.vagell.kv4pht.repeaterbook.RepeaterBookTokenStore;

import org.json.JSONException;
import org.json.JSONTokener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class FindRepeatersActivity extends AppCompatActivity {
    private static final String TAG = "FindRepeatersActivity";
    private static final String TOKEN_SETUP_URL = "https://www.repeaterbook.com/user/api_apps.php";

    // Runs on RepeaterBook's API apps page after the user signs in. It finds the
    // kv4p HT app card and submits the "issue token" form automatically, then reads
    // the newly revealed token. RepeaterBook shows a token's value only once, so we
    // try the known token field first, then any matching input, then visible page text.
    // A matching active token means we can't read its value; that case is reported so
    // the activity can ask before rotating it.
    private static final String TOKEN_SETUP_JS =
            "(function(){try{"
                    + "if(location.protocol!=='https:'||(location.hostname!=='www.repeaterbook.com'&&location.hostname!=='repeaterbook.com')){return 'ignore';}"
                    + "if(location.pathname.indexOf('/user/api_apps.php')===-1){return 'ignore';}"
                    + "var input=document.getElementById('rbv2UserAppBoundTokenField');"
                    + "var token=input&&input.value?input.value.trim():'';"
                    + "if(!/^rbuapp_[0-9a-fA-F]{64}$/.test(token)){"
                    + "var inputs=document.querySelectorAll('input,textarea');"
                    + "for(var k=0;k<inputs.length;k++){var v=inputs[k].value?inputs[k].value.trim():'';"
                    + "if(/^rbuapp_[0-9a-fA-F]{64}$/.test(v)){token=v;break;}}}"
                    + "if(!/^rbuapp_[0-9a-fA-F]{64}$/.test(token)){"
                    + "var m=(document.body.innerText||'').match(/rbuapp_[0-9a-fA-F]{64}/);token=m?m[0]:'';}"
                    + "if(token){try{sessionStorage.removeItem('kv4pTokenRequested');}catch(e){}"
                    + "try{if((window.name||'').indexOf('kv4pTokenRequested=')===0){window.name='';}}catch(e){}"
                    + "return 'token:'+token;}"
                    + "var cards=document.querySelectorAll('#rb-app-list article');var card=null;"
                    + "for(var i=0;i<cards.length;i++){"
                    + "if((cards[i].getAttribute('data-app-name')||'').toLowerCase()==='kv4p ht'){card=cards[i];break;}}"
                    + "if(!card){return (document.body.innerText||'').indexOf('Get a token for your app')===-1?'ignore':'noapp';}"
                    + "var forms=card.querySelectorAll('form');var issue=null;var rotate=null;"
                    + "for(var j=0;j<forms.length;j++){var a=forms[j].querySelector('input[name=action]');"
                    + "if(a&&a.value==='issue_user_app_token'){issue=forms[j];}"
                    + "if(a&&a.value==='rotate_user_app_token'){rotate=forms[j];}}"
                    + "if(issue){var requested=0;"
                    + "try{requested=Number(sessionStorage.getItem('kv4pTokenRequested')||0);}catch(e){"
                    + "try{if((window.name||'').indexOf('kv4pTokenRequested=')===0){"
                    + "requested=Number(window.name.substring(19))||0;}}catch(e2){}}"
                    + "if(requested&&Date.now()-requested<30000){return 'pending';}"
                    + "var stamp=String(Date.now());"
                    + "try{sessionStorage.setItem('kv4pTokenRequested',stamp);}catch(e){"
                    + "try{window.name='kv4pTokenRequested='+stamp;}catch(e2){}}"
                    + "issue.submit();return 'submitted';}"
                    + "if(rotate){try{if(sessionStorage.getItem('kv4pTokenRotateOffered')==='1'){return 'existing';}"
                    + "sessionStorage.setItem('kv4pTokenRotateOffered','1');}catch(e){}return 'existing';}"
                    + "return (document.body.innerText||'').indexOf('Get a token for your app')===-1?'ignore':'noform';"
                    + "}catch(e){return 'error';}})();";

    private static final String TOKEN_ROTATE_JS =
            "(function(){try{var cards=document.querySelectorAll('#rb-app-list article');var card=null;"
                    + "for(var i=0;i<cards.length;i++){"
                    + "if((cards[i].getAttribute('data-app-name')||'').toLowerCase()==='kv4p ht'){card=cards[i];break;}}"
                    + "if(!card){return 'missing';}"
                    + "var forms=card.querySelectorAll('form');"
                    + "for(var j=0;j<forms.length;j++){var a=forms[j].querySelector('input[name=action]');"
                    + "if(a&&a.value==='rotate_user_app_token'){forms[j].submit();return 'submitted';}}"
                    + "return 'missing';}catch(e){return 'error';}})();";

    private final ThreadPoolExecutor threadPoolExecutor =
            new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    private final RepeaterBookApiClient apiClient = new RepeaterBookApiClient();

    private Snackbar errorSnackbar = null;
    private Snackbar attributionSnackbar = null;
    private List<RepeaterBookApiClient.RepeaterInfo> nearbyRepeaters = null;
    private String locality = null;
    private String countryName = null;
    private String adminArea = null;
    private MainViewModel viewModel;
    private RadioServiceConnector serviceConnector;
    private RadioAudioService radioAudioService;
    private WebView repeaterBookWebView;
    private ProgressBar findRepeatersProgress;
    private double latitude = 0;
    private double longitude = 0;

    private String apiToken = null;
    private boolean tokenLoaded = false;
    private boolean locationDetailsReady = false;
    private boolean tokenSetupVisible = false;
    private boolean existingTokenPromptShown = false;
    private boolean pendingFetch = false;
    private boolean isFetching = false;
    private boolean fetchCompleted = false;
    private boolean destroyed = false;

    private static final int REQUEST_LOCATION_PERMISSION_CODE = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        serviceConnector = new RadioServiceConnector(this);
        viewModel = new ViewModelProvider(this).get(MainViewModel.class);
        setContentView(R.layout.activity_find_repeaters);

        repeaterBookWebView = findViewById(R.id.repeaterBookWebView);
        findRepeatersProgress = findViewById(R.id.findRepeatersProgress);

        configureWebView();
        populateMemoryGroups();
        loadSavedToken();
        requestLocationPermission();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = repeaterBookWebView.getSettings();
        settings.setJavaScriptEnabled(true); // Required by RepeaterBook's sign-in page.
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        repeaterBookWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("about".equals(uri.getScheme()) || isRepeaterBookHost(uri.getHost())) {
                    return false;
                }
                // Keep the RepeaterBook login session inside our WebView, but send
                // unrelated links (for example donation or app-store links) out.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception e) {
                    Log.d(TAG, "Unable to open external link.", e);
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                processTokenPage(url);
            }
        });
    }

    private static boolean isRepeaterBookHost(String host) {
        return host != null
                && (host.equals("repeaterbook.com") || host.endsWith(".repeaterbook.com"));
    }

    private void loadSavedToken() {
        threadPoolExecutor.execute(() -> {
            String token = RepeaterBookTokenStore.loadToken(viewModel.getAppDb());
            if (!RepeaterBookApiClient.looksLikeUserAppToken(token)) {
                token = null;
            }
            final String loadedToken = token;
            runOnUiThread(() -> {
                if (destroyed) {
                    return;
                }
                if (apiToken == null) {
                    apiToken = loadedToken;
                }
                tokenLoaded = true;
                continueWhenReady();
            });
        });
    }

    private void requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.ACCESS_FINE_LOCATION)) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.find_repeaters_permission_title)
                        .setMessage(R.string.find_repeaters_permission_message)
                        .setPositiveButton(R.string.ok_display, (dialog, which) ->
                                ActivityCompat.requestPermissions(
                                        FindRepeatersActivity.this,
                                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                                        REQUEST_LOCATION_PERMISSION_CODE))
                        .create()
                        .show();
            } else {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        REQUEST_LOCATION_PERMISSION_CODE);
            }
            return;
        }
        getGpsLocation();
    }

    private void getGpsLocation() {
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(getBaseContext())
                != ConnectionResult.SUCCESS) {
            Log.d(TAG, "Unable to get nearby repeaters because Google Play Services is missing.");
            showFatalErrorSnackbar(getString(R.string.find_repeaters_play_services_missing));
            return;
        }

        FusedLocationProviderClient fusedLocationClient =
                LocationServices.getFusedLocationProviderClient(this);
        CancellationTokenSource cancellationTokenSource = new CancellationTokenSource();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestLocationPermission();
            return;
        }

        fusedLocationClient.getCurrentLocation(
                        LocationRequest.PRIORITY_HIGH_ACCURACY,
                        cancellationTokenSource.getToken())
                .addOnSuccessListener(location -> {
                    if (location == null) {
                        showFatalErrorSnackbar(getString(R.string.find_repeaters_location_failed));
                        return;
                    }
                    latitude = location.getLatitude();
                    longitude = location.getLongitude();
                    resolveLocationDetails(latitude, longitude);
                })
                .addOnFailureListener(e ->
                        showFatalErrorSnackbar(getString(R.string.find_repeaters_location_failed)));
    }

    private void resolveLocationDetails(double latitude, double longitude) {
        threadPoolExecutor.execute(() -> {
            String country = null;
            String admin = null;
            String city = null;
            try {
                Geocoder geocoder = new Geocoder(this, Locale.US);
                List<Address> addresses = geocoder.getFromLocation(latitude, longitude, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    Address address = addresses.get(0);
                    country = address.getCountryName();
                    admin = address.getAdminArea();
                    city = address.getLocality();
                    if (city == null || city.trim().isEmpty()) {
                        city = address.getSubAdminArea();
                    }
                }
            } catch (IOException e) {
                Log.d(TAG, "Exception while reverse geocoding the user's location.", e);
            }

            final String resolvedCountry = country;
            final String resolvedAdmin = admin;
            final String resolvedCity = city;
            runOnUiThread(() -> {
                if (destroyed) {
                    return;
                }
                if (resolvedCountry == null || resolvedCountry.trim().isEmpty()) {
                    showFatalErrorSnackbar(getString(R.string.find_repeaters_location_details_failed));
                    return;
                }
                countryName = resolvedCountry;
                adminArea = resolvedAdmin;
                locality = resolvedCity;
                locationDetailsReady = true;
                continueWhenReady();
            });
        });
    }

    private void continueWhenReady() {
        if (!tokenLoaded || !locationDetailsReady) {
            return;
        }
        if (apiToken == null || apiToken.isEmpty()) {
            showTokenSetup();
            return;
        }
        tryStartFetch();
    }

    private void showTokenSetup() {
        if (tokenSetupVisible) {
            return;
        }
        tokenSetupVisible = true;
        existingTokenPromptShown = false;
        nearbyRepeaters = null;
        fetchCompleted = false;
        pendingFetch = false;

        findViewById(R.id.findRepeatersGroupInputHolder).setVisibility(View.GONE);
        findViewById(R.id.findRepeatersSaveButton).setVisibility(View.GONE);
        findViewById(R.id.findRepeatersManualTokenHolder).setVisibility(View.GONE);
        findViewById(R.id.findRepeatersSaveTokenButton).setVisibility(View.GONE);
        findRepeatersProgress.setVisibility(View.GONE);
        repeaterBookWebView.setVisibility(View.VISIBLE);
        findViewById(R.id.findRepeatersManualTokenButton).setVisibility(View.VISIBLE);

        setStatus(getString(R.string.find_repeaters_instructions));
        repeaterBookWebView.loadUrl(TOKEN_SETUP_URL);
    }

    private void hideTokenSetup() {
        tokenSetupVisible = false;
        repeaterBookWebView.setVisibility(View.GONE);
        findViewById(R.id.findRepeatersManualTokenButton).setVisibility(View.GONE);
        findViewById(R.id.findRepeatersManualTokenHolder).setVisibility(View.GONE);
        findViewById(R.id.findRepeatersSaveTokenButton).setVisibility(View.GONE);
        TextInputEditText tokenField = findViewById(R.id.findRepeatersTokenEditText);
        tokenField.setText("");
        repeaterBookWebView.stopLoading();
        repeaterBookWebView.clearHistory();
        repeaterBookWebView.clearFormData();
        repeaterBookWebView.clearCache(true);
        // Drop the token page (and its visible token) from the WebView.
        repeaterBookWebView.loadUrl("about:blank");
        // Don't leave a logged-in RepeaterBook session behind after setup.
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.removeAllCookies(null);
        cookieManager.flush();
    }

    private void processTokenPage(String url) {
        if (!tokenSetupVisible || url == null || !url.contains("/user/api_apps.php")) {
            return;
        }
        repeaterBookWebView.evaluateJavascript(TOKEN_SETUP_JS, rawResult -> {
            String result = jsString(rawResult);
            if (result.startsWith("token:")) {
                saveTokenAndFetch(result.substring("token:".length()));
            } else if ("submitted".equals(result) || "pending".equals(result)) {
                setStatus(getString(R.string.find_repeaters_creating_token));
            } else if ("existing".equals(result)) {
                promptReplaceExistingToken();
            } else if ("noapp".equals(result)) {
                showManualTokenEntry(getString(R.string.find_repeaters_app_not_available));
            } else if ("noform".equals(result)) {
                showManualTokenEntry(getString(R.string.find_repeaters_manual_token_instructions));
            }
        });
    }

    private void promptReplaceExistingToken() {
        if (existingTokenPromptShown) {
            return;
        }
        existingTokenPromptShown = true;
        new AlertDialog.Builder(this)
                .setTitle(R.string.find_repeaters_existing_token_title)
                .setMessage(R.string.find_repeaters_existing_token_message)
                .setPositiveButton(R.string.replace_token_display, (dialog, which) -> rotateExistingToken())
                .setNegativeButton(R.string.enter_token_manually_display, (dialog, which) ->
                        showManualTokenEntry(getString(R.string.find_repeaters_manual_token_instructions)))
                .show();
    }

    private void rotateExistingToken() {
        setStatus(getString(R.string.find_repeaters_creating_token));
        repeaterBookWebView.evaluateJavascript(TOKEN_ROTATE_JS, rawResult -> {
            String result = jsString(rawResult);
            if (!"submitted".equals(result)) {
                showManualTokenEntry(getString(R.string.find_repeaters_manual_token_instructions));
            }
        });
    }

    private void showManualTokenEntry(String message) {
        tokenSetupVisible = true;
        findViewById(R.id.findRepeatersGroupInputHolder).setVisibility(View.GONE);
        findViewById(R.id.findRepeatersSaveButton).setVisibility(View.GONE);
        findViewById(R.id.findRepeatersManualTokenHolder).setVisibility(View.VISIBLE);
        findViewById(R.id.findRepeatersSaveTokenButton).setVisibility(View.VISIBLE);
        findViewById(R.id.findRepeatersManualTokenButton).setVisibility(View.GONE);
        findRepeatersProgress.setVisibility(View.GONE);
        repeaterBookWebView.setVisibility(View.VISIBLE);
        setStatus(message);
        TextInputEditText tokenField = findViewById(R.id.findRepeatersTokenEditText);
        tokenField.requestFocus();
    }

    private void saveTokenAndFetch(String token) {
        String trimmed = token == null ? "" : token.trim();
        if (!RepeaterBookApiClient.looksLikeUserAppToken(trimmed)) {
            showManualTokenEntry(getString(R.string.find_repeaters_token_invalid));
            return;
        }
        threadPoolExecutor.execute(() -> {
            RepeaterBookTokenStore.saveToken(viewModel.getAppDb(), trimmed);
            runOnUiThread(() -> {
                if (destroyed) {
                    return;
                }
                apiToken = trimmed;
                tokenLoaded = true;
                fetchCompleted = false;
                nearbyRepeaters = null;
                hideTokenSetup();
                setStatus(getString(R.string.find_repeaters_token_saved));
                tryStartFetch();
            });
        });
    }

    private void tryStartFetch() {
        if (isFetching || fetchCompleted || !tokenLoaded || !locationDetailsReady) {
            return;
        }
        if (apiToken == null || apiToken.isEmpty()) {
            showTokenSetup();
            return;
        }
        if (radioAudioService == null) {
            pendingFetch = true;
            return;
        }

        float minFreq = radioAudioService.getMinRadioFreq();
        float maxFreq = radioAudioService.getMaxRadioFreq();
        if (minFreq <= 0 || maxFreq <= 0) {
            showFatalErrorSnackbar(getString(R.string.find_repeaters_radio_not_ready));
            return;
        }

        pendingFetch = false;
        isFetching = true;
        showFetchingUi();

        final String token = apiToken;
        final String country = countryName;
        final String state = adminArea;
        threadPoolExecutor.execute(() -> {
            try {
                List<RepeaterBookApiClient.RepeaterInfo> repeaters =
                        apiClient.fetchNearbyRepeaters(token, latitude, longitude, country, state,
                                minFreq, maxFreq);
                runOnUiThread(() -> onFetchSuccess(repeaters));
            } catch (RepeaterBookApiClient.ApiException e) {
                Log.d(TAG, "RepeaterBook request failed: " + e.getCode());
                runOnUiThread(() -> onFetchError(e));
            }
        });
    }

    private void showFetchingUi() {
        setStatus(getString(R.string.find_repeaters_fetching));
        findRepeatersProgress.setVisibility(View.VISIBLE);
        repeaterBookWebView.setVisibility(View.GONE);
        findViewById(R.id.findRepeatersManualTokenHolder).setVisibility(View.GONE);
        findViewById(R.id.findRepeatersManualTokenButton).setVisibility(View.GONE);
        findViewById(R.id.findRepeatersSaveTokenButton).setVisibility(View.GONE);
        findViewById(R.id.findRepeatersGroupInputHolder).setVisibility(View.GONE);
        findViewById(R.id.findRepeatersSaveButton).setVisibility(View.GONE);
    }

    private void onFetchSuccess(List<RepeaterBookApiClient.RepeaterInfo> repeaters) {
        if (destroyed) {
            return;
        }
        isFetching = false;
        fetchCompleted = true;
        pendingFetch = false;
        nearbyRepeaters = repeaters;
        findRepeatersProgress.setVisibility(View.GONE);

        if (repeaters.isEmpty()) {
            setStatus(getString(R.string.find_repeaters_no_repeaters));
            showAttributionSnackbar();
            return;
        }
        promptUserForMemoryGroup();
        showAttributionSnackbar();
    }

    private void onFetchError(RepeaterBookApiClient.ApiException error) {
        if (destroyed) {
            return;
        }
        isFetching = false;
        pendingFetch = false;
        findRepeatersProgress.setVisibility(View.GONE);

        String code = error.getCode();
        if (code != null && code.startsWith("auth_")) {
            String message = getString(R.string.find_repeaters_auth_error);
            setStatus(message);
            showErrorSnackbar(message,
                    getString(R.string.update_token_display), this::clearTokenAndShowSetup);
        } else if ("rate_limited".equals(code)) {
            String message = getString(R.string.find_repeaters_rate_limited);
            setStatus(message);
            showFatalErrorSnackbar(message);
        } else if ("network".equals(code)) {
            String message = getString(R.string.find_repeaters_network_error);
            setStatus(message);
            showErrorSnackbar(message,
                    getString(R.string.retry_display), this::tryStartFetch);
        } else {
            String message = getString(R.string.find_repeaters_api_error);
            setStatus(message);
            showFatalErrorSnackbar(message);
        }
    }

    private void clearTokenAndShowSetup() {
        threadPoolExecutor.execute(() -> {
            RepeaterBookTokenStore.clearToken(viewModel.getAppDb());
            runOnUiThread(() -> {
                if (destroyed) {
                    return;
                }
                apiToken = null;
                fetchCompleted = false;
                nearbyRepeaters = null;
                showTokenSetup();
            });
        });
    }

    private void showAttributionSnackbar() {
        attributionSnackbar = Snackbar.make(findViewById(R.id.firmwareTopLevelView),
                getString(R.string.repeaterbook_attribution), Snackbar.LENGTH_SHORT);
        TextView snackbarTextView = attributionSnackbar.getView()
                .findViewById(com.google.android.material.R.id.snackbar_text);
        snackbarTextView.setTextSize(20);
        attributionSnackbar.show();
    }

    private void setStatus(String message) {
        TextView status = findViewById(R.id.findRepeatersStatusText);
        status.setText(message);
    }

    private static String jsString(String rawResult) {
        if (rawResult == null) {
            return "";
        }
        try {
            Object value = new JSONTokener(rawResult).nextValue();
            return value instanceof String ? (String) value : String.valueOf(value);
        } catch (JSONException e) {
            return rawResult.replaceAll("^\"|\"$", "");
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        serviceConnector.bind(service -> {
            radioAudioService = service;
            if (pendingFetch) {
                tryStartFetch();
            }
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (errorSnackbar != null) {
            errorSnackbar.dismiss();
        }
        if (attributionSnackbar != null) {
            attributionSnackbar.dismiss();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
        threadPoolExecutor.shutdownNow();
        serviceConnector.unbind();
        if (repeaterBookWebView != null) {
            if (tokenSetupVisible) {
                CookieManager cookieManager = CookieManager.getInstance();
                cookieManager.removeAllCookies(null);
                cookieManager.flush();
            }
            repeaterBookWebView.loadUrl("about:blank");
            repeaterBookWebView.destroy();
        }
    }

    /**
     * Displays a fatal error snackbar with a "Close" action that exits the activity.
     */
    @SuppressWarnings("javasecurity:S6384") // This sets a fixed result code and never forwards an Intent.
    private void showFatalErrorSnackbar(String msg) {
        showErrorSnackbar(msg, getString(R.string.close_display), () -> {
            setResult(Activity.RESULT_CANCELED);
            finish();
        });
    }

    @SuppressWarnings("javasecurity:S6384") // This sets a fixed result code and never forwards an Intent.
    private void showErrorSnackbar(String msg, String actionText, Runnable action) {
        if (errorSnackbar != null) {
            errorSnackbar.dismiss();
        }
        errorSnackbar = Snackbar.make(this, findViewById(R.id.firmwareTopLevelView), msg,
                        BaseTransientBottomBar.LENGTH_INDEFINITE)
                .setBackgroundTint(Color.rgb(140, 20, 0))
                .setActionTextColor(Color.WHITE)
                .setTextColor(Color.WHITE);
        errorSnackbar.setAction(actionText, view -> {
            errorSnackbar.dismiss();
            action.run();
        });

        TextView snackbarActionTextView = errorSnackbar.getView()
                .findViewById(com.google.android.material.R.id.snackbar_action);
        snackbarActionTextView.setTextSize(20);
        TextView snackbarTextView = errorSnackbar.getView()
                .findViewById(com.google.android.material.R.id.snackbar_text);
        snackbarTextView.setTextSize(20);

        errorSnackbar.show();
    }

    @SuppressWarnings({"java:S1172", "javasecurity:S6384"}) // Called from XML; this only sets a fixed result code.
    public void findRepeatersCancelButtonClicked(View view) {
        setResult(Activity.RESULT_CANCELED);
        finish();
    }

    @SuppressWarnings("java:S1172") // Called from the layout's android:onClick attribute.
    public void findRepeatersManualTokenButtonClicked(View view) {
        showManualTokenEntry(getString(R.string.find_repeaters_manual_token_instructions));
    }

    @SuppressWarnings("java:S1172") // Called from the layout's android:onClick attribute.
    public void findRepeatersSaveTokenButtonClicked(View view) {
        TextInputEditText tokenField = findViewById(R.id.findRepeatersTokenEditText);
        String token = tokenField.getText() == null ? "" : tokenField.getText().toString();
        saveTokenAndFetch(token);
    }

    // Kept as an Activity helper because it coordinates multiple views and workflow stages.
    @SuppressWarnings("java:S3398")
    private void promptUserForMemoryGroup() {
        repeaterBookWebView.setVisibility(View.GONE);
        findViewById(R.id.findRepeatersManualTokenHolder).setVisibility(View.GONE);
        findViewById(R.id.findRepeatersManualTokenButton).setVisibility(View.GONE);
        findViewById(R.id.findRepeatersSaveTokenButton).setVisibility(View.GONE);
        findRepeatersProgress.setVisibility(View.GONE);

        String nearbyRepeatersFound = getString(R.string.nearby_repeaters_found)
                + nearbyRepeaters.size() + "\n\n"
                + getString(R.string.save_repeaters_group_instructions);
        setStatus(nearbyRepeatersFound);

        findViewById(R.id.findRepeatersGroupInputHolder).setVisibility(View.VISIBLE);
        if (locality != null && !locality.trim().isEmpty()) {
            AutoCompleteTextView autoCompleteTextView =
                    findViewById(R.id.findRepeatersGroupTextInputEditText);
            autoCompleteTextView.setText(locality);
        }
        findViewById(R.id.findRepeatersSaveButton).setVisibility(View.VISIBLE);
    }

    private void populateMemoryGroups() {
        final Activity activity = this;
        threadPoolExecutor.execute(() -> viewModel.loadDataAsync(() -> {
            List<String> memoryGroups = viewModel.getAppDb().channelMemoryDao().getGroups();
            memoryGroups.removeIf(name -> name == null || name.trim().isEmpty());
            activity.runOnUiThread(() -> {
                AutoCompleteTextView editMemoryGroupTextView =
                        findViewById(R.id.findRepeatersGroupTextInputEditText);
                ArrayAdapter<String> arrayAdapter =
                        new ArrayAdapter<>(activity, R.layout.dropdown_item, memoryGroups);
                editMemoryGroupTextView.setAdapter(arrayAdapter);
            });
        }));
    }

    @SuppressWarnings({"java:S1172", "javasecurity:S6384"}) // Called from XML; this only sets a fixed result code.
    public void findRepeatersSaveButtonClicked(View view) {
        String group = ((AutoCompleteTextView) findViewById(
                R.id.findRepeatersGroupTextInputEditText)).getText().toString().trim();
        final List<ChannelMemory> memoriesToAdd = new ArrayList<>();

        for (RepeaterBookApiClient.RepeaterInfo repeater : nearbyRepeaters) {
            ChannelMemory memory = new ChannelMemory();
            memory.name = repeater.call + " • " + repeater.location;
            memory.group = group;
            if (radioAudioService != null) {
                memory.frequency = radioAudioService.makeSafeHamFreq(String.valueOf(repeater.freq));
            } else {
                Log.e(TAG, "radioAudioService is null. Cannot set frequency.");
                continue;
            }
            if (repeater.offset < 0) {
                memory.offset = ChannelMemory.OFFSET_DOWN;
            } else if (repeater.offset > 0) {
                memory.offset = ChannelMemory.OFFSET_UP;
            } else {
                memory.offset = ChannelMemory.OFFSET_NONE;
            }
            memory.txTone = repeater.tone;
            memory.rxTone = getString(R.string.none_display);
            memory.offsetKhz = Math.abs((int) Math.round(repeater.offset * 1000));
            memory.skipDuringScan = false;

            memoriesToAdd.add(memory);
        }

        threadPoolExecutor.execute(() -> {
            for (ChannelMemory memory : memoriesToAdd) {
                viewModel.getAppDb().channelMemoryDao().insertAll(memory);
            }
            setResult(Activity.RESULT_OK);
            finish();
        });
    }
}