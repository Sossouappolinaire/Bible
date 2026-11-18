package com.tvkouame.studio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Comptes enregistrés sur le téléphone (aucune validation par une tierce personne, avec ou sans Internet).
 * PIN « propriétaire » : Studio + Academy biblique. Tout autre PIN : Academy biblique seulement.
 * Les PIN ne sont jamais écrits en clair (empreinte salée), et le PIN propriétaire non plus dans le code.
 */
final class Comptes {
    private static final String PIN_PATRON = "92f03d5a81080eccb4ec2d049dded3d2a42b59d46d06ce197e2758fdc001a4e2";

    private Comptes() {}

    static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    static boolean estPatron(String pin) { return sha("kouame|" + pin).equals(PIN_PATRON); }

    // ---------- Fichier des comptes : copie interne + copie dans KouameAp (fusionnées) ----------
    static synchronized JSONArray charger(Context c) {
        Map<String, JSONObject> m = new LinkedHashMap<>();
        lire(new File(c.getFilesDir(), "comptes.json"), m);
        File r = KouameAp.racine(c);
        if (r != null) lire(new File(r, "comptes.json"), m);
        JSONArray out = new JSONArray();
        for (JSONObject o : m.values()) out.put(o);
        return out;
    }

    private static void lire(File f, Map<String, JSONObject> m) {
        if (!f.exists()) return;
        try {
            JSONArray a = new JSONArray(KouameAp.lire(f));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String e = o.optString("email");
                if (!e.isEmpty() && !m.containsKey(e)) m.put(e, o);
            }
        } catch (Exception x) { }
    }

    static synchronized void sauver(Context c, JSONArray a) {
        try { KouameAp.ecrire(new File(c.getFilesDir(), "comptes.json"), a.toString()); } catch (Exception e) { }
        File r = KouameAp.racine(c);
        if (r != null) try { KouameAp.ecrire(new File(r, "comptes.json"), a.toString()); } catch (Exception e) { }
    }

    /** Recopie les comptes dans KouameAp dès que le dossier est accessible. */
    static void synchroniser(Context c) { sauver(c, charger(c)); }

    // ---------- Inscription / connexion : renvoient null si tout va bien, sinon le message d'erreur ----------
    static synchronized String inscrire(Context c, String nom, String email, String pin) {
        JSONArray a = charger(c);
        for (int i = 0; i < a.length(); i++)
            if (email.equals(a.optJSONObject(i).optString("email"))) return "Cet email est déjà inscrit : utilisez « Connexion ».";
        try {
            String sel = Long.toHexString(new SecureRandom().nextLong());
            a.put(new JSONObject().put("nom", nom).put("email", email).put("sel", sel)
                .put("pin", sha(sel + "|" + pin)).put("date", System.currentTimeMillis()));
        } catch (Exception e) { return "Erreur d'enregistrement du compte."; }
        sauver(c, a);
        ouvrirSession(c, nom, email, estPatron(pin));
        return null;
    }

    static String connecter(Context c, String email, String pin) {
        JSONArray a = charger(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || !email.equals(o.optString("email"))) continue;
            if (!sha(o.optString("sel") + "|" + pin).equals(o.optString("pin"))) return "PIN incorrect.";
            ouvrirSession(c, o.optString("nom"), email, estPatron(pin));
            return null;
        }
        return "Aucun compte avec cet email : inscrivez-vous d'abord.";
    }

    // ---------- Session ----------
    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("session", 0); }

    private static void ouvrirSession(Context c, String nom, String email, boolean patron) {
        sp(c).edit().putString("nom", nom).putString("email", email).putBoolean("patron", patron).commit();
    }

    static boolean connecte(Context c) { return !sp(c).getString("email", "").isEmpty(); }
    static String nom(Context c) { return sp(c).getString("nom", ""); }
    static boolean patron(Context c) { return sp(c).getBoolean("patron", false); }
    static void deconnecter(Context c) { sp(c).edit().clear().commit(); }
}
