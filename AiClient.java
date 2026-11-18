package com.tvkouame.studio;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Appelle les IA gratuites (format OpenAI /chat/completions) avec bascule automatique d'un fournisseur à l'autre. */
public final class AiClient {

    private AiClient() {}

    public interface Callback {
        /** Appelé sur le fil principal. */
        void onResult(String text, String provider);
        void onError(String message);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Fournisseur -> heure (ms) avant laquelle on ne le réessaie pas. */
    private static final java.util.Map<String, Long> PAUSED = new java.util.HashMap<>();

    private static final class HttpFail extends Exception {
        final int code;
        HttpFail(int code, String msg) { super(msg); this.code = code; }
    }

    private static synchronized boolean paused(String name, long now) {
        Long t = PAUSED.get(name);
        return t != null && t > now;
    }

    private static synchronized void pause(String name, long ms) { PAUSED.put(name, System.currentTimeMillis() + ms); }

    private static synchronized void resume(String name) { PAUSED.remove(name); }

    public static void chat(final String system, final String user, final float temperature, final Callback cb) {
        new Thread(() -> {
            final StringBuilder errors = new StringBuilder();
            // passage 1 : les fournisseurs disponibles, dans l'ordre ; passage 2 : ceux qui étaient en pause avant de commencer
            final java.util.Set<String> skipped = new java.util.HashSet<>();
            for (int pass = 0; pass < 2; pass++) {
                for (AiConfig.Provider p : AiConfig.PROVIDERS) {
                    if (!p.enabled()) continue;
                    if (pass == 0 && paused(p.name, System.currentTimeMillis())) { skipped.add(p.name); continue; }
                    if (pass == 1 && !skipped.contains(p.name)) continue;
                    for (String model : p.models) {
                        try {
                            String text = call(p, model, system, user, temperature);
                            if (text != null && text.trim().length() > 0) {
                                resume(p.name);
                                final String out = text.trim(), name = p.name;
                                MAIN.post(() -> cb.onResult(out, name));
                                return;
                            }
                            errors.append(p.name).append(" : réponse vide\n");
                        } catch (HttpFail e) {
                            errors.append(p.name).append(" (").append(model).append(") : HTTP ").append(e.code).append('\n');
                            if (e.code == 401 || e.code == 403) { pause(p.name, AiConfig.BAD_KEY_COOLDOWN_MS); break; }
                            if (e.code == 429 || e.code >= 500) { pause(p.name, AiConfig.COOLDOWN_MS); break; }
                            // 400/404 : ce modèle n'existe plus ou refuse la demande, on essaie le modèle suivant du même fournisseur
                        } catch (Exception e) {
                            errors.append(p.name).append(" (").append(model).append(") : ").append(shortMsg(e)).append('\n');
                            pause(p.name, AiConfig.COOLDOWN_MS);
                            break;
                        }
                    }
                }
            }
            final String msg = errors.length() == 0 ? "Aucune IA n'a répondu." : errors.toString().trim();
            MAIN.post(() -> cb.onError(msg));
        }, "ai-chat").start();
    }

    private static String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null) m = e.getClass().getSimpleName();
        if (e instanceof java.net.UnknownHostException) m = "pas de connexion Internet";
        else if (e instanceof java.net.SocketTimeoutException) m = "délai dépassé";
        return m.length() > 120 ? m.substring(0, 120) : m;
    }

    private static String call(AiConfig.Provider p, String model, String system, String user, float temp) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", temp);
        body.put("max_tokens", 2400);
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "system").put("content", system));
        msgs.put(new JSONObject().put("role", "user").put("content", user));
        body.put("messages", msgs);

        HttpURLConnection c = (HttpURLConnection) new URL(p.endpoint).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(AiConfig.CONNECT_TIMEOUT_MS);
            c.setReadTimeout(AiConfig.READ_TIMEOUT_MS);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            if (!AiConfig.NO_KEY.equals(p.key)) c.setRequestProperty("Authorization", "Bearer " + p.key);
            try (OutputStream os = c.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = c.getResponseCode();
            String resp = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) throw new HttpFail(code, resp.length() > 200 ? resp.substring(0, 200) : resp);
            JSONObject j = new JSONObject(resp);
            JSONObject msg = j.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
            String t = msg.optString("content", "");
            return t == null || "null".equals(t) ? "" : t;
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }

    /** Extrait le premier objet JSON {...} d'une réponse (les IA ajoutent parfois du texte ou des ```). */
    public static JSONObject extractJson(String s) throws Exception {
        int a = s.indexOf('{');
        if (a < 0) throw new Exception("réponse sans JSON");
        int depth = 0;
        boolean str = false;
        for (int i = a; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (str) { if (ch == '\\') i++; else if (ch == '"') str = false; continue; }
            if (ch == '"') str = true;
            else if (ch == '{') depth++;
            else if (ch == '}' && --depth == 0) return new JSONObject(s.substring(a, i + 1));
        }
        throw new Exception("JSON incomplet");
    }
}
