package io.github.choumax.a1260xiaozhi;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Normal OTA activation v1: no account credentials, hardware IDs, or client-generated verification codes. */
public final class ActivationController implements AutoCloseable {
    public interface Listener { void onActivationState(ActivationState state, String detail); void onBindingCode(String message, String code); void onIssuedConfig(ConnectionConfig config); }
    public static final String DEFAULT_OTA_URL = "https://api.tenclass.net/xiaozhi/ota/";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final Context context; private final SettingsStore settings; private final Listener listener;
    private final HandlerThread workerThread = new HandlerThread("xiaozhi-activation"); private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = new OkHttpClient.Builder().callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build();
    private final AtomicLong generation = new AtomicLong(); private Handler worker; private ActivationState state = ActivationState.IDLE;
    public ActivationController(Context context, Listener listener) { this.context = context.getApplicationContext(); settings = new SettingsStore(this.context); this.listener = listener; workerThread.start(); worker = new Handler(workerThread.getLooper()); }
    public void start() { long run = generation.incrementAndGet(); worker.post(() -> checkOta(run, false)); }
    public void refresh() { start(); }
    public void rebind() { long run = generation.incrementAndGet(); worker.post(() -> { settings.clearIssuedConfig(); settings.setCustomServer(false); checkOta(run, false); }); }
    public void cancel() { generation.incrementAndGet(); worker.post(() -> post(ActivationState.CANCELLED, "已取消绑定检查。")); }
    private boolean current(long run) { return run == generation.get(); }
    private void checkOta(long run, boolean recheck) {
        if (!current(run)) return; post(recheck ? ActivationState.RECHECKING_OTA : ActivationState.CHECKING_OTA, recheck ? "正在确认绑定结果…" : "正在连接小智服务…");
        try {
            ActivationResponse response = ota(); if (!current(run)) return;
            if (response.hasActivation()) {
                post(ActivationState.WAITING_USER_BIND, response.message.isEmpty() ? "请在小智控制台填写验证码，完成后这里会自动连接。" : response.message);
                if (!response.code.isEmpty()) main.post(() -> { if (current(run)) listener.onBindingCode(response.message, response.code); });
                if (response.hasChallenge()) pollActivate(run); return;
            }
            if (!response.hasValidWebsocketV1()) { post(ActivationState.ERROR, "服务器没有返回受支持的连接配置，请稍后重试。"); return; }
            settings.saveIssuedConfig(response); ConnectionConfig config = settings.load();
            post(ActivationState.CONFIG_READY, "绑定配置已取得，正在连接。"); main.post(() -> { if (current(run)) listener.onIssuedConfig(config); });
        } catch (Exception ignored) { if (current(run)) post(ActivationState.ERROR, "连接绑定服务失败，请检查网络后重试。"); }
    }
    private void pollActivate(long run) {
        if (!current(run)) return; post(ActivationState.POLLING_ACTIVATE, "请在小智控制台输入下方验证码，正在等待绑定…");
        try {
            int code = activate(); if (!current(run)) return;
            if (code == 200) { checkOta(run, true); return; }
            if (code == 202) { worker.postDelayed(() -> pollActivate(run), 3000); return; }
            post(ActivationState.ERROR, "绑定检查未通过，请刷新验证码后重试。");
        } catch (Exception ignored) { if (current(run)) post(ActivationState.ERROR, "绑定检查失败，请刷新后重试。"); }
    }
    private ActivationResponse ota() throws Exception {
        Request request = request(DEFAULT_OTA_URL, systemInfo(), "POST");
        try (Response response = http.newCall(request).execute()) { if (response.code() != 200 || response.body() == null) throw new IOException("OTA request failed"); return ActivationResponse.parse(response.body().string()); }
    }
    private int activate() throws Exception {
        Request request = request(DEFAULT_OTA_URL + "activate", "{}", "POST");
        try (Response response = http.newCall(request).execute()) { return response.code(); }
    }
    private Request request(String url, String json, String method) {
        ConnectionConfig id = settings.load();
        return new Request.Builder().url(url).post(RequestBody.create(json, JSON)).header("Activation-Version", "1")
                .header("Device-Id", id.deviceId).header("Client-Id", id.clientId)
                .header("User-Agent", "A1260-Android/" + BuildConfig.VERSION_NAME).header("Accept-Language", "zh-CN")
                .header("Content-Type", "application/json").build();
    }
    private String systemInfo() {
        try {
            ConnectionConfig id = settings.load(); org.json.JSONObject root = new org.json.JSONObject(); root.put("version", 2); root.put("language", "zh-CN"); root.put("flash_size", 0); root.put("minimum_free_heap_size", "0"); root.put("mac_address", id.deviceId); root.put("uuid", id.clientId); root.put("chip_model_name", Build.HARDWARE == null ? "android" : Build.HARDWARE);
            org.json.JSONObject chip = new org.json.JSONObject(); chip.put("model", 0); chip.put("cores", Runtime.getRuntime().availableProcessors()); chip.put("revision", 0); chip.put("features", 0); root.put("chip_info", chip);
            org.json.JSONObject app = new org.json.JSONObject(); app.put("name", "A1260 Android Voice Client"); app.put("version", BuildConfig.VERSION_NAME); app.put("compile_time", BuildConfig.BUILD_TYPE); app.put("idf_version", "android-" + Build.VERSION.SDK_INT); app.put("elf_sha256", ""); root.put("application", app); root.put("partition_table", new org.json.JSONArray());
            org.json.JSONObject display = new org.json.JSONObject(); display.put("monochrome", false); display.put("width", 0); display.put("height", 0); root.put("display", display);
            org.json.JSONObject board = new org.json.JSONObject(); board.put("type", "android"); board.put("name", Build.MODEL == null ? "Android" : Build.MODEL); board.put("manufacturer", Build.MANUFACTURER == null ? "unknown" : Build.MANUFACTURER); board.put("mac", id.deviceId); root.put("board", board); return root.toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private void post(ActivationState newState, String detail) { state = newState; long run = generation.get(); main.post(() -> { if (current(run)) listener.onActivationState(newState, detail); }); }
    @Override public void close() { generation.incrementAndGet(); workerThread.quitSafely(); http.dispatcher().executorService().shutdown(); }
}
