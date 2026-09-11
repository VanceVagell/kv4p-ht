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
import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
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
import com.google.android.material.snackbar.Snackbar;
import com.vagell.kv4pht.R;
import com.vagell.kv4pht.data.ChannelMemory;
import com.vagell.kv4pht.radio.RadioAudioService;
import com.vagell.kv4pht.radio.RadioServiceConnector;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class FindRepeatersActivity extends AppCompatActivity {
    private static final String TAG = "FindRepeatersActivity";
    private static final String URL_LONGITUDE_PARAMETER = "&long=";
    private final ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    private Snackbar errorSnackbar = null;
    private long downloadId = 0; // So we can tell when the download is done
    private List<RepeaterInfo> nearbyRepeaters = null;
    private String locality = null;
    private MainViewModel viewModel;
    private RadioServiceConnector serviceConnector;
    private RadioAudioService radioAudioService;
    private double latitude = 0;
    private double longitude = 0;
    private String[] downloadUrls = null;
    private int downloadUrlIndex = 0;
    private WebView downloadWebView;
    private WebView webViewForDownloads;
    private boolean isManualDownloadAttempt = false;

    // Android permission stuff
    private static final int REQUEST_LOCATION_PERMISSION_CODE = 1;
    private static final int REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_CODE = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        serviceConnector = new RadioServiceConnector(this);
        viewModel = new ViewModelProvider(this).get(MainViewModel.class);
        setContentView(R.layout.activity_find_repeaters);

        // Listen for file downloads so we can detect when CSV download is done.
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        registerReceiver(onDownloadComplete, filter, Context.RECEIVER_EXPORTED);

        populateMemoryGroups();
        requestPermissions();
    }

    private void getGpsLocation() {
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(getBaseContext()) != ConnectionResult.SUCCESS) {
            Log.d(TAG, "Unable to get nearby repeaters because Android device is missing Google Play Services, needed to get GPS location.");
            showErrorSnackbar("Google Play Services is missing, it's needed for GPS location.");
            return;
        }

        FusedLocationProviderClient fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        CancellationTokenSource cancellationTokenSource = new CancellationTokenSource();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions();
            return;
        }

        fusedLocationClient.getCurrentLocation(LocationRequest.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.getToken())
                .addOnSuccessListener(location -> {
                    if (location == null) {
                        showErrorSnackbar("Failed to find your GPS location (it came back null).");
                        return;
                    }
                    double currentLatitude = location.getLatitude();
                    double currentLongitude = location.getLongitude();
                    findLocalityAsync(currentLatitude, currentLongitude);
                    latitude = currentLatitude;
                    longitude = currentLongitude;
                    startCSVDownload();
                }).addOnFailureListener(e -> {
                    showErrorSnackbar("Failed to find your GPS location.");
                });
    }

    protected void requestPermissions() {
        // Location permission...
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.ACCESS_FINE_LOCATION)) {

                new AlertDialog.Builder(this)
                        .setTitle("Permission needed")
                        .setMessage("This app needs the fine location permission")
                        .setPositiveButton("OK", (dialog, which) -> ActivityCompat.requestPermissions(
                                FindRepeatersActivity.this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                                REQUEST_LOCATION_PERMISSION_CODE))
                        .create()
                        .show();

            } else {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        REQUEST_LOCATION_PERMISSION_CODE);
            }
        } else {
            // Start the location-dependent download after permission is confirmed.
            getGpsLocation();
        }

        // External storage permission...
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)) {

                new AlertDialog.Builder(this)
                        .setTitle("Permission needed")
                        .setMessage("This app needs to write to external storage to find nearby repeaters")
                        .setPositiveButton("OK", (dialog, which) -> ActivityCompat.requestPermissions(
                                FindRepeatersActivity.this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                                REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_CODE))
                        .create()
                        .show();

            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_LOCATION_PERMISSION_CODE) {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted.
                    getGpsLocation();
                } else {
                    // Permission denied
                    Log.d(TAG, "Warning: Need fine location permission to find nearby repeaters, but user denied it.");
                    showErrorSnackbar("Can't get your GPS location because the permission was denied.");
                }
        } else if (requestCode == REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_CODE) {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted.
                } else {
                    // Permission denied
                    Log.d(TAG, "Warning: Need to write to external storage to find nearby repeaters, but user denied it.");
                    showErrorSnackbar("Can't find nearby repeaters because storage permission was denied.");
                    finishActivity(Activity.RESULT_CANCELED);
                }
        }
    }

    private String[] getDownloadRepeatersUrls() {
        if (radioAudioService.getRadioType() == RadioAudioService.RadioModuleType.VHF) {
            String usVhfURL = "https://www.repeaterbook.com/repeaters/downloads/csv/index.php?func=prox&features%5B0%5D=FM&lat=" +
                    latitude + URL_LONGITUDE_PARAMETER + longitude + "&distance=25&Dunit=m&band=4&call=&use=OPEN&status_id=1";
            String internationalVhfURL = "https://www.repeaterbook.com/row_repeaters/downloads/csv/index.php?func=prox2&city=&lat=" +
                    latitude + URL_LONGITUDE_PARAMETER + longitude + "&distance=40&Dunit=k&band=4&freq=0&feature=0&call=&mode=1&net=0&status_id=%&use=&lat=" +
                    latitude + URL_LONGITUDE_PARAMETER + longitude; // RepeaterBook requires latitude/longitude twice for international results.
            return new String[]{usVhfURL, internationalVhfURL};
        } else { // UHF
            String usUhfURL = "https://www.repeaterbook.com/repeaters/downloads/csv/index.php?func=prox&features%5B0%5D=FM&lat=" +
                    latitude + URL_LONGITUDE_PARAMETER + longitude + "&distance=25&Dunit=m&band=16&band2=&call=&use=OPEN&status_id=1";
            String internationalUhfURL = "https://www.repeaterbook.com/row_repeaters/downloads/csv/index.php?func=prox2&city=&lat=" +
                    latitude + URL_LONGITUDE_PARAMETER + longitude + "&distance=40&Dunit=k&band=16&freq=0&feature=0&call=&mode=1&net=0&status_id=%&use=&lat=" +
                    latitude + URL_LONGITUDE_PARAMETER + longitude; // RepeaterBook requires latitude/longitude twice for international results.
            return new String[]{usUhfURL, internationalUhfURL};
        }
    }

    private void attemptNextDownload() {
        if (downloadUrls == null) {
            downloadUrls = getDownloadRepeatersUrls();
        }
        if (downloadUrlIndex < downloadUrls.length) {
            String url = downloadUrls[downloadUrlIndex];
            Log.d(TAG, "Attempting download from URL #" + downloadUrlIndex + ": " + url);
            webViewForDownloads.loadUrl(url);
        } else if (isManualDownloadAttempt) {
            showErrorSnackbar("No nearby repeaters found.");
        } else {
            Log.d(TAG, "Silent download attempt failed, user may not be logged in yet.");
        }
    }

    private DownloadListener createDownloadListener() {
        return (url, userAgent, contentDisposition, mimeType, contentLength) -> {
                Log.d(TAG, "RepeaterBook CSV download started.");

                // Fetch cookies to maintain session
                String cookies = CookieManager.getInstance().getCookie(url);

                // Now download the file using Android's DownloadManager
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.addRequestHeader("Cookie", cookies);
                request.addRequestHeader("User-Agent", userAgent);
                request.setMimeType(mimeType);
                request.setDescription("Downloading repeater CSV...");
                String filename = URLUtil.guessFileName(url, contentDisposition, mimeType);
                request.setTitle(filename);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);

                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                downloadId = dm.enqueue(request);
        };
    }

    private void startCSVDownload() {
        downloadUrls = getDownloadRepeatersUrls();
        downloadUrlIndex = 0;

        // Initialize the WebView
        WebView webView = findViewById(R.id.repeaterBookWebView);

        // For silent downloads
        downloadWebView = new WebView(this);
        downloadWebView.setDownloadListener(createDownloadListener());

        // Enable JavaScript if your webpage needs it
        webView.getSettings().setJavaScriptEnabled(true);

        // Set a WebViewClient to handle page loading inside the app
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Log.d(TAG, "Navigating to: " + url);
                view.loadUrl(url); // Continue loading inside the WebView
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (nearbyRepeaters == null) {
                    Log.d(TAG, "Page finished loading: " + url + ". Attempting silent download.");
                    isManualDownloadAttempt = false;
                    webViewForDownloads = downloadWebView;
                    downloadUrlIndex = 0;
                    attemptNextDownload();
                }
            }
        });

        webView.setDownloadListener(createDownloadListener());

        // Load your initial URL
        webView.loadUrl("https://www.repeaterbook.com");
    }

    /** Alternative method for people who's webview doesn't let us track when login is complete. */
    @SuppressWarnings("java:S1172") // Called from the layout's android:onClick attribute.
    public void findRepeatersDownloadButtonClicked(View view) {
        isManualDownloadAttempt = true;
        webViewForDownloads = findViewById(R.id.repeaterBookWebView);
        downloadUrlIndex = 0;
        attemptNextDownload();
    }

    @Override
    protected void onStart() {
        super.onStart();
        serviceConnector.bind(rs -> this.radioAudioService = rs);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (errorSnackbar != null) {
            errorSnackbar.dismiss();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(onDownloadComplete);
        threadPoolExecutor.shutdownNow();
        serviceConnector.unbind();
    }

    /**
     * Displays an error snackbar with the given message, and a "Close" action that exits the activity.
     */
    @SuppressWarnings("javasecurity:S6384") // This sets a fixed result code and never forwards an Intent.
    private void showErrorSnackbar(String msg) {
        errorSnackbar = Snackbar.make(this, findViewById(R.id.firmwareTopLevelView), msg, Snackbar.LENGTH_INDEFINITE)
                .setBackgroundTint(Color.rgb(140, 20, 0)).setActionTextColor(Color.WHITE).setTextColor(Color.WHITE);
        errorSnackbar.setAction("Close", view -> {
            errorSnackbar.dismiss();
            setResult(Activity.RESULT_CANCELED);
            finish();
        });

        // Make the text of the snackbar larger.
        TextView snackbarActionTextView = (TextView) errorSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_action);
        snackbarActionTextView.setTextSize(20);
        TextView snackbarTextView = (TextView) errorSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        snackbarTextView.setTextSize(20);

        errorSnackbar.show();
    }

    @SuppressWarnings({"java:S1172", "javasecurity:S6384"}) // Called from XML; this only sets a fixed result code.
    public void findRepeatersCancelButtonClicked(View view) {
        setResult(Activity.RESULT_CANCELED);
        finish();
    }

    private BroadcastReceiver onDownloadComplete = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);

            if (id == downloadId) {
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                Uri uri = dm.getUriForDownloadedFile(downloadId);
                Log.d(TAG, "Download complete for URL #" + downloadUrlIndex);
                try {
                    String csvData = readDownloadedCsvFile(uri);
                    Log.d(TAG, "CSV Contents:\n" + csvData);
                    nearbyRepeaters = parseRepeaterList(csvData);
                    Log.d(TAG, "Num repeaters found: " + nearbyRepeaters.size());
                    if (nearbyRepeaters == null || nearbyRepeaters.isEmpty()) {
                        downloadUrlIndex++;
                        attemptNextDownload();
                    } else {
                        // Ask the user what memory group to dump these repeaters in.
                        promptUserForMemoryGroup();
                    }
                } catch (Exception e) {
                    Log.d(TAG, "Error while trying to parse repeater CSV file.", e);
                    downloadUrlIndex++;
                    attemptNextDownload();
                }
            }
        }
    };

    private String readDownloadedCsvFile(Uri fileUri) throws IOException {
        if (fileUri == null) {
            throw new IOException("Downloaded CSV file URI is null.");
        }
        InputStream inputStream = getContentResolver().openInputStream(fileUri);
        if (inputStream == null) {
            throw new IOException("Could not open input stream for downloaded CSV file.");
        }

        // Read file into a String
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    public class RepeaterInfo {
        public double freq;
        public double input;
        public double offset;
        public String tone;
        public String location;
        public String state;
        public String county;
        public String call;
        public String use;
        public double miles;
        public String bearing;
        public double degrees;

        @Override
        public String toString() {
            return String.format("%s @ %s (%.5f MHz)", call, location, freq);
        }
    }

    public List<RepeaterInfo> parseRepeaterList(String csvData) {
        List<RepeaterInfo> repeaters = new ArrayList<>();
        List<String> records = splitCsvRecords(csvData);
        if (records.isEmpty()) {
            return repeaters;
        }

        String header = records.get(0);
        boolean isUsFormat = header.startsWith("Freq,Input,Offset,Tone,Location");
        if (!isUsFormat && !header.startsWith("Output Freq,Input Freq,Offset,Uplink Tone")) {
            return repeaters; // Unknown format
        }

        for (int recordIndex = 1; recordIndex < records.size(); recordIndex++) {
            String csvRecord = records.get(recordIndex);
            if (csvRecord.trim().isEmpty()) {
                continue;
            }
            String[] cols = splitCSVLine(csvRecord);
            RepeaterInfo repeater = isUsFormat ? parseUsRepeater(cols) : parseInternationalRepeater(cols);
            if (repeater != null && isWithinRadioRange(repeater)) {
                repeaters.add(repeater);
            }
        }
        return repeaters;
    }

    private RepeaterInfo parseUsRepeater(String[] cols) {
        if (cols.length < 12) {
            return null;
        }
        RepeaterInfo repeater = parseCommonRepeaterFields(cols);
        repeater.location = cleanLocation(cols[4]);
        repeater.state = cols[5];
        repeater.county = cols[6];
        repeater.call = cols[7];
        repeater.use = cols[8];
        repeater.miles = tryParseDouble(cols[9]);
        repeater.bearing = cols[10];
        repeater.degrees = tryParseDouble(cols[11]);
        return repeater;
    }

    private RepeaterInfo parseInternationalRepeater(String[] cols) {
        if (cols.length < 11) {
            return null;
        }
        RepeaterInfo repeater = parseCommonRepeaterFields(cols);
        repeater.call = cols[5];
        repeater.location = cleanLocation(cols[6]);
        repeater.county = cols[7];
        repeater.state = cols[8];
        return repeater;
    }

    private RepeaterInfo parseCommonRepeaterFields(String[] cols) {
        RepeaterInfo repeater = new RepeaterInfo();
        repeater.freq = tryParseDouble(cols[0]);
        repeater.input = tryParseDouble(cols[1]);
        repeater.offset = tryParseDouble(cols[2]);
        repeater.tone = ToneHelper.normalizeTone(cols[3].trim());
        return repeater;
    }

    private String cleanLocation(String location) {
        return location.replace("\n", " ").replace("\r", "");
    }

    private boolean isWithinRadioRange(RepeaterInfo repeater) {
        return radioAudioService != null
                && repeater.freq >= radioAudioService.getMinRadioFreq()
                && repeater.freq <= radioAudioService.getMaxRadioFreq();
    }

    private List<String> splitCsvRecords(String csvData) {
        List<String> records = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder record = new StringBuilder();

        for (int i = 0; i < csvData.length(); i++) {
            char c = csvData.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            }

            if (c == '\n' && !inQuotes) {
                records.add(record.toString());
                record.setLength(0);
            } else {
                record.append(c);
            }
        }
        if (record.length() > 0) {
            records.add(record.toString());
        }
        return records;
    }

    /**
     * Basic CSV line splitter that handles quoted fields
     * (commas inside quotes are not treated as separators).
     */
    private String[] splitCSVLine(String line) {
        List<String> cols = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder cur = new StringBuilder();

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                cols.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cols.add(cur.toString().trim());
        return cols.toArray(new String[0]);
    }

    private double tryParseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void findLocalityAsync(double latitude, double longitude) {
        new Thread(() -> {
                try {
                    Geocoder geocoder = new Geocoder(this, Locale.getDefault());
                    List<Address> list = geocoder.getFromLocation(latitude, longitude, 1);

                    if (list != null && !list.isEmpty()) {
                        Address addr = list.get(0);
                        locality = addr.getLocality(); // e.g. city name
                    }
                } catch (IOException e) {
                    Log.d(TAG, "Exception while trying to get name of user's locality (city).", e);
                }
        }).start();
    }

    private void promptUserForMemoryGroup() {
        // Hide the web view.
        findViewById(R.id.repeaterBookWebView).setVisibility(View.GONE);

        // Update instructions to instead list the number of repeaters found.
        TextView findRepeatersStatusText = (TextView) findViewById(R.id.findRepeatersStatusText);
        String nearbyRepeatersFound = getString(R.string.nearby_repeaters_found) + nearbyRepeaters.size() + "\n\n" + getString(R.string.save_repeaters_group_instructions);
        findRepeatersStatusText.setText(nearbyRepeatersFound);

        // Show a memory group picker.
        findViewById(R.id.findRepeatersGroupInputHolder).setVisibility(View.VISIBLE);

        // If we know the locality (e.g. city), suggest it as a name for the new group.
        if (locality != null && !locality.trim().isEmpty()) { // Locality lookup may not have completed yet.
            AutoCompleteTextView autoCompleteTextView = (AutoCompleteTextView) findViewById(R.id.findRepeatersGroupTextInputEditText);
            autoCompleteTextView.setText(locality);
        }

        // Hide "Download" button.
        findViewById(R.id.findRepeatersDownloadButton).setVisibility(View.GONE);

        // Show a "SAVE" button to the right of Cancel.
        findViewById(R.id.findRepeatersSaveButton).setVisibility(View.VISIBLE);
    }

    private void populateMemoryGroups() {
        final Activity activity = this;
        threadPoolExecutor.execute(() -> viewModel.loadDataAsync(() -> {
            List<String> memoryGroups = viewModel.getAppDb().channelMemoryDao().getGroups();
            memoryGroups.removeIf(name -> name == null || name.trim().isEmpty());
            activity.runOnUiThread(() -> {
                AutoCompleteTextView editMemoryGroupTextView = findViewById(R.id.findRepeatersGroupTextInputEditText);
                ArrayAdapter arrayAdapter = new ArrayAdapter(activity, R.layout.dropdown_item, memoryGroups);
                editMemoryGroupTextView.setAdapter(arrayAdapter);
            });
        }));
    }

    @SuppressWarnings({"java:S1172", "javasecurity:S6384"}) // Called from XML; this only sets a fixed result code.
    public void findRepeatersSaveButtonClicked(View view) {
        String group = ((AutoCompleteTextView) findViewById(R.id.findRepeatersGroupTextInputEditText)).getText().toString().trim();
        final List<ChannelMemory> memoriesToAdd = new ArrayList<>();

        for (RepeaterInfo repeater : nearbyRepeaters) {

            ChannelMemory memory = new ChannelMemory();
            memory.name = repeater.call + " • " + repeater.location;
            memory.group = group;
            if (radioAudioService != null) {
                memory.frequency = radioAudioService.makeSafeHamFreq(String.valueOf(repeater.freq));
            } else {
                Log.e(TAG, "radioAudioService is null. Cannot set frequency.");
                continue; // Skip this repeater if radioAudioService is unavailable
            }
            if (repeater.offset < 0) {
                memory.offset = ChannelMemory.OFFSET_DOWN;
            } else if (repeater.offset > 0) {
                memory.offset = ChannelMemory.OFFSET_UP;
            } else {
                memory.offset = ChannelMemory.OFFSET_NONE;
            }
            memory.txTone = String.valueOf(repeater.tone);
            memory.rxTone = getString(R.string.none_display);
            memory.offsetKhz = Math.abs((int) (repeater.offset * 1000));
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
