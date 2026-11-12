package br.com.equimos;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.Network;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EquimosService extends Service {
    public interface StatusListener {
        void onStatus(String message, boolean connected);
    }

    private static final int PORT = 3761;
    private static final int FRAME_STATUS = 0x20;
    private static final int FRAME_SPEAKER = 0x21;
    private final IBinder binder = new LocalBinder();
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Object outputLock = new Object();
    private final ConcurrentHashMap<String, File> speechFiles = new ConcurrentHashMap<>();
    private volatile Socket socket;
    private volatile OutputStream output;
    private volatile Network cellularNetwork;
    private volatile String apiKey = "";
    private volatile String assistantContext = "";
    private volatile StatusListener listener;
    private volatile boolean ttsReady;
    private TextToSpeech tts;
    private ByteArrayOutputStream microphoneCapture;
    private BroadcastReceiver reminderReceiver;

    public class LocalBinder extends Binder {
        EquimosService getService() {
            return EquimosService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        apiKey = ApiKeyVault.load(this);
        createNotificationChannel();
        Notification note = new Notification.Builder(this, "equimos_connection")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("Equimos conectado")
                .setContentText("A conexão local com o ESP está ativa.")
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(18, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(18, note);
        }
        tts = new TextToSpeech(this, result -> {
            if (result == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("pt", "BR"));
                tts.setSpeechRate(0.96f);
                ttsReady = true;
            }
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {
                notifyStatus("Preparando resposta de voz", true);
            }
            @Override public void onDone(String id) {
                File file = speechFiles.remove(id);
                if (file != null) io.execute(() -> {
                    try {
                        sendWavToSpeaker(file);
                    } catch (Exception e) {
                        notifyStatus("Não consegui enviar a voz ao alto-falante", true);
                    } finally {
                        file.delete();
                    }
                });
            }
            @Override public void onError(String id) {
                speechFiles.remove(id);
                notifyStatus("O TTS do telefone não conseguiu gerar a voz", true);
            }
        });
        reminderReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String text = intent.getStringExtra("text");
                if (text == null || text.isEmpty()) text = "Seu lembrete chegou.";
                sendStatus("HORA DO LEMBRETE", null, -1.0, text);
                notifyStatus("Lembrete: " + text, isConnected());
                speakOnDevice("Lembrete: " + text);
            }
        };
        IntentFilter reminderFilter = new IntentFilter("br.com.equimos.REMINDER_FIRED");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(reminderReceiver, reminderFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(reminderReceiver, reminderFilter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        closeSocket();
        if (reminderReceiver != null) unregisterReceiver(reminderReceiver);
        if (tts != null) tts.shutdown();
        io.shutdownNow();
        super.onDestroy();
    }

    public void setListener(StatusListener value) {
        listener = value;
    }

    public void setApiKey(String value) {
        apiKey = value == null ? "" : value.trim();
    }

    public void setCellularNetwork(Network value) {
        cellularNetwork = value;
    }

    public void setAssistantContext(String value) {
        assistantContext = value == null ? "" : value;
    }

    public boolean isConnected() {
        Socket current = socket;
        return current != null && current.isConnected() && !current.isClosed();
    }

    public void connect(Network wifi) {
        io.execute(() -> {
            closeSocket();
            notifyStatus("Conectando à placa Equimos…", false);
            try {
                Socket candidate = wifi.getSocketFactory().createSocket();
                candidate.connect(new InetSocketAddress("192.168.4.1", PORT), 12000);
                socket = candidate;
                output = candidate.getOutputStream();
                notifyStatus("Equimos conectado", true);
                readFrames(candidate);
            } catch (Exception e) {
                notifyStatus("Não conectou. Confira EQUIMOS-C6 e tente novamente.", false);
            } finally {
                closeSocket();
                notifyStatus("Placa desconectada", false);
            }
        });
    }

    public void sendStatus(String status, Double tempC, double moonPhase,
                           String reminder) {
        try {
            JSONObject json = new JSONObject();
            json.put("status", status == null ? "APP ONLINE" : status);
            json.put("epoch", System.currentTimeMillis() / 1000L);
            json.put("moonPhase", moonPhase);
            if (tempC != null) json.put("tempC", tempC);
            if (reminder != null) json.put("reminder", reminder);
            sendFrame(FRAME_STATUS, json.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            notifyStatus("Falha ao atualizar a tela do Equimos", isConnected());
        }
    }

    private void readFrames(Socket from) {
        try {
            InputStream input = from.getInputStream();
            while (!from.isClosed()) {
                byte[] header = new byte[5];
                if (!readFully(input, header, 0, 5)) return;
                int type = header[0] & 0xff;
                int length = ((header[1] & 0xff) << 24)
                        | ((header[2] & 0xff) << 16)
                        | ((header[3] & 0xff) << 8)
                        | (header[4] & 0xff);
                if (length < 0 || length > 4096) return;
                byte[] payload = new byte[length];
                if (!readFully(input, payload, 0, length)) return;
                if (type == 0x01) {
                    microphoneCapture = new ByteArrayOutputStream();
                    notifyStatus("O Equimos está ouvindo", true);
                } else if (type == 0x02 && microphoneCapture != null) {
                    if (microphoneCapture.size() + length > 8 * 1024 * 1024) {
                        microphoneCapture = null;
                        notifyStatus("Áudio longo demais; fale por menos tempo", true);
                    } else {
                        microphoneCapture.write(payload);
                    }
                } else if (type == 0x03 && microphoneCapture != null) {
                    byte[] audio = microphoneCapture.toByteArray();
                    microphoneCapture = null;
                    io.execute(() -> answerFromAudio(audio));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void answerFromAudio(byte[] pcm) {
        if (pcm.length < 3200) {
            notifyStatus("Áudio muito curto; tente falar novamente", true);
            return;
        }
        if (apiKey.isEmpty()) {
            notifyStatus("Configure sua chave gratuita do Gemini no aplicativo", true);
            return;
        }
        try {
            notifyStatus("Consultando a IA…", true);
            JSONObject result = askGemini(pcm);
            String response = result.optString("reply", "").trim();
            if (response.isEmpty()) {
                notifyStatus("A IA não retornou uma resposta", true);
                return;
            }
            String action = result.optString("action", "none");
            boolean reminderScheduled = false;
            if ("reminder".equalsIgnoreCase(action)) {
                int minutes = result.optInt("minutes", 0);
                String reminder = result.optString("reminderText", "").trim();
                if (minutes > 0 && minutes <= 10080 && !reminder.isEmpty()) {
                    int id = (int) (System.currentTimeMillis() & 0x7fffffff);
                    long when = System.currentTimeMillis() + minutes * 60_000L;
                    ReminderStore.put(this, id, reminder, when);
                    ReminderStore.schedule(this, id, reminder, when);
                    sendStatus("LEMBRETE AGENDADO", null, -1.0, reminder);
                    reminderScheduled = true;
                }
            }
            notifyStatus(response, true);
            if (!reminderScheduled) {
                sendStatus("EQUIMOS RESPONDE", null, -1.0, null);
            }
            speakOnDevice(response);
        } catch (Exception e) {
            notifyStatus("IA indisponível: confira internet, chave e cota gratuita", true);
        }
    }

    private JSONObject askGemini(byte[] pcm) throws Exception {
        Network route = cellularNetwork;
        if (route == null) throw new IllegalStateException("Rede móvel indisponível");
        byte[] wav = makeWav(pcm);
        JSONObject inline = new JSONObject()
                .put("mimeType", "audio/wav")
                .put("data", Base64.encodeToString(wav, Base64.NO_WRAP));
        JSONArray parts = new JSONArray()
                .put(new JSONObject().put("text",
                        "Você é Equimos, assistente pessoal em português brasileiro. " +
                        "Entenda o áudio e retorne SOMENTE um objeto JSON com os campos " +
                        "transcript (texto ouvido), reply (resposta curta para falar), " +
                        "action (none ou reminder), reminderText (assunto) e minutes " +
                        "(minutos até o aviso). Se a pessoa pedir alarme/lembrete, defina " +
                        "action=reminder e calcule os minutos; para horário exato, use a " +
                        "próxima ocorrência no fuso local informado. Se faltar horário ou " +
                        "assunto, action=none e pergunte o que falta em reply. Limite minutes " +
                        "a 10080. Use o contexto atual abaixo; não invente dados atuais. " +
                        "Contexto: " + assistantContext))
                .put(new JSONObject().put("inlineData", inline));
        JSONObject request = new JSONObject().put("contents",
                new JSONArray().put(new JSONObject().put("parts", parts)))
                .put("generationConfig", new JSONObject()
                        .put("responseMimeType", "application/json"));
        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/"
                + "gemini-3.8-flash:generateContent");
        HttpURLConnection connection = (HttpURLConnection) route.openConnection(url);
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(60000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("x-goog-api-key", apiKey);
        try (OutputStream stream = connection.getOutputStream()) {
            stream.write(request.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = connection.getResponseCode();
        InputStream response = code >= 200 && code < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String body = readText(response);
        connection.disconnect();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("Gemini HTTP " + code + ": " + body);
        }
        JSONObject root = new JSONObject(body);
        JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) return new JSONObject();
        JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
        JSONArray answerParts = content == null ? null : content.optJSONArray("parts");
        if (answerParts == null) return new JSONObject();
        StringBuilder answer = new StringBuilder();
        for (int i = 0; i < answerParts.length(); i++) {
            String text = answerParts.getJSONObject(i).optString("text", "");
            if (!text.isEmpty()) answer.append(text);
        }
        String json = answer.toString().trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end < start) {
            return new JSONObject().put("reply", json).put("action", "none");
        }
        return new JSONObject(json.substring(start, end + 1));
    }

    private void speakOnDevice(String text) {
        if (!ttsReady || tts == null) {
            notifyStatus("TTS do Android ainda não está pronto", true);
            return;
        }
        String id = UUID.randomUUID().toString();
        File file = new File(getCacheDir(), "equimos-" + id + ".wav");
        speechFiles.put(id, file);
        int result = tts.synthesizeToFile(text, null, file, id);
        if (result != TextToSpeech.SUCCESS) {
            speechFiles.remove(id);
            notifyStatus("Falha ao sintetizar a resposta", true);
        }
    }

    private void sendWavToSpeaker(File file) throws Exception {
        byte[] wav = java.nio.file.Files.readAllBytes(file.toPath());
        int format = 0;
        int channels = 0;
        int sampleRate = 0;
        int bits = 0;
        int dataOffset = -1;
        int dataLength = 0;
        int position = 12;
        while (position + 8 <= wav.length) {
            String id = new String(wav, position, 4, StandardCharsets.US_ASCII);
            int size = le32(wav, position + 4);
            int start = position + 8;
            if (size < 0 || start + size > wav.length) break;
            if (id.equals("fmt ") && size >= 16) {
                format = le16(wav, start);
                channels = le16(wav, start + 2);
                sampleRate = le32(wav, start + 4);
                bits = le16(wav, start + 14);
            } else if (id.equals("data")) {
                dataOffset = start;
                dataLength = size;
            }
            position = start + size + (size & 1);
        }
        if (format != 1 || channels < 1 || sampleRate < 8000 || bits != 16
                || dataOffset < 0) {
            notifyStatus("O TTS selecionado não gerou WAV PCM compatível", true);
            return;
        }
        int sourceFrames = dataLength / (channels * 2);
        int targetFrames = (int) ((long) sourceFrames * 16000 / sampleRate);
        int offset = 0;
        while (offset < targetFrames) {
            int count = Math.min(2048, targetFrames - offset);
            ByteBuffer pcm = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count; i++) {
                int targetIndex = offset + i;
                int src = (int) ((long) targetIndex * sampleRate / 16000);
                int sum = 0;
                for (int channel = 0; channel < channels; channel++) {
                    int at = dataOffset + (src * channels + channel) * 2;
                    sum += (short) le16(wav, at);
                }
                pcm.putShort((short) (sum / channels));
            }
            sendFrame(FRAME_SPEAKER, pcm.array());
            offset += count;
        }
    }

    private static int le16(byte[] b, int p) {
        return (b[p] & 0xff) | ((b[p + 1] & 0xff) << 8);
    }

    private static int le32(byte[] b, int p) {
        return (b[p] & 0xff) | ((b[p + 1] & 0xff) << 8)
                | ((b[p + 2] & 0xff) << 16) | (b[p + 3] << 24);
    }

    private static byte[] makeWav(byte[] pcm) {
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        header.putInt(36 + pcm.length);
        header.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) 1);
        header.putInt(16000);
        header.putInt(32000);
        header.putShort((short) 2);
        header.putShort((short) 16);
        header.put("data".getBytes(StandardCharsets.US_ASCII));
        header.putInt(pcm.length);
        byte[] result = new byte[header.capacity() + pcm.length];
        System.arraycopy(header.array(), 0, result, 0, header.capacity());
        System.arraycopy(pcm, 0, result, header.capacity(), pcm.length);
        return result;
    }

    private void sendFrame(int type, byte[] payload) {
        OutputStream current = output;
        if (current == null) return;
        synchronized (outputLock) {
            try {
                DataOutputStream data = new DataOutputStream(current);
                data.writeByte(type);
                data.writeInt(payload.length);
                data.write(payload);
                data.flush();
            } catch (Exception e) {
                notifyStatus("Conexão de áudio interrompida", false);
            }
        }
    }

    private static boolean readFully(InputStream input, byte[] target, int offset, int length)
            throws Exception {
        int total = 0;
        while (total < length) {
            int count = input.read(target, offset + total, length - total);
            if (count < 0) return false;
            total += count;
        }
        return true;
    }

    private static String readText(InputStream input) throws Exception {
        if (input == null) return "";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int count;
        while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
        return bytes.toString(StandardCharsets.UTF_8.name());
    }

    private void notifyStatus(String message, boolean connected) {
        StatusListener current = listener;
        if (current != null) current.onStatus(message, connected);
    }

    private void closeSocket() {
        Socket current = socket;
        socket = null;
        output = null;
        if (current != null) {
            try { current.close(); } catch (Exception ignored) {}
        }
        microphoneCapture = null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(new NotificationChannel(
                    "equimos_connection", "Conexão Equimos",
                    NotificationManager.IMPORTANCE_LOW));
        }
    }
}
