package com.tvkouame.studio;

import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;

// Génération de vidéos par IA GRATUITES avec relais automatique :
// Pollinations (plusieurs modèles) -> Spaces Hugging Face (liste fixe + découverte) -> ...
// Si une IA est épuisée ou en panne, la suivante prend le relais. Puis assemblage en documentaire.
class BibleVideo {
    static final int CHOIX_IMAGE = 4242;
    static final String[] MODELES_POLL = {"seedance", "wan", "grok-video"};
    static final String[] SPACES = {
        "NitinBot001/Text_Image-to-Video",
        "Lightricks/ltx-video-distilled",
        "multimodalart/wan2-1-fast",
        "Wan-AI/Wan2.2-5B"
    };
    static final int MAX_SPACES = 8;

    static byte[] imageOctets = null; // JPEG de la photo de visage
    static EditText champPrompt;
    static TextView etatImage;

    interface Etat { void maj(String s); }
    static class PasDePhoto extends Exception { PasDePhoto() { super("pas d'entrée photo"); } }
    static class Resultat { File fichier; String source; boolean photo; }

    static SharedPreferences sp(BibleActivity a) { return a.getSharedPreferences("video", 0); }

    static JSONArray videos(BibleActivity a) {
        try { return new JSONArray(sp(a).getString("liste", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    static void sauver(BibleActivity a, JSONArray l) { sp(a).edit().putString("liste", l.toString()).apply(); }

    static void ecran(BibleActivity a) { ecran(a, ""); }

    static void ecran(BibleActivity a, String prefill) {
        a.ecran("🎬 Générer des vidéos");
        a.t("Décrivez la scène. L'appli essaie les IA gratuites une par une : si l'une est épuisée ou en panne, la suivante prend le relais (compter 2 à 10 minutes). Ajoutez une photo pour essayer de garder le même visage.", 14, false);

        champPrompt = new EditText(a);
        champPrompt.setHint("Ex : Moïse lève son bâton devant la mer Rouge qui s'ouvre, lumière dorée, style cinéma");
        champPrompt.setMinLines(3);
        champPrompt.setText(prefill);
        a.champ(champPrompt);

        etatImage = a.t(imageOctets == null ? "Aucune photo de visage choisie" : "✅ Photo de visage prête", 14, false);
        a.b("🖼️ Choisir une photo (visage)", () -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            a.startActivityForResult(Intent.createChooser(i, "Choisir une photo"), CHOIX_IMAGE);
        });
        if (imageOctets != null) a.b("✖ Retirer la photo", () -> { imageOctets = null; ecran(a, champPrompt.getText().toString()); });

        LinearLayout res = new LinearLayout(a);
        res.setOrientation(LinearLayout.VERTICAL);
        a.b("✨ GÉNÉRER LA VIDÉO", () -> {
            String p = champPrompt.getText().toString().trim();
            if (p.isEmpty()) { Toast.makeText(a, "Écrivez d'abord le prompt", Toast.LENGTH_SHORT).show(); return; }
            if (!a.online()) { Toast.makeText(a, "Pas de connexion Internet", Toast.LENGTH_SHORT).show(); return; }
            generer(a, res, p);
        });
        a.box.addView(res);

        JSONArray l = videos(a);
        a.t("Mon documentaire (" + l.length() + " vidéo" + (l.length() > 1 ? "s" : "") + ")", 18, true);
        if (l.length() > 0) {
            String dernier = l.optJSONObject(l.length() - 1).optString("prompt");
            a.t("Prompts pour continuer l'histoire :", 14, false);
            String[] suites = {
                "Suite directe de la scène précédente, mêmes personnages, même décor : " + dernier,
                "Plan suivant du documentaire : gros plan sur le visage du personnage principal qui réagit à ce qui vient de se passer. Contexte : " + dernier,
                "Transition de documentaire : vue aérienne lente du paysage biblique, puis retour vers les personnages. Contexte : " + dernier,
                "Scène finale du documentaire : le personnage principal regarde au loin, coucher de soleil, ambiance paisible. Contexte : " + dernier };
            String[] noms = {"➡️ Suite directe", "🎭 Gros plan / réaction", "🌄 Transition paysage", "🏁 Scène finale"};
            for (int i = 0; i < suites.length; i++) { final String s = suites[i]; a.b(noms[i], () -> ecran(a, s)); }
            for (int i = 0; i < l.length(); i++) {
                JSONObject v = l.optJSONObject(i);
                String src = v.optString("source");
                a.b("▶️ Vidéo " + (i + 1) + " : " + court(v.optString("prompt")) + (src.isEmpty() ? "" : " (" + src + ")"), () -> lire(a, new File(v.optString("fichier"))));
            }
            a.b("🎞️ ASSEMBLER LE DOCUMENTAIRE", () -> assembler(a));
            a.b("🗑️ Supprimer la dernière vidéo", () -> {
                JSONArray m = videos(a);
                JSONObject der = m.optJSONObject(m.length() - 1);
                if (der != null) new File(der.optString("fichier")).delete();
                m.remove(m.length() - 1); sauver(a, m); ecran(a);
            });
        } else a.t("Les vidéos générées s'ajouteront ici, dans l'ordre. Vous pourrez ensuite les assembler en un seul documentaire.", 14, false);

        boolean cp = !sp(a).getString("cle_poll", "").isEmpty();
        boolean ch = !sp(a).getString("hf", "").isEmpty();
        a.b("🔑 " + (cp ? "Changer" : "Ajouter") + " ma clé Pollinations (gratuite)", () -> demander(a, "cle_poll", "Clé Pollinations",
            "Créez un compte gratuit sur enter.pollinations.ai et copiez votre clé (sk_...). Sans clé, Pollinations est ignoré et l'appli utilise seulement Hugging Face.", "sk_..."));
        a.b("🔑 " + (ch ? "Changer" : "Ajouter") + " mon jeton Hugging Face (gratuit, optionnel)", () -> demander(a, "hf", "Jeton Hugging Face",
            "Optionnel mais conseillé : un compte gratuit sur huggingface.co (Settings → Access Tokens, droit « read ») augmente le quota gratuit de GPU.", "hf_..."));
    }

    static String court(String s) { return s.length() > 40 ? s.substring(0, 40) + "…" : s; }

    static void demander(BibleActivity a, String pref, String titre, String message, String hint) {
        EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setHint(hint);
        new AlertDialog.Builder(a).setTitle(titre).setMessage(message).setView(e)
            .setPositiveButton("Enregistrer", (d, w) -> { sp(a).edit().putString(pref, e.getText().toString().trim()).apply(); ecran(a, champPrompt == null ? "" : champPrompt.getText().toString()); })
            .setNegativeButton("Annuler", null).show();
    }

    static void imageChoisie(BibleActivity a, Uri u) {
        new Thread(() -> {
            try {
                InputStream in = a.getContentResolver().openInputStream(u);
                Bitmap bm = BitmapFactory.decodeStream(in);
                in.close();
                int max = 768, w = bm.getWidth(), h = bm.getHeight();
                if (Math.max(w, h) > max) { float f = max / (float) Math.max(w, h); bm = Bitmap.createScaledBitmap(bm, (int) (w * f), (int) (h * f), true); }
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                bm.compress(Bitmap.CompressFormat.JPEG, 88, o);
                imageOctets = o.toByteArray();
                a.runOnUiThread(() -> ecran(a, champPrompt == null ? "" : champPrompt.getText().toString()));
            } catch (Exception e) {
                a.runOnUiThread(() -> Toast.makeText(a, "Impossible de lire cette photo", Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ---------- Réseau ----------

    static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); }

    static HttpURLConnection ouvrir(String url, String methode, String hf, int lecture) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(methode);
        c.setConnectTimeout(20000);
        c.setReadTimeout(lecture);
        String h = new URL(url).getHost();
        if (!hf.isEmpty() && (h.endsWith(".hf.space") || h.equals("huggingface.co"))) c.setRequestProperty("Authorization", "Bearer " + hf);
        return c;
    }

    static String corps(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
        StringBuilder b = new StringBuilder();
        if (is != null) {
            BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String l;
            while ((l = r.readLine()) != null) b.append(l);
            r.close();
        }
        if (code >= 400) { String s = b.toString(); throw new Exception("erreur " + code + (s.isEmpty() ? "" : " " + (s.length() > 120 ? s.substring(0, 120) : s))); }
        return b.toString();
    }

    static String get(String url, String hf, int lecture) throws Exception { return corps(ouvrir(url, "GET", hf, lecture)); }

    static String post(String url, String json, String hf) throws Exception {
        HttpURLConnection c = ouvrir(url, "POST", hf, 60000);
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        OutputStream o = c.getOutputStream();
        o.write(json.getBytes("UTF-8"));
        o.close();
        return corps(c);
    }

    static String multipart(String url, String champ, byte[] data, String nom, String type, String jeton, boolean bearerHf, String hf) throws Exception {
        String b = "----bible" + System.currentTimeMillis();
        HttpURLConnection c = ouvrir(url, "POST", hf, 60000);
        if (!jeton.isEmpty() && !bearerHf) c.setRequestProperty("Authorization", "Bearer " + jeton);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + b);
        c.setDoOutput(true);
        OutputStream o = c.getOutputStream();
        o.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"" + champ + "\"; filename=\"" + nom + "\"\r\nContent-Type: " + type + "\r\n\r\n").getBytes("UTF-8"));
        o.write(data);
        o.write(("\r\n--" + b + "--\r\n").getBytes("UTF-8"));
        o.close();
        return corps(c);
    }

    static File nouveauFichier(BibleActivity a) {
        File dir = new File(a.getFilesDir(), "videos");
        dir.mkdirs();
        return new File(dir, "v" + System.currentTimeMillis() + ".mp4");
    }

    static void telecharger(String url, File f, String hf, String jeton) throws Exception {
        HttpURLConnection c = ouvrir(url, "GET", hf, 240000);
        if (!jeton.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + jeton);
        int code = c.getResponseCode();
        if (code >= 400) throw new Exception("téléchargement : erreur " + code);
        String type = c.getContentType();
        if (type != null && (type.startsWith("text/") || type.contains("json"))) throw new Exception("réponse sans vidéo");
        InputStream in = c.getInputStream();
        FileOutputStream o = new FileOutputStream(f);
        byte[] b = new byte[65536]; int n;
        try { while ((n = in.read(b)) > 0) o.write(b, 0, n); } finally { o.close(); in.close(); }
        if (f.length() < 20000) { f.delete(); throw new Exception("fichier vidéo invalide"); }
    }

    // ---------- Fournisseur 1 : Pollinations ----------

    static String televerserPoll(String cle, byte[] jpg) throws Exception {
        String r = multipart("https://media.pollinations.ai/upload", "file", jpg, "visage.jpg", "image/jpeg", cle, false, "");
        String u = new JSONObject(r).optString("url", "");
        if (u.isEmpty()) throw new Exception("pas d'URL renvoyée");
        return u;
    }

    static File pollinations(BibleActivity a, String prompt, String modele, String urlPhoto, String cle) throws Exception {
        String p = prompt.length() > 900 ? prompt.substring(0, 900) : prompt;
        String url = "https://gen.pollinations.ai/video/" + enc(p) + "?model=" + enc(modele) + "&duration=5&aspectRatio=16:9";
        if (urlPhoto != null) url += "&image=" + enc(urlPhoto);
        File f = nouveauFichier(a);
        try { telecharger(url, f, "", cle); } catch (Exception e) { f.delete(); throw e; }
        return f;
    }

    // ---------- Fournisseur 2 : Spaces Hugging Face (API Gradio) ----------

    static List<String> decouvrir(String hf) {
        List<String> l = new ArrayList<>();
        try {
            String r = get("https://huggingface.co/api/spaces/semantic-search?q=" + enc("text to video generation") + "&sdk=gradio", hf, 20000);
            JSONArray t = new JSONArray(r);
            for (int i = 0; i < t.length(); i++) {
                JSONObject s = t.optJSONObject(i);
                if (s == null) continue;
                String id = s.optString("id", "");
                if (id.isEmpty()) continue;
                JSONObject rt = s.optJSONObject("runtime");
                if (rt != null && !"RUNNING".equals(rt.optString("stage", "RUNNING"))) continue;
                l.add(id);
                if (l.size() >= 6) break;
            }
        } catch (Exception e) { }
        return l;
    }

    static String trouverUrl(Object o, String base, String pref) {
        if (o instanceof JSONArray) {
            JSONArray t = (JSONArray) o;
            for (int i = 0; i < t.length(); i++) { String u = trouverUrl(t.opt(i), base, pref); if (u != null) return u; }
        } else if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            String u = j.optString("url", "");
            if (!u.isEmpty() && !u.equals("null")) return u;
            String p = j.optString("path", "");
            if (!p.isEmpty() && !p.equals("null")) return base + pref + "/file=" + p;
            Iterator<String> it = j.keys();
            while (it.hasNext()) { String r = trouverUrl(j.opt(it.next()), base, pref); if (r != null) return r; }
        }
        return null;
    }

    static File gradio(BibleActivity a, String id, boolean utiliserPhoto, String prompt, boolean[] photoUtilisee, long fin) throws Exception {
        String hf = sp(a).getString("hf", "");
        String sub = id.toLowerCase(Locale.ROOT).replace("/", "-").replace("_", "-").replace(".", "-");
        String base = "https://" + sub + ".hf.space";
        String pref = "/gradio_api";
        String info;
        try { info = get(base + pref + "/info", hf, 30000); }
        catch (Exception e) { pref = ""; info = get(base + "/info", hf, 30000); }

        JSONObject ne = new JSONObject(info).optJSONObject("named_endpoints");
        if (ne == null) throw new Exception("pas d'API");
        String choisi = null; int meilleur = -1;
        Iterator<String> it = ne.keys();
        while (it.hasNext()) {
            String nom = it.next();
            JSONObject e = ne.getJSONObject(nom);
            JSONArray ret = e.optJSONArray("returns");
            boolean video = false;
            if (ret != null) for (int i = 0; i < ret.length(); i++) {
                JSONObject r = ret.optJSONObject(i);
                if (r != null && r.optString("component", "").equalsIgnoreCase("video")) video = true;
            }
            if (!video) continue;
            int score = (nom.contains("generat") ? 2 : 0) + (nom.contains("video") ? 1 : 0);
            if (score > meilleur) { meilleur = score; choisi = nom; }
        }
        if (choisi == null) throw new Exception("aucune sortie vidéo");

        JSONArray params = ne.getJSONObject(choisi).getJSONArray("parameters");
        boolean aImage = false;
        for (int i = 0; i < params.length(); i++) if (params.getJSONObject(i).optString("component", "").equalsIgnoreCase("image")) aImage = true;
        if (utiliserPhoto && !aImage) throw new PasDePhoto();

        JSONArray data = new JSONArray();
        boolean promptMis = false, photoMise = false;
        JSONObject fichierPhoto = null;
        for (int i = 0; i < params.length(); i++) {
            JSONObject p = params.getJSONObject(i);
            String cl = p.optString("component", "").toLowerCase(Locale.ROOT);
            boolean aDefaut = p.optBoolean("parameter_has_default", false);
            Object defaut = p.opt("parameter_default");
            if (defaut == null) defaut = JSONObject.NULL;
            if (cl.equals("textbox") && !promptMis) {
                data.put(prompt); promptMis = true;
            } else if (cl.equals("image") && utiliserPhoto && !photoMise) {
                if (fichierPhoto == null) {
                    String r = multipart(base + pref + "/upload", "files", imageOctets, "visage.jpg", "image/jpeg", "", true, hf);
                    JSONArray chemins = new JSONArray(r);
                    fichierPhoto = new JSONObject().put("path", chemins.getString(0)).put("meta", new JSONObject().put("_type", "gradio.FileData"));
                }
                data.put(fichierPhoto); photoMise = true;
            } else if (aDefaut) {
                data.put(defaut);
            } else {
                throw new Exception("paramètre obligatoire inconnu : " + p.optString("label", cl));
            }
        }
        if (!promptMis) throw new Exception("pas de champ texte");
        photoUtilisee[0] = photoMise;

        String nomApi = choisi.startsWith("/") ? choisi.substring(1) : choisi;
        String rep = post(base + pref + "/call/" + nomApi, new JSONObject().put("data", data).toString(), hf);
        String eid = new JSONObject(rep).getString("event_id");

        HttpURLConnection c = ouvrir(base + pref + "/call/" + nomApi + "/" + eid, "GET", hf, 60000);
        if (c.getResponseCode() >= 400) throw new Exception("suivi : erreur " + c.getResponseCode());
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        String ligne, evt = "", resultat = null;
        try {
            while ((ligne = r.readLine()) != null) {
                if (System.currentTimeMillis() > fin) throw new Exception("trop long");
                if (ligne.startsWith("event:")) evt = ligne.substring(6).trim();
                else if (ligne.startsWith("data:")) {
                    String d = ligne.substring(5).trim();
                    if (evt.equals("complete")) { resultat = d; break; }
                    if (evt.equals("error")) throw new Exception("refusé par le Space (" + (d.length() > 100 ? d.substring(0, 100) : d) + ")");
                }
            }
        } finally { r.close(); }
        if (resultat == null) throw new Exception("aucun résultat");
        String url = trouverUrl(new JSONArray(resultat), base, pref);
        if (url == null) throw new Exception("pas de vidéo dans la réponse");
        File f = nouveauFichier(a);
        try { telecharger(url, f, hf, ""); } catch (Exception e) { f.delete(); throw e; }
        return f;
    }

    // ---------- La chaîne de relais ----------

    static Resultat resultat(File f, String source, boolean photo) {
        Resultat r = new Resultat(); r.fichier = f; r.source = source; r.photo = photo; return r;
    }

    static Resultat chaine(BibleActivity a, String prompt, Etat etat) throws Exception {
        boolean aPhoto = imageOctets != null;
        String cle = sp(a).getString("cle_poll", "");
        String hf = sp(a).getString("hf", "");
        List<String> echecs = new ArrayList<>();
        List<String> spaces = new ArrayList<>(Arrays.asList(SPACES));
        boolean decouvert = false;
        HashSet<String> dejaEchoues = new HashSet<>();
        int essais = 0;

        for (int phase = 0; phase < (aPhoto ? 2 : 1); phase++) {
            boolean avecPhoto = aPhoto && phase == 0;

            // 1) Pollinations (tous les modèles, l'un après l'autre)
            if (!cle.isEmpty()) {
                String urlPhoto = null;
                if (avecPhoto) {
                    etat.maj("Envoi de la photo à Pollinations…");
                    try { urlPhoto = televerserPoll(cle, imageOctets); }
                    catch (Exception e) { echecs.add("Photo Pollinations : " + e.getMessage()); }
                }
                if (!avecPhoto || urlPhoto != null) {
                    for (String m : MODELES_POLL) {
                        etat.maj("Essai : Pollinations (" + m + ")…");
                        try { return resultat(pollinations(a, prompt, m, urlPhoto, cle), "Pollinations " + m, urlPhoto != null); }
                        catch (Exception e) { echecs.add("Pollinations " + m + " : " + e.getMessage()); }
                    }
                }
            } else if (phase == 0) echecs.add("Pollinations : pas de clé");

            // 2) Spaces Hugging Face (liste fixe puis IA découvertes)
            for (int i = 0; i < spaces.size() || !decouvert; i++) {
                if (i >= spaces.size()) {
                    decouvert = true;
                    etat.maj("Recherche d'autres IA gratuites…");
                    for (String id : decouvrir(hf)) {
                        boolean connu = false;
                        for (String s : spaces) if (s.equalsIgnoreCase(id)) connu = true;
                        if (!connu) spaces.add(id);
                    }
                    if (i >= spaces.size()) break;
                }
                if (essais >= MAX_SPACES) break;
                String id = spaces.get(i);
                if (dejaEchoues.contains(id.toLowerCase(Locale.ROOT))) continue;
                etat.maj("Essai : " + id + " (Hugging Face)…");
                try {
                    boolean[] ph = new boolean[1];
                    File f = gradio(a, id, avecPhoto, prompt, ph, System.currentTimeMillis() + 6 * 60 * 1000);
                    return resultat(f, "Hugging Face " + id, ph[0]);
                } catch (PasDePhoto e) {
                    // pas de photo possible ici : sera réessayé à la 2e passe
                } catch (Exception e) {
                    essais++;
                    dejaEchoues.add(id.toLowerCase(Locale.ROOT));
                    echecs.add(id + " : " + e.getMessage());
                }
            }
        }
        StringBuilder sb = new StringBuilder("Toutes les IA gratuites ont échoué ou sont épuisées pour le moment. Réessayez plus tard.\n");
        for (int i = Math.max(0, echecs.size() - 8); i < echecs.size(); i++) sb.append("• ").append(echecs.get(i)).append("\n");
        throw new Exception(sb.toString().trim());
    }

    static void generer(BibleActivity a, LinearLayout res, String prompt) {
        res.removeAllViews();
        a.progression(res, new String[]{"Recherche d'une IA gratuite disponible…", "L'IA dessine les images…", "Animation de la scène…", "Encore un peu de patience, la vidéo se termine…"});
        TextView st = new TextView(a);
        st.setTextSize(13);
        st.setTextColor(0xFF5A5478);
        st.setPadding(a.dp(4), 0, a.dp(4), a.dp(8));
        res.addView(st);
        final boolean aPhoto = imageOctets != null;
        new Thread(() -> {
            try {
                Resultat r = chaine(a, prompt, s -> a.runOnUiThread(() -> st.setText(s)));
                JSONArray l = videos(a);
                l.put(new JSONObject().put("prompt", prompt).put("fichier", r.fichier.getAbsolutePath()).put("source", r.source));
                sauver(a, l);
                String msg = "Vidéo prête (" + r.source + ") ✅" + (aPhoto && !r.photo ? "\nLa photo n'a pas pu être utilisée par cette IA." : "");
                a.runOnUiThread(() -> { Toast.makeText(a, msg, Toast.LENGTH_LONG).show(); ecran(a); lire(a, r.fichier); });
            } catch (Exception e) {
                a.runOnUiThread(() -> { res.removeAllViews(); TextView t = new TextView(a); t.setText("❌ " + e.getMessage()); a.carte(t); res.addView(t); });
            }
        }).start();
    }

    // ---------- Lecture, enregistrement ----------

    static void lire(BibleActivity a, File f) {
        if (!f.exists()) { Toast.makeText(a, "Fichier introuvable", Toast.LENGTH_SHORT).show(); return; }
        VideoView vv = new VideoView(a);
        vv.setVideoURI(Uri.fromFile(f));
        vv.setMediaController(new MediaController(a));
        FrameLayout fl = new FrameLayout(a);
        fl.addView(vv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(420)));
        AlertDialog d = new AlertDialog.Builder(a).setView(fl)
            .setPositiveButton("💾 Enregistrer dans la galerie", (x, w) -> enregistrer(a, f))
            .setNegativeButton("Fermer", null).create();
        d.setOnShowListener(x -> vv.start());
        d.show();
    }

    static void enregistrer(BibleActivity a, File f) {
        try {
            OutputStream o;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Video.Media.DISPLAY_NAME, f.getName());
                cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Academy biblique");
                Uri u = a.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
                o = a.getContentResolver().openOutputStream(u);
            } else {
                File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Academy biblique");
                d.mkdirs();
                o = new FileOutputStream(new File(d, f.getName()));
            }
            InputStream in = new FileInputStream(f);
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            o.close(); in.close();
            Toast.makeText(a, "Enregistrée dans Films / Academy biblique", Toast.LENGTH_LONG).show();
        } catch (Exception e) { Toast.makeText(a, "Échec de l'enregistrement : " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    // ---------- Assemblage ----------

    static boolean memeVideo(MediaFormat x, MediaFormat y) {
        try {
            return x.getString(MediaFormat.KEY_MIME).equals(y.getString(MediaFormat.KEY_MIME))
                && x.getInteger(MediaFormat.KEY_WIDTH) == y.getInteger(MediaFormat.KEY_WIDTH)
                && x.getInteger(MediaFormat.KEY_HEIGHT) == y.getInteger(MediaFormat.KEY_HEIGHT);
        } catch (Exception e) { return false; }
    }

    static boolean memeAudio(MediaFormat x, MediaFormat y) {
        try {
            return x.getString(MediaFormat.KEY_MIME).equals(y.getString(MediaFormat.KEY_MIME))
                && x.getInteger(MediaFormat.KEY_SAMPLE_RATE) == y.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                && x.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == y.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        } catch (Exception e) { return false; }
    }

    // Colle les vidéos bout à bout dans un seul MP4 (sans réencodage).
    // Les vidéos d'un format différent de la première (IA différente) sont ignorées.
    static void assembler(BibleActivity a) {
        JSONArray l = videos(a);
        if (l.length() < 2) { Toast.makeText(a, "Il faut au moins 2 vidéos", Toast.LENGTH_SHORT).show(); return; }
        Toast.makeText(a, "Assemblage du documentaire…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File out = new File(new File(a.getFilesDir(), "videos"), "documentaire_" + System.currentTimeMillis() + ".mp4");
                MediaMuxer mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                int pisteV = -1, pisteA = -1, ignorees = 0, utilisees = 0;
                MediaFormat fmV0 = null, fmA0 = null;
                long decal = 0;
                boolean demarre = false;
                ByteBuffer buf = ByteBuffer.allocate(4 * 1024 * 1024);
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                for (int i = 0; i < l.length(); i++) {
                    String chemin = l.getJSONObject(i).getString("fichier");
                    MediaExtractor ex = new MediaExtractor();
                    ex.setDataSource(chemin);
                    int tv = -1, ta = -1;
                    MediaFormat fv = null, fa = null;
                    for (int t = 0; t < ex.getTrackCount(); t++) {
                        MediaFormat fm = ex.getTrackFormat(t);
                        String mime = fm.getString(MediaFormat.KEY_MIME);
                        if (mime.startsWith("video/") && tv < 0) { tv = t; fv = fm; }
                        else if (mime.startsWith("audio/") && ta < 0) { ta = t; fa = fm; }
                    }
                    if (tv < 0) { ex.release(); ignorees++; continue; }
                    if (!demarre) {
                        fmV0 = fv; fmA0 = fa;
                        pisteV = mux.addTrack(fv);
                        if (fa != null) pisteA = mux.addTrack(fa);
                        mux.start();
                        demarre = true;
                    } else {
                        if (!memeVideo(fmV0, fv)) { ex.release(); ignorees++; continue; }
                        if (fa != null && (pisteA < 0 || !memeAudio(fmA0, fa))) ta = -1;
                    }
                    utilisees++;
                    Map<Integer, Integer> map = new HashMap<>();
                    map.put(tv, pisteV);
                    ex.selectTrack(tv);
                    if (ta >= 0 && pisteA >= 0) { map.put(ta, pisteA); ex.selectTrack(ta); }
                    long maxT = 0;
                    while (true) {
                        int n = ex.readSampleData(buf, 0);
                        if (n < 0) break;
                        Integer dest = map.get(ex.getSampleTrackIndex());
                        if (dest != null) {
                            info.offset = 0; info.size = n;
                            info.presentationTimeUs = ex.getSampleTime() + decal;
                            info.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                            mux.writeSampleData(dest, buf, info);
                            maxT = Math.max(maxT, ex.getSampleTime());
                        }
                        ex.advance();
                    }
                    decal += maxT + 40000;
                    ex.release();
                }
                if (!demarre) throw new Exception("aucune vidéo lisible");
                mux.stop(); mux.release();
                final int ign = ignorees, uti = utilisees;
                a.runOnUiThread(() -> {
                    Toast.makeText(a, "Documentaire prêt 🎞️ (" + uti + " vidéo" + (uti > 1 ? "s" : "") + ")" + (ign > 0 ? "\n" + ign + " vidéo(s) ignorée(s) : format différent, impossible à coller sans réencodage" : ""), Toast.LENGTH_LONG).show();
                    lire(a, out);
                });
            } catch (Exception e) {
                a.runOnUiThread(() -> Toast.makeText(a, "Assemblage impossible : " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }
}
