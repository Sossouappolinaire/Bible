package com.tvkouame.studio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dossier « KouameAp » à la racine du téléphone : comptes, thèmes créés par l'IA, compositions, enregistrements.
 * Il n'est pas effacé quand on désinstalle l'application : après réinstallation, tout est relu depuis ce dossier.
 * Il faut une seule autorisation : « Accès à tous les fichiers ».
 */
public final class KouameAp {
    public static final String NOM = "KouameAp";
    // Réglages sauvegardés : Academy biblique (thèmes IA, caches), enregistreur, studio, vidéos
    static final String[] PREFS = {"b", "studio", "studio_beat", "video"};

    private static final ExecutorService EX = Executors.newSingleThreadExecutor();
    private static final Map<String, Integer> dernier = new HashMap<>();
    private static volatile boolean planifie = false;

    private KouameAp() {}

    // ---------- Accès au dossier ----------
    static boolean acces(Context c) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Dossier KouameAp (créé au besoin), ou null si l'autorisation n'est pas donnée. */
    static File racine(Context c) {
        if (!acces(c)) return null;
        try {
            File r = new File(Environment.getExternalStorageDirectory(), NOM);
            if (!r.exists() && !r.mkdirs()) return null;
            return r.canWrite() ? r : null;
        } catch (Exception e) {
            return null;
        }
    }

    static void demanderAcces(final Activity a) {
        new AlertDialog.Builder(a)
            .setTitle("Dossier « " + NOM + " »")
            .setMessage("Pour garder vos données (comptes, thèmes créés par l'IA, compositions, enregistrements) même après "
                + "une réinstallation, l'application les range dans le dossier « " + NOM + " » du téléphone.\n\n"
                + "Autorisez l'accès aux fichiers à l'écran qui va s'ouvrir.")
            .setPositiveButton("Autoriser", (d, w) -> ouvrirReglage(a))
            .setNegativeButton("Plus tard", null)
            .show();
    }

    static void ouvrirReglage(Activity a) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + a.getPackageName())));
            } catch (Exception e) {
                try { a.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); } catch (Exception x) { }
            }
        } else {
            a.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, 77);
        }
    }

    // ---------- Première utilisation du dossier : transfert des anciennes données + restauration ----------
    static boolean pret(Context c) {
        return c.getSharedPreferences("kouame_meta", 0).getBoolean("init", false);
    }

    /** À appeler hors du fil principal. */
    static void initialiser(Context c) {
        File r = racine(c);
        if (r == null) return;
        File dest = new File(r, "enregistrements");
        deplacer(c.getExternalFilesDir(Storage.FOLDER), dest);
        deplacer(new File(c.getFilesDir(), Storage.FOLDER), dest);
        restaurer(c, r);
        Comptes.synchroniser(c);
        c.getSharedPreferences("kouame_meta", 0).edit().putBoolean("init", true).commit();
        sauvegarderAsync(c);
    }

    private static void deplacer(File src, File dst) {
        if (src == null || !src.exists()) return;
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] fs = src.listFiles();
            if (fs != null) for (File f : fs) deplacer(f, new File(dst, f.getName()));
            src.delete();
        } else {
            if (dst.exists()) return; // on garde la version déjà présente dans KouameAp
            if (!src.renameTo(dst)) {
                try { copier(src, dst); src.delete(); } catch (Exception e) { }
            }
        }
    }

    private static void copier(File src, File dst) throws Exception {
        try (InputStream is = new FileInputStream(src); OutputStream os = new FileOutputStream(dst)) {
            byte[] b = new byte[16384];
            int n;
            while ((n = is.read(b)) > 0) os.write(b, 0, n);
        }
    }

    // ---------- Lecture / écriture de fichiers texte ----------
    static String lire(File f) throws Exception {
        try (InputStream is = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = is.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        }
    }

    static void ecrire(File f, String s) throws Exception {
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream os = new FileOutputStream(tmp)) { os.write(s.getBytes("UTF-8")); }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
    }

    // ---------- Sauvegarde des réglages et thèmes dans KouameAp/donnees ----------
    static void sauvegarderAsync(final Context c) {
        if (planifie) return;
        planifie = true;
        final Context app = c.getApplicationContext();
        EX.execute(() -> {
            try { Thread.sleep(1500); } catch (Exception e) { }
            planifie = false;
            try { sauvegarder(app); } catch (Exception e) { }
        });
    }

    static synchronized void sauvegarder(Context c) throws Exception {
        File r = racine(c);
        if (r == null || !pret(c)) return;
        File d = new File(r, "donnees");
        d.mkdirs();
        for (String nom : PREFS) {
            Map<String, ?> all = c.getSharedPreferences(nom, 0).getAll();
            if (all.isEmpty()) continue; // ne jamais écraser une sauvegarde par du vide
            JSONObject o = new JSONObject();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                Object v = e.getValue();
                JSONObject t = new JSONObject();
                if (v instanceof String) t.put("t", "s").put("v", v);
                else if (v instanceof Integer) t.put("t", "i").put("v", v);
                else if (v instanceof Long) t.put("t", "l").put("v", v);
                else if (v instanceof Float) t.put("t", "f").put("v", ((Float) v).doubleValue());
                else if (v instanceof Boolean) t.put("t", "b").put("v", v);
                else if (v instanceof Set) t.put("t", "set").put("v", new JSONArray((Set<?>) v));
                else continue;
                o.put(e.getKey(), t);
            }
            String s = o.toString();
            Integer h = dernier.get(nom);
            if (h != null && h == s.hashCode()) continue;
            ecrire(new File(d, nom + ".json"), s);
            dernier.put(nom, s.hashCode());
        }
    }

    /** Remet dans l'application ce qui manque (jamais d'écrasement de ce qui existe déjà). */
    private static void restaurer(Context c, File r) {
        File d = new File(r, "donnees");
        for (String nom : PREFS) {
            File f = new File(d, nom + ".json");
            if (!f.exists()) continue;
            try {
                JSONObject o = new JSONObject(lire(f));
                SharedPreferences sp = c.getSharedPreferences(nom, 0);
                SharedPreferences.Editor ed = sp.edit();
                for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String k = it.next();
                    if (sp.contains(k)) continue;
                    JSONObject t = o.getJSONObject(k);
                    switch (t.getString("t")) {
                        case "s": ed.putString(k, t.getString("v")); break;
                        case "i": ed.putInt(k, t.getInt("v")); break;
                        case "l": ed.putLong(k, t.getLong("v")); break;
                        case "f": ed.putFloat(k, (float) t.getDouble("v")); break;
                        case "b": ed.putBoolean(k, t.getBoolean("v")); break;
                        case "set":
                            JSONArray a = t.getJSONArray("v");
                            Set<String> st = new HashSet<>();
                            for (int i = 0; i < a.length(); i++) st.add(a.getString(i));
                            ed.putStringSet(k, st);
                            break;
                        default: break;
                    }
                }
                ed.commit();
            } catch (Exception e) { }
        }
    }
}
