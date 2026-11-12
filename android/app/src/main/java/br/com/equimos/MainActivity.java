package br.com.equimos;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.text.InputType;
import android.text.Html;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.media.AudioManager;
import android.media.MediaPlayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(7, 14, 25);
    private static final int PANEL = Color.rgb(17, 29, 45);
    private static final int CYAN = Color.rgb(0, 217, 245);
    private static final int WHITE = Color.rgb(242, 247, 255);
    private static final int MUTED = Color.rgb(149, 169, 194);
    private final ExecutorService worker = Executors.newCachedThreadPool();
    private final ScheduledExecutorService periodic = Executors.newSingleThreadScheduledExecutor();

    private ConnectivityManager connectivity;
    private Network deviceNetwork;
    private Network cellularNetwork;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private ConnectivityManager.NetworkCallback cellularCallback;
    private EquimosService service;
    private boolean bound;
    private boolean serviceStarted;
    private boolean periodicStarted;
    private volatile Double temperature;
    private String nextReminder = "";
    private EditText keyInput;
    private EditText reminderInput;
    private EditText minutesInput;
    private EditText radioInput;
    private EditText tvInput;
    private EditText webhookInput;
    private TextView connectionLabel;
    private TextView featureLabel;
    private MediaPlayer mediaPlayer;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((EquimosService.LocalBinder) binder).getService();
            bound = true;
            service.setListener((message, connected) -> runOnUiThread(() -> {
                connectionLabel.setText((connected ? "● " : "○ ") + message);
                connectionLabel.setTextColor(connected ? CYAN : MUTED);
            }));
            service.setApiKey(keyInput.getText().toString());
            service.setCellularNetwork(cellularNetwork);
            updateAssistantContext();
            if (deviceNetwork != null) service.connect(deviceNetwork);
            beginPeriodicStatus();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        buildInterface();
        requestNotificationPermission();
    }

    private void buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(28), dp(20), dp(34));
        scroll.addView(page);

        TextView title = new TextView(this);
        title.setText("EQUIMOS");
        title.setTextColor(CYAN);
        title.setTextSize(31);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        page.addView(title);
        TextView intro = label("Assistente pessoal • ESP32-C6 + Android", 14, MUTED);
        page.addView(intro);

        LinearLayout connection = section(page, "Conexão com a placa");
        connectionLabel = label("○ Ainda não conectado", 15, MUTED);
        connection.addView(connectionLabel);
        addButton(connection, "Conectar ao Equimos", this::connectToDevice);
        addButton(connection, "Atualizar relógio, Lua e clima", this::refreshData);

        LinearLayout voice = section(page, "Voz e inteligência");
        keyInput = new EditText(this);
        keyInput.setSingleLine(true);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setHint("Chave da API Gemini");
        keyInput.setHintTextColor(MUTED);
        keyInput.setTextColor(WHITE);
        keyInput.setText(ApiKeyVault.load(this));
        voice.addView(keyInput, matchWrap());
        addButton(voice, "Salvar chave no Android", () -> {
            try {
                String key = keyInput.getText().toString().trim();
                ApiKeyVault.save(this, key);
                if (service != null) service.setApiKey(key);
                toast(key.isEmpty() ? "Chave removida." : "Chave protegida e salva.");
            } catch (Exception e) {
                toast("Não consegui guardar a chave no Android.");
            }
        });
        voice.addView(label("Use a chave criada no Google AI Studio. A conversa começa pelo toque conectado à placa.", 13, MUTED));

        LinearLayout reminders = section(page, "Lembretes e alarmes");
        reminderInput = new EditText(this);
        reminderInput.setSingleLine(true);
        reminderInput.setHint("O que devo lembrar?");
        reminderInput.setHintTextColor(MUTED);
        reminderInput.setTextColor(WHITE);
        reminders.addView(reminderInput, matchWrap());
        minutesInput = new EditText(this);
        minutesInput.setSingleLine(true);
        minutesInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        minutesInput.setText("10");
        minutesInput.setHint("Minutos");
        minutesInput.setHintTextColor(MUTED);
        minutesInput.setTextColor(WHITE);
        reminders.addView(minutesInput, matchWrap());
        addButton(reminders, "Criar lembrete", this::scheduleReminder);

        LinearLayout media = section(page, "Rádio e televisão");
        radioInput = new EditText(this);
        radioInput.setSingleLine(true);
        radioInput.setHint("Rádio: nome ou estilo");
        radioInput.setHintTextColor(MUTED);
        radioInput.setTextColor(WHITE);
        media.addView(radioInput, matchWrap());
        addButton(media, "Buscar rádios gratuitas", this::searchRadio);
        tvInput = new EditText(this);
        tvInput.setSingleLine(true);
        tvInput.setHint("Programa de TV ou série");
        tvInput.setHintTextColor(MUTED);
        tvInput.setTextColor(WHITE);
        media.addView(tvInput, matchWrap());
        addButton(media, "Consultar programas", this::searchTv);
        addButton(media, "Parar rádio", this::stopRadio);

        LinearLayout phone = section(page, "Telefone, TV e navegação");
        addButton(phone, "Abrir transmissão Cast do Android", this::openCast);
        addButton(phone, "Abrir Google Home", this::openGoogleHome);
        addButton(phone, "Abrir navegação no Google Maps", this::openMaps);

        LinearLayout controls = section(page, "Controle por serviço compatível");
        webhookInput = new EditText(this);
        webhookInput.setSingleLine(true);
        webhookInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI);
        webhookInput.setHint("URL HTTPS de comando do seu aparelho");
        webhookInput.setHintTextColor(MUTED);
        webhookInput.setTextColor(WHITE);
        controls.addView(webhookInput, matchWrap());
        addButton(controls, "Enviar comando HTTP", this::sendWebhook);
        controls.addView(label("A URL precisa ser oferecida pelo fabricante ou serviço do aparelho. Não existe uma API gratuita universal para qualquer TV ou dispositivo.", 13, MUTED));

        featureLabel = label("Pronto para conectar.", 13, MUTED);
        page.addView(featureLabel);
        setContentView(scroll);
    }

    private LinearLayout section(LinearLayout parent, String title) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(15), dp(12), dp(15), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PANEL);
        bg.setCornerRadius(dp(16));
        box.setBackground(bg);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(18);
        parent.addView(box, params);
        TextView heading = label(title, 17, WHITE);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        box.addView(heading);
        return box;
    }

    private void addButton(LinearLayout parent, String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(WHITE);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        parent.addView(button, params);
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, dp(5), 0, dp(5));
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void connectToDevice() {
        if (Build.VERSION.SDK_INT < 29) {
            toast("Este app requer Android 10 ou mais recente.");
            return;
        }
        try {
            if (wifiCallback != null) connectivity.unregisterNetworkCallback(wifiCallback);
        } catch (Exception ignored) {}
        WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                .setSsid("EQUIMOS-C6")
                .setWpa2Passphrase("equimos2026")
                .build();
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build();
        wifiCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                deviceNetwork = network;
                runOnUiThread(() -> {
                    connectionLabel.setText("Wi-Fi do Equimos conectado");
                    connectionLabel.setTextColor(CYAN);
                });
                ensureService();
                requestCellularNetwork();
            }
            @Override
            public void onUnavailable() {
                runOnUiThread(() -> toast("Não conectou. Verifique se a placa está ligada."));
            }
            @Override
            public void onLost(Network network) {
                if (deviceNetwork != null && deviceNetwork.equals(network)) {
                    deviceNetwork = null;
                    runOnUiThread(() -> {
                        connectionLabel.setText("○ Wi-Fi do Equimos desconectado");
                        connectionLabel.setTextColor(MUTED);
                    });
                }
            }
        };
        connectionLabel.setText("Aguardando autorização do Wi-Fi…");
        connectivity.requestNetwork(request, wifiCallback);
    }

    private void ensureService() {
        if (!serviceStarted) {
            Intent intent = new Intent(this, EquimosService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
            serviceStarted = true;
        }
        if (!bound) bindService(new Intent(this, EquimosService.class),
                serviceConnection, Context.BIND_AUTO_CREATE);
        if (service != null && deviceNetwork != null) {
            service.setApiKey(keyInput.getText().toString());
            service.setCellularNetwork(cellularNetwork);
            service.connect(deviceNetwork);
        }
    }

    private void requestCellularNetwork() {
        if (cellularCallback != null) return;
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        cellularCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                cellularNetwork = network;
                connectivity.bindProcessToNetwork(network);
                if (service != null) service.setCellularNetwork(network);
                runOnUiThread(() -> {
                    featureLabel.setText("Dados móveis disponíveis para os serviços gratuitos.");
                    refreshData();
                });
            }
            @Override
            public void onLost(Network network) {
                if (cellularNetwork != null && cellularNetwork.equals(network)) {
                    cellularNetwork = null;
                    connectivity.bindProcessToNetwork(null);
                    if (service != null) service.setCellularNetwork(null);
                    runOnUiThread(() -> featureLabel.setText("Sem rede móvel para consultas online."));
                }
            }
        };
        try {
            connectivity.requestNetwork(request, cellularCallback);
        } catch (Exception e) {
            featureLabel.setText("Ative os dados móveis para usar a IA e consultas online.");
        }
    }

    private void beginPeriodicStatus() {
        if (periodicStarted) return;
        periodicStarted = true;
        periodic.scheduleAtFixedRate(this::refreshData, 0, 30, TimeUnit.MINUTES);
    }

    private void refreshData() {
        double phase = moonPhase(System.currentTimeMillis() / 1000L);
        updateAssistantContext();
        if (service != null) service.sendStatus("APP ONLINE", temperature, phase, nextReminder);
        Network route = cellularNetwork;
        if (route == null) return;
        worker.execute(() -> {
            try {
                URL url = new URL("https://api.open-meteo.com/v1/forecast"
                        + "?latitude=-29.99&longitude=-51.12"
                        + "&current=temperature_2m&timezone=America%2FSao_Paulo");
                HttpURLConnection connection = (HttpURLConnection) route.openConnection(url);
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(12000);
                JSONObject root = new JSONObject(readResponse(connection));
                double value = root.getJSONObject("current").getDouble("temperature_2m");
                temperature = value;
                updateAssistantContext();
                if (service != null) service.sendStatus("CLIMA ATUALIZADO",
                        temperature, moonPhase(System.currentTimeMillis() / 1000L), nextReminder);
                runOnUiThread(() -> featureLabel.setText(
                        String.format(java.util.Locale.getDefault(),
                                "Sarandi • %.1f °C • dados atualizados pela internet", value)));
            } catch (Exception e) {
                runOnUiThread(() -> featureLabel.setText("Clima indisponível; confira a rede móvel."));
            }
        });
    }

    private static double moonPhase(long epoch) {
        double days = (epoch - 947182440.0) / 86400.0;
        double fraction = (days / 29.530588853) % 1.0;
        return fraction < 0 ? fraction + 1.0 : fraction;
    }

    private void updateAssistantContext() {
        if (service == null) return;
        String localTime = java.time.ZonedDateTime.now(
                java.time.ZoneId.of("America/Sao_Paulo"))
                .format(java.time.format.DateTimeFormatter.ofPattern(
                        "dd/MM/yyyy HH:mm z", java.util.Locale.forLanguageTag("pt-BR")));
        double phase = moonPhase(System.currentTimeMillis() / 1000L);
        String weather = temperature == null ? "clima ainda não consultado"
                : String.format(java.util.Locale.US,
                        "Sarandi/Porto Alegre: %.1f °C", temperature);
        long illumination = Math.round(
                ((1 - Math.cos(phase * 2 * Math.PI)) / 2) * 100);
        service.setAssistantContext("Agora: " + localTime + ". " + weather
                + ". Lua: " + illumination + "% iluminada.");
    }

    private void scheduleReminder() {
        String text = reminderInput.getText().toString().trim();
        if (text.isEmpty()) {
            toast("Escreva o lembrete primeiro.");
            return;
        }
        int minutes;
        try {
            minutes = Math.max(1, Integer.parseInt(minutesInput.getText().toString()));
        } catch (Exception e) {
            minutes = 10;
        }
        int id = (int) (System.currentTimeMillis() & 0x7fffffff);
        long when = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes);
        ReminderStore.put(this, id, text, when);
        ReminderStore.schedule(this, id, text, when);
        nextReminder = text;
        if (service != null) service.sendStatus("LEMBRETE AGENDADO",
                temperature, moonPhase(System.currentTimeMillis() / 1000L), text);
        toast("Lembrete criado para daqui a " + minutes + " min.");
    }

    private void searchRadio() {
        String query = radioInput.getText().toString().trim();
        if (query.isEmpty()) query = "Brazil";
        final String term = query;
        worker.execute(() -> {
            try {
                String encoded = URLEncoder.encode(term, StandardCharsets.UTF_8.name());
                URL url = new URL("https://de1.api.radio-browser.info/json/stations/search"
                        + "?name=" + encoded + "&limit=12&hidebroken=true");
                JSONArray stations = new JSONArray(readResponse(openConnection(url)));
                runOnUiThread(() -> showStations(stations));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Rádios indisponíveis. Confira os dados móveis."));
            }
        });
    }

    private void showStations(JSONArray stations) {
        java.util.ArrayList<JSONObject> usable = new java.util.ArrayList<>();
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        for (int i = 0; i < stations.length(); i++) {
            JSONObject station = stations.optJSONObject(i);
            if (station == null) continue;
            String stream = station.optString("url_resolved", "");
            if (!stream.startsWith("https://")) continue;
            usable.add(station);
            names.add(station.optString("name", "Rádio sem nome")
                    + " • " + station.optString("country", ""));
        }
        if (usable.isEmpty()) {
            toast("Não encontrei estações HTTPS disponíveis para tocar.");
            return;
        }
        new AlertDialog.Builder(this).setTitle("Rádios gratuitas")
                .setItems(names.toArray(new String[0]), (dialog, which) ->
                        playStation(usable.get(which).optString("url_resolved", "")))
                .setNegativeButton("Fechar", null).show();
    }

    private void playStation(String url) {
        stopRadio();
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mediaPlayer.setDataSource(url);
            mediaPlayer.setOnPreparedListener(MediaPlayer::start);
            mediaPlayer.setOnErrorListener((player, what, extra) -> {
                toast("Esta rádio está fora do ar ou bloqueou a transmissão.");
                stopRadio();
                return true;
            });
            mediaPlayer.prepareAsync();
            toast("Conectando à rádio. O áudio segue a saída do telefone/Bluetooth.");
        } catch (Exception e) {
            toast("Não consegui iniciar esta rádio.");
        }
    }

    private void stopRadio() {
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Exception ignored) {}
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private void searchTv() {
        String query = tvInput.getText().toString().trim();
        if (query.isEmpty()) {
            toast("Digite o nome de um programa.");
            return;
        }
        worker.execute(() -> {
            try {
                String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
                URL url = new URL("https://api.tvmaze.com/search/shows?q=" + encoded);
                JSONArray results = new JSONArray(readResponse(openConnection(url)));
                runOnUiThread(() -> showTvResults(results));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Consulta de TV indisponível."));
            }
        });
    }

    private void showTvResults(JSONArray results) {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        java.util.ArrayList<String> descriptions = new java.util.ArrayList<>();
        for (int i = 0; i < Math.min(results.length(), 8); i++) {
            JSONObject show = results.optJSONObject(i);
            JSONObject info = show == null ? null : show.optJSONObject("show");
            if (info == null) continue;
            names.add(info.optString("name", "Sem título"));
            String summary = info.optString("summary", "Sem sinopse disponível.");
            descriptions.add(Html.fromHtml(summary, Html.FROM_HTML_MODE_LEGACY).toString());
        }
        if (names.isEmpty()) {
            toast("Não encontrei resultados.");
            return;
        }
        new AlertDialog.Builder(this).setTitle("Programas encontrados")
                .setItems(names.toArray(new String[0]), (dialog, which) ->
                        new AlertDialog.Builder(this).setTitle(names.get(which))
                                .setMessage(descriptions.get(which))
                                .setPositiveButton("OK", null).show())
                .setNegativeButton("Fechar", null).show();
    }

    private void openCast() {
        toast("Cast exige que telefone e TV estejam na mesma Wi-Fi; o link com a placa pode pausar.");
        try {
            startActivity(new Intent("android.settings.CAST_SETTINGS"));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                toast("Use o seletor de transmissão do sistema ou o Google Home.");
            } catch (Exception ignored) {
                toast("Este telefone não disponibilizou o seletor Cast.");
            }
        }
    }

    private void openGoogleHome() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(
                "com.google.android.apps.chromecast.app");
        if (launch == null) {
            toast("Instale e configure o Google Home para controlar aparelhos compatíveis.");
            return;
        }
        startActivity(launch);
    }

    private void openMaps() {
        EditText destination = new EditText(this);
        destination.setHint("Endereço ou lugar");
        new AlertDialog.Builder(this).setTitle("Navegar com o telefone")
                .setView(destination)
                .setPositiveButton("Abrir Maps", (dialog, which) -> {
                    try {
                        String query = URLEncoder.encode(destination.getText().toString(),
                                StandardCharsets.UTF_8.name());
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                android.net.Uri.parse("google.navigation:q=" + query)));
                    } catch (Exception e) {
                        toast("Google Maps não está disponível.");
                    }
                })
                .setNegativeButton("Cancelar", null).show();
    }

    private void sendWebhook() {
        String endpoint = webhookInput.getText().toString().trim();
        if (!endpoint.startsWith("https://")) {
            toast("Use uma URL HTTPS oferecida pelo seu aparelho/serviço.");
            return;
        }
        worker.execute(() -> {
            try {
                URL url = new URL(endpoint);
                HttpURLConnection connection = openConnection(url);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                int code = connection.getResponseCode();
                connection.disconnect();
                runOnUiThread(() -> toast("Comando enviado. Resposta HTTP " + code + "."));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Falha ao enviar o comando HTTPS."));
            }
        });
    }

    private HttpURLConnection openConnection(URL url) throws Exception {
        Network route = cellularNetwork;
        if (route == null) throw new IllegalStateException("Dados móveis indisponíveis");
        HttpURLConnection connection = (HttpURLConnection) route.openConnection(url);
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", "Equimos-Android/1.0");
        return connection;
    }

    private static String readResponse(HttpURLConnection connection) throws Exception {
        try (java.io.InputStream stream = connection.getInputStream();
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) >= 0) output.write(buffer, 0, count);
            connection.disconnect();
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 71);
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        stopRadio();
        periodic.shutdownNow();
        worker.shutdownNow();
        if (bound) unbindService(serviceConnection);
        if (wifiCallback != null) {
            try { connectivity.unregisterNetworkCallback(wifiCallback); } catch (Exception ignored) {}
        }
        if (cellularCallback != null) {
            try { connectivity.unregisterNetworkCallback(cellularCallback); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
