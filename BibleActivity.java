package com.tvkouame.studio;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.graphics.Color;
import android.net.*;
import android.os.Bundle;
import android.text.*;
import android.widget.*;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.*;

public class BibleActivity extends Activity {
    // Sujets : WideBible (gratuit, sans clé). Commentaires développés : HelloAO (gratuit, sans clé, sans quota).
    // Traduction anglais -> français : ML Kit (gratuit, sur le téléphone, sans limite).
    static final String API = "https://widebible.com/api/v1/bible/";
    static final String HELLO = "https://bible.helloao.org/api/c/";
    static final String[] LIVRES = ("Genèse,Exode,Lévitique,Nombres,Deutéronome,Josué,Juges,Ruth,1 Samuel,2 Samuel,1 Rois,2 Rois,"
        + "1 Chroniques,2 Chroniques,Esdras,Néhémie,Esther,Job,Psaumes,Proverbes,Ecclésiaste,Cantique des Cantiques,Ésaïe,Jérémie,"
        + "Lamentations,Ézéchiel,Daniel,Osée,Joël,Amos,Abdias,Jonas,Michée,Nahum,Habacuc,Sophonie,Aggée,Zacharie,Malachie,Matthieu,"
        + "Marc,Luc,Jean,Actes,Romains,1 Corinthiens,2 Corinthiens,Galates,Éphésiens,Philippiens,Colossiens,1 Thessaloniciens,"
        + "2 Thessaloniciens,1 Timothée,2 Timothée,Tite,Philémon,Hébreux,Jacques,1 Pierre,2 Pierre,1 Jean,2 Jean,3 Jean,Jude,Apocalypse").split(",");
    static final String[] ANG = ("Genesis,Exodus,Leviticus,Numbers,Deuteronomy,Joshua,Judges,Ruth,1 Samuel,2 Samuel,1 Kings,2 Kings,"
        + "1 Chronicles,2 Chronicles,Ezra,Nehemiah,Esther,Job,Psalms,Proverbs,Ecclesiastes,Song of Solomon,Isaiah,Jeremiah,"
        + "Lamentations,Ezekiel,Daniel,Hosea,Joel,Amos,Obadiah,Jonah,Micah,Nahum,Habakkuk,Zephaniah,Haggai,Zechariah,Malachi,Matthew,"
        + "Mark,Luke,John,Acts,Romans,1 Corinthians,2 Corinthians,Galatians,Ephesians,Philippians,Colossians,1 Thessalonians,"
        + "2 Thessalonians,1 Timothy,2 Timothy,Titus,Philemon,Hebrews,James,1 Peter,2 Peter,1 John,2 John,3 John,Jude,Revelation").split(",");
    static final String[] CODES = ("GEN,EXO,LEV,NUM,DEU,JOS,JDG,RUT,1SA,2SA,1KI,2KI,1CH,2CH,EZR,NEH,EST,JOB,PSA,PRO,ECC,SNG,ISA,JER,LAM,EZK,DAN,"
        + "HOS,JOL,AMO,OBA,JON,MIC,NAM,HAB,ZEP,HAG,ZEC,MAL,MAT,MRK,LUK,JHN,ACT,ROM,1CO,2CO,GAL,EPH,PHP,COL,1TH,2TH,1TI,2TI,TIT,PHM,"
        + "HEB,JAS,1PE,2PE,1JN,2JN,3JN,JUD,REV").split(",");

    interface Ouvrir { void go(JSONObject o); }

    SharedPreferences sp;
    LinearLayout box;
    Translator tx;
    boolean home = true;
    boolean echec = false;
    volatile boolean maj = false;

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        sp = getSharedPreferences("b", 0);
        try {
            ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).registerNetworkCallback(
                new NetworkRequest.Builder().build(),
                new ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(Network n) { majEnLigne(); }
                });
        } catch (Exception x) { }
        accueil();
        majEnLigne();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == BibleVideo.CHOIX_IMAGE && res == RESULT_OK && data != null && data.getData() != null) BibleVideo.imageChoisie(this, data.getData());
    }

    @Override public void onBackPressed() {
        if (!home) accueil(); else super.onBackPressed();
    }

    // ---------- Réseau ----------
    boolean online() {
        ConnectivityManager m = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo n = m.getActiveNetworkInfo();
        return n != null && n.isConnected();
    }

    String get(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(30000);
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder b = new StringBuilder();
        String l;
        while ((l = r.readLine()) != null) b.append(l).append('\n');
        return b.toString();
    }

    // ---------- Traduction anglais -> français (à appeler hors du fil principal) ----------
    String tr(String en) {
        if (en == null || en.trim().isEmpty()) return "";
        en = en.trim();
        String k = "tr_" + en.hashCode();
        String c = sp.getString(k, null);
        if (c != null) return c;
        try {
            if (tx == null) tx = Translation.getClient(new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.FRENCH).build());
            Tasks.await(tx.downloadModelIfNeeded(new DownloadConditions.Builder().build()));
            String t = Tasks.await(tx.translate(en));
            sp.edit().putString(k, t).apply();
            return t;
        } catch (Exception e) { return null; }
    }

    // Texte long : traduit par morceaux. null = échec
    String trLong(String en) {
        StringBuilder out = new StringBuilder();
        for (String p : en.split("\n")) {
            if (p.trim().isEmpty()) { out.append("\n"); continue; }
            StringBuilder cur = new StringBuilder();
            for (String ph : p.split("(?<=[.!?;:]) ")) {
                if (cur.length() + ph.length() > 700 && cur.length() > 0) {
                    String r = tr(cur.toString());
                    if (r == null) return null;
                    out.append(r).append(" ");
                    cur.setLength(0);
                }
                cur.append(ph).append(" ");
            }
            if (cur.length() > 0) { String r = tr(cur.toString()); if (r == null) return null; out.append(r); }
            out.append("\n");
        }
        return out.toString().trim();
    }

    String trad(String en) {
        String r = trLong(en);
        if (r == null) { echec = true; return en; }
        return r;
    }

    JSONArray arr(String cle) {
        try { return new JSONArray(sp.getString(cle, "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    // ---------- Téléchargement automatique dès qu'Internet est disponible ----------
    void majEnLigne() {
        if (maj || !online()) return;
        maj = true;
        new Thread(() -> {
            try {
                liste("topics", "topics/", "slug", 40, false);
                liste("glossary", "glossary/", "term", 10, true);
                noms("topics", 100000);
                noms("glossary", 100000);
            } catch (Exception e) { }
            maj = false;
        }).start();
    }

    void liste(String cle, String chemin, String id, int pages, boolean def) throws Exception {
        Map<String, String> anc = new HashMap<>();
        JSONArray old = arr(cle);
        for (int i = 0; i < old.length(); i++) anc.put(old.getJSONObject(i).optString("id"), old.getJSONObject(i).optString("fr", ""));
        JSONArray n = new JSONArray();
        String u = API + chemin;
        for (int p = 0; u != null && p < pages; p++) {
            JSONObject j = new JSONObject(get(u));
            JSONArray r = j.getJSONArray("results");
            for (int i = 0; i < r.length(); i++) {
                JSONObject x = r.getJSONObject(i);
                String nom = x.optString("name", x.optString("term", x.optString(id)));
                String ident = x.optString(id, nom);
                if (nom.isEmpty()) continue;
                JSONObject o = new JSONObject().put("id", ident).put("en", nom);
                if (def) o.put("def", x.optString("definition", x.optString("description", "")));
                String f = anc.get(ident);
                if (f != null && !f.isEmpty()) o.put("fr", f);
                n.put(o);
            }
            u = j.isNull("next") ? null : j.optString("next", null);
        }
        if (n.length() > 0) sp.edit().putString(cle, n.toString()).apply();
    }

    void noms(String cle, int max) throws Exception {
        JSONArray a = arr(cle);
        int k = 0;
        for (int i = 0; i < a.length() && k < max; i++) {
            JSONObject o = a.getJSONObject(i);
            if (o.has("fr")) continue;
            String r = tr(o.getString("en"));
            if (r == null) break;
            o.put("fr", r);
            k++;
            if (k % 20 == 0) sp.edit().putString(cle, a.toString()).apply();
        }
        sp.edit().putString(cle, a.toString()).apply();
    }

    // ---------- Interface (design : dégradés, cartes, boutons colorés animés) ----------
    static final int[][] PAL = {
        {0xFF6A11CB, 0xFF2575FC}, {0xFFFF512F, 0xFFF09819}, {0xFF11998E, 0xFF38C172}, {0xFFE91E63, 0xFFFF6F61},
        {0xFF8E2DE2, 0xFF4A00E0}, {0xFF0083B0, 0xFF00B4DB}, {0xFFD38312, 0xFFA83279}
    };
    // Polices : boutons modernes (sans-serif medium), textes et titres en serif (confort de lecture)
    static final Typeface POLICE_BOUTON = Typeface.create("sans-serif-medium", Typeface.BOLD);
    static final Typeface POLICE_TEXTE = Typeface.create("serif", Typeface.NORMAL);
    static final Typeface POLICE_TITRE = Typeface.create("serif", Typeface.BOLD);
    static final String WHATSAPP = "2290143914249";
    int cpt = 0;
    boolean occupe = false;
    LinearLayout head;
    final Handler hd = new Handler(Looper.getMainLooper());

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    void ecran(String titre) { ecran(titre, false); }

    void ecran(String titre, boolean accueil) {
        home = false;
        cpt = 0;
        try { getWindow().setStatusBarColor(0xFF1B1F4B); } catch (Exception e) { }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFFFFF8EC, 0xFFEDE7FB}));

        head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(20), dp(22), dp(20), dp(22));
        GradientDrawable hb = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF1B1F4B, 0xFF3D2C8D, 0xFF6A3DB8});
        hb.setCornerRadii(new float[]{0, 0, 0, 0, dp(28), dp(28), dp(28), dp(28)});
        head.setBackground(hb);
        head.setElevation(dp(8));

        LinearLayout ligne = new LinearLayout(this);
        ligne.setOrientation(LinearLayout.HORIZONTAL);
        ligne.setGravity(Gravity.CENTER_VERTICAL);
        if (!accueil) {
            TextView ret = new TextView(this);
            ret.setText("‹");
            ret.setTextSize(38);
            ret.setTextColor(Color.WHITE);
            ret.setPadding(0, 0, dp(16), dp(4));
            ret.setOnClickListener(v -> accueil());
            ligne.addView(ret);
        }
        TextView ti = new TextView(this);
        ti.setText(titre);
        ti.setTextSize(accueil ? 28 : 22);
        ti.setTypeface(POLICE_TITRE);
        ti.setTextColor(Color.WHITE);
        ti.setMaxLines(2);
        ligne.addView(ti);
        head.addView(ligne);
        root.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView sv = new ScrollView(this);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(14), dp(14), dp(28));
        sv.addView(box);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        root.setAlpha(0f);
        root.setTranslationX(dp(30));
        root.animate().alpha(1f).translationX(0f).setDuration(280).start();
    }

    // Numéro WhatsApp en texte défilant (touchez pour ouvrir WhatsApp)
    TextView numeroDefilant() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) sb.append("📱 Numéro WhatsApp : ").append(WHATSAPP).append("          •          ");
        TextView m = new TextView(this);
        m.setText(sb.toString());
        m.setTextSize(14);
        m.setTextColor(Color.WHITE);
        m.setTypeface(POLICE_BOUTON);
        m.setSingleLine(true);
        m.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        m.setMarqueeRepeatLimit(-1);
        m.setHorizontallyScrolling(true);
        m.setFocusable(true);
        m.setFocusableInTouchMode(true);
        m.setSelected(true);
        m.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x33FFFFFF);
        g.setCornerRadius(dp(16));
        m.setBackground(g);
        m.setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + WHATSAPP))); }
            catch (Exception e) { Toast.makeText(this, "WhatsApp : " + WHATSAPP, Toast.LENGTH_LONG).show(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(12), 0, 0);
        m.setLayoutParams(lp);
        return m;
    }

    void carte(TextView v) {
        v.setTypeface(POLICE_TEXTE);
        v.setLineSpacing(0, 1.3f);
        v.setLetterSpacing(0.01f);
        v.setTextColor(0xFF2B2150);
        v.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE);
        g.setCornerRadius(dp(18));
        v.setBackground(g);
        v.setElevation(dp(3));
    }

    TextView t(String s, int sz, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sz);
        v.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (bold) {
            v.setTypeface(POLICE_TITRE);
            v.setTextColor(0xFF3D2C8D);
            v.setPadding(dp(4), dp(18), dp(4), dp(6));
        } else if (sz >= 16) {
            v.setTextSize(sz + 1);
            carte(v);
            lp.setMargins(0, dp(6), 0, dp(6));
        } else {
            v.setTextColor(0xFF5A5478);
            v.setPadding(dp(4), dp(8), dp(4), dp(4));
        }
        box.addView(v, lp);
        return v;
    }

    void champ(EditText e) { champ(e, box); }

    void champ(EditText e, LinearLayout p) {
        e.setTextSize(16);
        e.setTextColor(0xFF2B2150);
        e.setHintTextColor(0xFF9A93B8);
        e.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE);
        g.setCornerRadius(dp(16));
        g.setStroke(dp(2), 0xFFB9A8E8);
        e.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), dp(8), dp(4), dp(8));
        p.addView(e, lp);
    }

    Button b(String s, Runnable r) { return b(box, s, r); }

    Button b(LinearLayout p, String s, Runnable r) {
        Button x = new Button(this);
        x.setText(s);
        x.setAllCaps(false);
        x.setTextColor(Color.WHITE);
        x.setTextSize(16);
        x.setTypeface(POLICE_BOUTON);
        x.setLetterSpacing(0.03f);
        x.setGravity(Gravity.CENTER);
        x.setStateListAnimator(null);
        x.setMinHeight(dp(54));
        x.setMinimumHeight(dp(54));
        x.setPadding(dp(20), dp(14), dp(20), dp(14));
        final float rad = dp(26);
        int[] c = PAL[cpt++ % PAL.length];
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, c);
        g.setCornerRadius(rad);
        ClipDrawable fill = new ClipDrawable(new ColorDrawable(0x55FFFFFF), Gravity.LEFT, ClipDrawable.HORIZONTAL);
        LayerDrawable ld = new LayerDrawable(new Drawable[]{g, fill});
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(rad);
        x.setBackground(new RippleDrawable(ColorStateList.valueOf(0x66FFFFFF), ld, mask));
        x.setElevation(dp(5));
        x.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rad); }
        });
        x.setClipToOutline(true);
        x.setOnTouchListener((v, ev) -> {
            int a = ev.getAction();
            if (a == MotionEvent.ACTION_DOWN) v.animate().setStartDelay(0).scaleX(0.96f).scaleY(0.96f).setDuration(90).start();
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) v.animate().setStartDelay(0).scaleX(1f).scaleY(1f).setDuration(140).start();
            return false;
        });
        x.setOnClickListener(v -> {
            if (occupe) return;
            occupe = true;
            ValueAnimator va = ValueAnimator.ofInt(0, 10000);
            va.setDuration(260);
            va.addUpdateListener(an -> fill.setLevel((Integer) an.getAnimatedValue()));
            va.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator an) { occupe = false; fill.setLevel(0); r.run(); }
            });
            va.start();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), dp(7), dp(4), dp(7));
        p.addView(x, lp);
        x.setAlpha(0f);
        x.setTranslationY(dp(24));
        x.animate().alpha(1f).translationY(0f).setStartDelay(Math.min(cpt, 7) * 50L).setDuration(300).start();
        return x;
    }

    // Carte de progression animée (barre + messages qui changent) pendant une longue opération
    LinearLayout progression(LinearLayout res, String[] etapes) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE);
        g.setCornerRadius(dp(18));
        card.setBackground(g);
        card.setElevation(dp(3));
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setIndeterminate(true);
        pb.setIndeterminateTintList(ColorStateList.valueOf(0xFF6A3DB8));
        card.addView(pb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)));
        TextView msg = new TextView(this);
        msg.setText(etapes[0]);
        msg.setTextSize(16);
        msg.setTextColor(0xFF2B2150);
        msg.setPadding(0, dp(14), 0, 0);
        card.addView(msg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(12), 0, dp(8));
        res.addView(card, lp);
        final int[] k = {0};
        if (etapes.length > 1) hd.postDelayed(new Runnable() {
            @Override public void run() {
                if (!card.isAttachedToWindow()) return;
                k[0]++;
                msg.setText(etapes[k[0] % etapes.length]);
                hd.postDelayed(this, 6000);
            }
        }, 6000);
        return card;
    }


    String norm(String s) {
        return Normalizer.normalize(s.toLowerCase(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    void accueil() {
        ecran("📖 Academy biblique", true);
        home = true;
        TextView sub = new TextView(this);
        sub.setText("Préparez vos messages avec la Parole de Dieu");
        sub.setTextSize(14);
        sub.setTextColor(0xCCFFFFFF);
        sub.setPadding(0, dp(4), 0, 0);
        head.addView(sub);
        head.addView(numeroDefilant());
        t("« Ta parole est une lampe à mes pieds, et une lumière sur mon sentier. »\n— Psaumes 119:105", 16, false);
        b("📚 Préparation de mes prédications (thèmes et sujets)", () -> recherche("Thèmes et sujets", themesEtSujets(), this::ouvrirThemeOuSujet));
        b("✍️ ÉCRIRE un thème à développer", this::ecrireTheme);
        b("Prédications développées (commentaires)", this::passage);
        b("Questions / Réponses", () -> recherche("Questions / Réponses", arr("glossary"), this::reponse));
        b("Rechercher un verset", this::verset);
        b("🎬 Générer des vidéos (IA)", () -> BibleVideo.ecran(this));
        b("🔄 Mettre à jour maintenant", () -> {
            if (online()) { Toast.makeText(this, "Mise à jour en cours…", Toast.LENGTH_SHORT).show(); majEnLigne(); }
            else Toast.makeText(this, "Pas de connexion Internet", Toast.LENGTH_SHORT).show();
        });
        t("Thèmes téléchargés : " + arr("topics").length() + " — Questions : " + arr("glossary").length(), 14, false);
    }

    void recherche(String titre, JSONArray a, Ouvrir ou) {
        ecran(titre);
        EditText e = new EditText(this);
        e.setHint("🔍 Rechercher…");
        champ(e);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        box.addView(l);
        e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence c, int x, int y, int z) { }
            public void onTextChanged(CharSequence c, int x, int y, int z) { remplir(l, a, c.toString().trim(), ou); }
            public void afterTextChanged(Editable c) { }
        });
        remplir(l, a, "", ou);
    }

    void remplir(LinearLayout l, JSONArray a, String q, Ouvrir ou) {
        l.removeAllViews();
        cpt = 0;
        String f = norm(q);
        int n = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String nom = o.optString("fr", "");
            if (nom.isEmpty()) nom = o.optString("en");
            if (norm(nom).contains(f) || norm(o.optString("en")).contains(f)) {
                if (++n > 150) break;
                b(l, nom, () -> ou.go(o));
            }
        }
        if (n == 0) {
            TextView v = new TextView(this);
            v.setText("Aucun résultat. Si la liste est vide, connectez-vous à Internet : le téléchargement est automatique.");
            v.setPadding(0, 16, 0, 8);
            l.addView(v);
        }
    }

    // ---------- Thèmes développés (intégrés à l'application, lisibles sans Internet) ----------
    JSONArray themesLocaux() {
        JSONArray out = new JSONArray();
        JSONArray ia = arr("themes_ia");
        for (int i = 0; i < ia.length(); i++) out.put(ia.opt(i));
        try {
            JSONArray b = new JSONObject(BibleThemes.JSON).getJSONArray("themes");
            for (int i = 0; i < b.length(); i++) out.put(b.get(i));
        } catch (Exception e) { }
        return out;
    }

    // Enregistre un thème développé par l'IA : il apparaît dans « Préparation de mes prédications » et reste lisible hors ligne
    void sauverTheme(String th, String texte) {
        try {
            JSONArray old = arr("themes_ia"), n = new JSONArray();
            n.put(new JSONObject().put("fr", th).put("texte", texte).put("ia", true));
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.getJSONObject(i);
                if (!norm(o.optString("fr")).equals(norm(th))) n.put(o);
            }
            sp.edit().putString("themes_ia", n.toString()).apply();
        } catch (Exception e) { }
    }

    void supprimerTheme(String th) {
        try {
            JSONArray old = arr("themes_ia"), n = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.getJSONObject(i);
                if (!norm(o.optString("fr")).equals(norm(th))) n.put(o);
            }
            sp.edit().putString("themes_ia", n.toString()).apply();
        } catch (Exception e) { }
    }

    // Une seule liste : thèmes enregistrés + thèmes intégrés + sujets téléchargés
    JSONArray themesEtSujets() {
        JSONArray out = themesLocaux();
        JSONArray tp = arr("topics");
        for (int i = 0; i < tp.length(); i++) out.put(tp.opt(i));
        return out;
    }

    void ouvrirThemeOuSujet(JSONObject o) {
        if (o.has("id") && !o.has("texte")) sujet(o); else theme(o);
    }

    void theme(JSONObject o) {
        ecran(o.optString("fr"));
        if (o.has("texte")) {
            TextView tv = t("", 16, false);
            tv.setTextIsSelectable(true);
            afficher(box, tv, o.optString("texte"));
            b("🗑 Supprimer ce thème", () -> { supprimerTheme(o.optString("fr")); accueil(); });
            return;
        }
        String[][] s = {{"definition", "Définition"}, {"caracteristiques", "Caractéristiques"}, {"avantages", "Avantages"},
            {"inconvenients", "Inconvénients"}, {"exemples", "Exemples bibliques"}, {"docteurs", "Enseignement des docteurs bibliques"},
            {"concrets", "Exemples concrets"}, {"conclusion", "Conclusion"}};
        for (String[] p : s) {
            String v = o.optString(p[0], "");
            if (!v.isEmpty()) { t(p[1], 18, true); t(v, 16, false); }
        }
        JSONArray v = o.optJSONArray("versets");
        if (v != null) {
            t("Versets à lire (Louis Segond)", 18, true);
            for (int i = 0; i < v.length(); i++) { String r = v.optString(i); b("📖 " + r, () -> lire(r)); }
        }
    }


    // ---------- ÉCRIRE un thème : développé par une IA gratuite (LLM7, OVHcloud, Kilo, Pollinations : sans clé, essayés à tour de rôle) ----------
    // Réglages IA : adresse/clé personnelles facultatives, essayées en premier.
    static final String IA_URL = "https://text.pollinations.ai/openai";
    static final String IA_MODELE = "openai";
    static final String IA_SYSTEME =
        "Tu es un théologien protestant évangélique et un prédicateur expérimenté. Tu écris toujours en français. "
        + "Tu prépares une PRÉDICATION et non une dissertation : style oral, clair, simple et vivant, que le prédicateur peut prêcher tel quel devant une assemblée. "
        + "Reste STRICTEMENT sur le thème donné, sans digression. "
        + "Appuie tout ce que tu dis sur ce que la Bible déclare à propos du thème : cite les versets avec leur référence (version Louis Segond) et explique-les. "
        + "Intègre aussi, avec tes propres mots, l'enseignement des docteurs bibliques reconnus (Augustin, Luther, Calvin, Wesley, Matthew Henry, Spurgeon, Moody, Tozer, Stott…) : "
        + "cite un ouvrage seulement si tu en es certain ; n'invente JAMAIS de citation ni de référence de livre. "
        + "Ne dis rien qui contredise la Bible ou l'enseignement reconnu des docteurs bibliques. "
        + "Phrases et paragraphes courts, exemples concrets de la vie quotidienne. N'écris aucun titre : le sous-titre est déjà affiché. "
        + "Si le sujet n'est pas biblique ou chrétien, dis-le brièvement et propose un angle biblique proche.";

    static final String IA_SYSTEME_PLAN =
        "Tu es un théologien protestant évangélique et un prédicateur expérimenté. Tu écris toujours en français. "
        + "Tu prépares le plan d'une prédication sur un thème biblique, d'après ce que la Bible et les docteurs bibliques reconnus en disent. "
        + "Tu réponds uniquement par la liste des sous-titres demandés, un par ligne, sans numéro, sans explication.";

    static final String IA_SYSTEME_VERSETS =
        "Tu es un théologien protestant évangélique et un prédicateur expérimenté. Tu écris toujours en français. "
        + "Tu proposes uniquement des versets bibliques RÉELS (version Louis Segond), qui parlent vraiment du thème donné. "
        + "Tu réponds uniquement par la liste demandée, un verset par ligne, sans rien d'autre.";

    static final int NB_SOUS_TITRES = 4; // + la conclusion, ajoutée automatiquement à la fin du plan

    // Fournisseurs gratuits sans clé, essayés dans l'ordre jusqu'à ce que l'un réponde : {adresse chat, adresse modèles, modèle imposé}
    static final String[][] FOURNISSEURS = {
        {"https://api.llm7.io/v1/chat/completions", "https://api.llm7.io/v1/models", "default"},
        {"https://oai.endpoints.kepler.ai.cloud.ovh.net/v1/chat/completions", "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1/models", ""},
        {"https://api.kilo.ai/api/gateway/chat/completions", "https://api.kilo.ai/api/gateway/models", ""},
        {IA_URL, "", IA_MODELE}
    };

    String lireRep(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder b = new StringBuilder();
        String l;
        while ((l = r.readLine()) != null) b.append(l).append('\n');
        if (code >= 400) throw new Exception("HTTP " + code);
        return b.toString().trim();
    }

    // Choisit un modèle de texte dans la liste du fournisseur
    String modeleAuto(String urlModeles, String cle) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlModeles).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        if (!cle.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + cle);
        JSONArray d = new JSONObject(lireRep(c)).getJSONArray("data");
        String premier = "", pref = "", gratuit = "";
        for (int i = 0; i < d.length(); i++) {
            String id = d.getJSONObject(i).optString("id", "");
            String n = id.toLowerCase();
            if (id.isEmpty() || n.matches(".*(embed|whisper|image|flux|tts|speech|rerank|bge|stable).*")) continue;
            if (premier.isEmpty()) premier = id;
            if (gratuit.isEmpty() && n.contains("free")) gratuit = id;
            if (pref.isEmpty() && n.matches(".*(llama|qwen|mistral|gpt).*")) pref = id;
        }
        String m = !gratuit.isEmpty() ? gratuit : (!pref.isEmpty() ? pref : premier);
        if (m.isEmpty()) throw new Exception("aucun modèle");
        return m;
    }

    String appeler(String url, String modele, String cle, String systeme, String user, int min) throws Exception {
        JSONObject corps = new JSONObject()
            .put("model", modele)
            .put("max_tokens", 4000)
            .put("messages", new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", systeme))
                .put(new JSONObject().put("role", "user").put("content", user)));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(110000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        if (!cle.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + cle);
        try (OutputStream o = c.getOutputStream()) { o.write(corps.toString().getBytes("UTF-8")); }
        String rep = lireRep(c);
        String m = "";
        try {
            m = new JSONObject(rep).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "");
        } catch (Exception e) {
            if (!rep.isEmpty() && !rep.startsWith("{")) m = rep; // certains services renvoient directement le texte
        }
        m = m.replaceAll("(?s)<think>.*?</think>", "").trim();
        if (m.length() < min) throw new Exception("réponse trop courte");
        return m;
    }

    static String[] bonFournisseur = null; // dernier service qui a répondu : essayé en premier

    String ia(String systeme, String user, int min) throws Exception {
        String cle = sp.getString("ia_cle", "");
        java.util.ArrayList<String[]> liste = new java.util.ArrayList<>();
        String perso = sp.getString("ia_url", IA_URL);
        if (!perso.equals(IA_URL)) liste.add(new String[]{perso, "", sp.getString("ia_modele", IA_MODELE)});
        if (bonFournisseur != null) liste.add(bonFournisseur);
        for (String[] f : FOURNISSEURS) if (f != bonFournisseur) liste.add(f);
        String erreurs = "";
        for (String[] f : liste) {
            try {
                String modele = f[2];
                if (modele.isEmpty()) modele = modeleAuto(f[1], cle.isEmpty() || !f[0].equals(perso) ? "" : cle);
                String r = appeler(f[0], modele, f[0].equals(perso) ? cle : "", systeme, user, min);
                if (!f[0].equals(perso)) bonFournisseur = f;
                return r;
            } catch (Exception x) {
                erreurs += (erreurs.isEmpty() ? "" : " ; ") + Uri.parse(f[0]).getHost() + " : " + x.getMessage();
            }
        }
        throw new Exception(erreurs);
    }

    // Versets proposés pour le thème (affichés avant le plan)
    String versets(String th, String verset) throws Exception {
        String user = "Thème ou sujet : " + th + "\n\n"
            + (verset.isEmpty() ? "" : "Verset de base choisi par le prédicateur : " + verset + "\n\n")
            + "Propose de 6 à 8 versets bibliques réels (Louis Segond) qu'on peut utiliser pour ce thème"
            + (verset.isEmpty() ? "" : " (en lien avec ce verset de base, sans le répéter)") + ". "
            + "Un verset par ligne, au format : Livre chapitre:verset — l'idée du verset en quelques mots. Ne recopie pas le texte du verset, pas de numéro, rien d'autre.";
        String rep = ia(IA_SYSTEME_VERSETS, user, 40);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String l : rep.split("\n")) {
            String x = l.trim().replaceAll("^\\d+[.)]\\s+", "").replaceAll("^[#*\\-•\\s]+", "").replace("**", "").trim();
            if (x.length() < 6 || x.length() > 170 || !x.matches(".*\\d+\\s*:\\s*\\d+.*")) continue;
            sb.append("• ").append(x).append("\n");
            if (++n == 8) break;
        }
        if (n < 3) throw new Exception("versets insuffisants");
        return sb.toString();
    }

    // Étape 1 : l'IA cherche elle-même les sous-titres propres au thème (le 1er = définition / ce qu'on sait du thème), la conclusion est ajoutée à la fin
    String[] planifier(String th, String verset) throws Exception {
        String user = "Thème ou sujet : " + th + "\n\n"
            + (verset.isEmpty() ? "" : "Verset du thème (texte de base choisi par le prédicateur) : " + verset + "\n"
                + "Appuie-toi sur ce verset pour trouver les sous-titres : le plan doit découler de ce verset et du thème.\n\n")
            + "Propose exactement " + NB_SOUS_TITRES + " sous-titres pour une PRÉDICATION sur ce thème (pas une dissertation). "
            + "Le premier sous-titre porte sur la définition du thème ou ce que l'on sait du thème (par exemple « Définition »). "
            + "Les suivants sont propres à CE thème, précis, différents les uns des autres, et suivent ce que la Bible déclare à son sujet jusqu'à la mise en pratique. "
            + "Interdit : « Introduction » et « Conclusion » (la conclusion sera ajoutée ensuite). Réponds uniquement par les " + NB_SOUS_TITRES + " sous-titres, un par ligne.";
        String rep = ia(IA_SYSTEME_PLAN, user, 20);
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (String l : rep.split("\n")) {
            String x = l.trim().replaceAll("^[#*\\-•\\d.)\\s]+", "").replace("**", "").replaceAll("^[«\"]|[»\"]$", "").trim();
            String n = norm(x);
            if (x.length() < 4 || x.length() > 120) continue;
            if (n.matches("^(conclusion|introduction).*")) continue;
            out.add(x);
            if (out.size() == NB_SOUS_TITRES) break;
        }
        if (out.size() < 3) throw new Exception("plan insuffisant");
        out.add("Conclusion");
        return out.toArray(new String[0]);
    }

    // Étape 2 : rédaction d'UN sous-titre du plan à chaque appel
    String consigneSection(String th, String verset, String[] plan, String[] idees, boolean planUtilisateur, int i) {
        StringBuilder sb = new StringBuilder("Thème ou sujet : " + th + "\n");
        if (!verset.isEmpty()) sb.append("Verset du thème (texte de base) : ").append(verset).append("\n");
        sb.append("\nPlan de la prédication :\n");
        for (int k = 0; k < plan.length; k++) sb.append(k + 1).append(". ").append(plan[k]).append("\n");
        if (planUtilisateur) sb.append("\nCe plan a été fixé par le prédicateur : suis-le fidèlement, sans le modifier.\n");
        sb.append("\nRédige maintenant UNIQUEMENT la partie n° ").append(i + 1).append(" « ").append(plan[i]).append(" ». ");
        if (idees[i] != null && !idees[i].isEmpty()) sb.append("Idées données par le prédicateur pour cette partie, à développer : « ").append(idees[i]).append(" ». ");
        if (!verset.isEmpty()) sb.append("Appuie-toi sur le verset du thème quand c'est pertinent. ");
        sb.append("N'écris PAS le sous-titre (il est déjà affiché) : écris seulement le développement, sans aucun titre. ");
        boolean dernier = i == plan.length - 1;
        if (i == 0 && !planUtilisateur) {
            sb.append("Donne la définition du thème ou ce que l'on sait du thème, puis explique ce que la Bible déclare à ce sujet en citant les versets avec leur référence. ");
        } else if (dernier) {
            sb.append("Termine la prédication : résume l'essentiel du thème en quelques phrases, lance un appel final clair, puis une courte prière. ");
        } else {
            sb.append("Explique ce que la Bible déclare sur ce point précis du thème, avec les versets cités et leur référence, et l'éclairage de docteurs bibliques reconnus. ");
        }
        sb.append("Style de prédication : clair, oral, simple, de 250 à 350 mots, sans t'écarter du thème et sans répéter ce qui est dit dans les autres parties.");
        return sb.toString();
    }

    String cleCache(String th, String verset, String[] points) {
        if (verset.isEmpty() && points.length == 0) return "ia3_" + norm(th).hashCode();
        return "ia3_" + norm(th + "|" + verset + "|" + TextUtils.join("|", points)).hashCode();
    }

    SpannableStringBuilder mettreEnForme(String texte) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (String l : texte.replace("\r", "").split("\n")) {
            String t = l.trim();
            if (t.startsWith("#")) {
                int niveau = t.length() - t.replaceAll("^#+", "").length();
                String titre = t.replaceAll("^#+\\s*", "").replace("**", "").trim();
                int d = sb.length();
                sb.append(titre).append("\n");
                sb.setSpan(new StyleSpan(Typeface.BOLD), d, sb.length() - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new RelativeSizeSpan(niveau == 1 ? 1.4f : 1.2f), d, sb.length() - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(0xFF3D2C8D), d, sb.length() - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                sb.append(l.replace("**", "")).append("\n");
            }
        }
        return sb;
    }

    static final int MAX_PLAN = 10; // nombre maximum de cases du plan

    void ecrireTheme() {
        ecran("✍️ Écrire un thème");
        t("Saisissez le thème ou le sujet à développer (ex. : Le pardon, La persévérance, Le Saint-Esprit).", 15, false);
        EditText e = new EditText(this);
        e.setHint("Votre thème ou sujet…");
        e.setSingleLine(false);
        champ(e);

        t("Verset du thème (facultatif) : l'IA s'en sert pour trouver le plan et les développements.", 14, false);
        EditText ev = new EditText(this);
        ev.setHint("Ex. : Jean 3:16");
        ev.setSingleLine(false);
        champ(ev);

        t("Plan (facultatif) : choisissez le nombre de points, puis écrivez pour chacun un titre ou des idées. Sans plan, l'IA le propose.", 14, false);
        java.util.ArrayList<EditText> cases = new java.util.ArrayList<>();
        LinearLayout zone = new LinearLayout(this);
        zone.setOrientation(LinearLayout.VERTICAL);
        Button[] bp = new Button[1];
        bp[0] = b("📋 Plan : aucun point (l'IA le propose)", () -> {
            String[] ch = new String[MAX_PLAN + 1];
            ch[0] = "Aucun (l'IA propose le plan)";
            for (int k = 1; k <= MAX_PLAN; k++) ch[k] = k + (k == 1 ? " point" : " points");
            new AlertDialog.Builder(this).setTitle("Nombre de points du plan").setItems(ch, (dlg, w) -> {
                while (cases.size() > w) { EditText x = cases.remove(cases.size() - 1); zone.removeView(x); }
                while (cases.size() < w) {
                    EditText x = new EditText(this);
                    x.setHint("Point " + (cases.size() + 1) + " : titre ou idées…");
                    x.setSingleLine(false);
                    x.setMinLines(2);
                    champ(x, zone);
                    cases.add(x);
                }
                bp[0].setText(w == 0 ? "📋 Plan : aucun point (l'IA le propose)" : "📋 Plan : " + w + (w == 1 ? " point" : " points") + " (changer)");
            }).show();
        });
        box.addView(zone);

        LinearLayout res = new LinearLayout(this);
        res.setOrientation(LinearLayout.VERTICAL);
        b("Développer ce thème", () -> {
            String th = e.getText().toString().trim();
            if (th.isEmpty()) { Toast.makeText(this, "Saisissez d'abord un thème", Toast.LENGTH_SHORT).show(); return; }
            String ver = ev.getText().toString().trim();
            java.util.ArrayList<String> pts = new java.util.ArrayList<>();
            for (EditText x : cases) { String sx = x.getText().toString().trim(); if (!sx.isEmpty()) pts.add(sx); }
            developper(th, ver, pts.toArray(new String[0]), res);
        });
        box.addView(res);
    }

    void developper(String th, String verset, String[] points, LinearLayout res) {
        res.removeAllViews();
        TextView tv = new TextView(this);
        tv.setTextSize(17);
        tv.setTextIsSelectable(true);
        carte(tv);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(10), 0, dp(6));
        final String ck = cleCache(th, verset, points);
        String done = sp.getString(ck, null);
        if (done != null) {
            sauverTheme(th, done);
            res.addView(tv, lp);
            afficher(res, tv, done);
            ajouterNote(res);
            return;
        }
        if (!online()) {
            tv.setText("Pas de connexion Internet : une connexion Internet est nécessaire pour développer un nouveau thème.");
            res.addView(tv, lp);
            return;
        }
        // Le thème s'affiche tout de suite en gras, puis le texte se construit au fur et à mesure
        tv.setText(mettreEnForme("# " + th));
        res.addView(tv, lp);
        LinearLayout carteProg = progression(res, new String[]{"🔎 Recherche du thème : versets et plan…"});
        ProgressBar pb = (ProgressBar) carteProg.getChildAt(0);
        pb.setIndeterminate(false);
        pb.setMax(100);
        pb.setProgress(4);
        pb.setProgressTintList(ColorStateList.valueOf(0xFF6A3DB8));
        TextView msgProg = (TextView) carteProg.getChildAt(1);
        new Thread(() -> {
            StringBuilder tout = new StringBuilder("# " + th + "\n\n");
            if (!verset.isEmpty()) tout.append("## Verset du thème\n• ").append(verset).append("\n\n");
            // 1) Versets utilisables pour ce thème
            runOnUiThread(() -> msgProg.setText("📖 Recherche des versets qu'on peut utiliser pour ce thème…"));
            String v = null;
            for (int essai = 0; essai < 2 && v == null; essai++) {
                try { v = versets(th, verset); } catch (Exception x) { }
            }
            if (v != null) tout.append("## Versets à utiliser pour ce thème\n").append(v).append("\n");
            final String s1 = tout.toString();
            runOnUiThread(() -> { tv.setText(mettreEnForme(s1)); pb.setProgress(10); });
            // 2) Plan = sous-titres
            final boolean planUtilisateur = points.length > 0;
            runOnUiThread(() -> msgProg.setText(planUtilisateur ? "📋 Lecture de votre plan…" : (verset.isEmpty() ? "📋 Préparation du plan (les sous-titres)…" : "📋 Préparation du plan à partir du verset…")));
            String[] plan = null;
            String[] idees = null;
            if (planUtilisateur) {
                // Plan écrit par le prédicateur : on le suit tel quel (1re ligne = titre, le reste = idées à développer)
                java.util.ArrayList<String> tt = new java.util.ArrayList<>(), dd = new java.util.ArrayList<>();
                for (String pt : points) {
                    String l1 = pt.split("\n")[0].trim();
                    String titre = l1.length() > 100 ? l1.substring(0, 100).trim() + "…" : l1;
                    tt.add(titre);
                    dd.add(pt.equals(titre) ? "" : pt);
                }
                if (!norm(tt.get(tt.size() - 1)).startsWith("conclusion")) { tt.add("Conclusion"); dd.add(""); }
                plan = tt.toArray(new String[0]);
                idees = dd.toArray(new String[0]);
            } else {
                for (int essai = 0; essai < 2 && plan == null; essai++) {
                    try { plan = planifier(th, verset); } catch (Exception x) { }
                }
                if (plan != null) { idees = new String[plan.length]; java.util.Arrays.fill(idees, ""); }
            }
            if (plan == null) {
                String msg = "Impossible de développer ce thème pour le moment. Vérifiez Internet et réessayez dans une minute.";
                runOnUiThread(() -> { res.removeAllViews(); tv.setText(msg); res.addView(tv, lp); });
                return;
            }
            final String[] pl = plan;
            final String[] idl = idees;
            final int n = pl.length;
            tout.append("## Plan de la prédication\n");
            for (int i = 0; i < n; i++) tout.append(i + 1).append(". ").append(pl[i]).append("\n");
            tout.append("\n");
            final String s2 = tout.toString();
            runOnUiThread(() -> { tv.setText(mettreEnForme(s2)); pb.setProgress(15); });
            // 3) Développement : un sous-titre à la fois, avec l'étape affichée (ex. 2/5)
            int ok = 0;
            for (int i = 0; i < n; i++) {
                final int k = i;
                final String fin = i == n - 1 ? "\n\n🏁 Dernière étape : la conclusion !" : (i == n - 2 ? "\n\n⏳ Presque à la fin…" : "");
                runOnUiThread(() -> {
                    msgProg.setText("✍️ Étape " + (k + 1) + "/" + n + " du plan :\n« " + pl[k] + " »" + fin);
                    pb.setProgress(15 + 85 * k / n);
                });
                for (int essai = 0; essai < 2; essai++) {
                    try {
                        String corps = ia(IA_SYSTEME, consigneSection(th, verset, pl, idl, planUtilisateur, k), 200).replaceAll("(?m)^\\s*#+.*\\n?", "").trim();
                        tout.append("## ").append(pl[k]).append("\n").append(corps).append("\n\n");
                        ok++;
                        break;
                    } catch (Exception x) { }
                }
                final String snap = tout.toString();
                runOnUiThread(() -> tv.setText(mettreEnForme(snap)));
            }
            final String r = tout.toString().trim();
            final boolean complet = ok == n;
            if (ok == 0) {
                String msg = "Impossible de développer ce thème pour le moment. Vérifiez Internet et réessayez dans une minute.";
                runOnUiThread(() -> { res.removeAllViews(); tv.setText(msg); res.addView(tv, lp); });
                return;
            }
            if (complet) { sp.edit().putString(ck, r).apply(); sauverTheme(th, r); }
            runOnUiThread(() -> {
                res.removeAllViews();
                res.addView(tv, lp);
                afficher(res, tv, complet ? r : r + "\n\n(Certaines parties n'ont pas pu être rédigées : relancez le thème plus tard pour avoir la prédication complète. Ce texte n'est pas enregistré.)");
                if (complet) ajouterNote(res);
            });
        }).start();
    }

    void ajouterNote(LinearLayout res) {
        TextView n = new TextView(this);
        n.setText("✅ Thème enregistré dans « Préparation de mes prédications » : lisible sans Internet.");
        n.setTextSize(14);
        n.setTextColor(0xFF1E7B4F);
        n.setTypeface(null, android.graphics.Typeface.BOLD);
        n.setPadding(0, 16, 0, 8);
        res.addView(n, 0);
    }

    void afficher(LinearLayout res, TextView tv, String texte) {
        tv.setText(mettreEnForme(texte));
        TextView n = new TextView(this);
        n.setText("ℹ️ Texte généré automatiquement : vérifiez les versets et les citations avant de prêcher.");
        n.setTextSize(13);
        n.setTextColor(0xFF5A5478);
        n.setPadding(0, 16, 0, 8);
        res.addView(n);
        Matcher m = Pattern.compile("((?:[1-3] ?)?[A-ZÉ][\\p{L}]+(?: des Cantiques)?) (\\d{1,3}):(\\d{1,3})").matcher(texte);
        java.util.LinkedHashSet<String> vus = new java.util.LinkedHashSet<>();
        while (m.find() && vus.size() < 15) {
            String ref = m.group(1) + " " + m.group(2) + ":" + m.group(3);
            if (parse(ref) != null) vus.add(ref);
        }
        if (!vus.isEmpty()) {
            TextView h = new TextView(this);
            h.setText("Versets cités (touchez pour lire)");
            h.setTextSize(18);
            h.setTextColor(0xFF3D2C8D);
            h.setTypeface(POLICE_TITRE);
            h.setPadding(0, 16, 0, 8);
            res.addView(h);
            for (String r : vus) b(res, "📖 " + r, () -> lire(r));
        }
    }

    // ---------- Sujet : versets (WideBible) traduits + accès au commentaire de chaque passage ----------
    void sujet(JSONObject o) {
        ecran(o.optString("fr", o.optString("en")));
        LinearLayout mine = box;
        TextView tv = t("Chargement…", 16, false);
        String id = o.optString("id");
        new Thread(() -> {
            String ck = "d_" + id;
            String res = sp.getString(ck, null);
            if (res == null) {
                try {
                    echec = false;
                    res = construire(id);
                    if (!echec) sp.edit().putString(ck, res).apply();
                } catch (Exception e) { res = "Indisponible : connectez-vous à Internet une première fois pour télécharger ce sujet."; }
            }
            String f = res;
            String[] refs = sp.getString("r_" + id, "").split("\n");
            runOnUiThread(() -> {
                tv.setText(f);
                for (String r : refs) {
                    int[] p = parse(r);
                    if (p != null) b(mine, "📚 Commentaire : " + LIVRES[p[0]] + " " + p[1], () -> commentaire(p[0], p[1], "matthew-henry", "Matthew Henry"));
                }
            });
        }).start();
    }

    String prem(JSONObject o, String... ks) {
        for (String k : ks) { String v = o.optString(k, ""); if (!v.isEmpty()) return v; }
        return "";
    }

    String construire(String slug) throws Exception {
        JSONObject j = new JSONObject(get(API + "topics/" + slug + "/"));
        StringBuilder sb = new StringBuilder(), refs = new StringBuilder();
        String d = prem(j, "description", "summary", "overview", "definition", "intro", "content");
        if (!d.isEmpty()) sb.append("Description\n").append(trad(d)).append("\n\n");
        JSONArray v = null;
        for (Iterator<String> it = j.keys(); it.hasNext() && v == null; ) {
            Object x = j.opt(it.next());
            if (x instanceof JSONArray && ((JSONArray) x).length() > 0) v = (JSONArray) x;
        }
        if (v == null) {
            String raw = j.toString();
            return sb.length() > 0 ? sb.toString() : "Format non reconnu : " + raw.substring(0, Math.min(400, raw.length()));
        }
        sb.append("Versets (").append(v.length()).append(")\n\n");
        for (int i = 0; i < v.length() && i < 20; i++) {
            Object x = v.get(i);
            String ref = "", txt;
            if (x instanceof JSONObject) {
                JSONObject o = (JSONObject) x;
                ref = prem(o, "reference", "ref", "verse_ref", "citation");
                txt = prem(o, "text", "content", "verse_text");
                if (ref.isEmpty() && txt.isEmpty()) {
                    String raw = o.toString();
                    sb.append("Format non reconnu : ").append(raw.substring(0, Math.min(300, raw.length())));
                    break;
                }
            } else txt = x.toString();
            if (!ref.isEmpty()) refs.append(ref).append("\n");
            sb.append("• ").append(ref).append("\n").append(trad(txt)).append("\n\n");
        }
        if (v.length() > 20) sb.append("… et ").append(v.length() - 20).append(" autres versets.");
        if (echec) sb.append("\n(Traduction partielle : le modèle de traduction doit se télécharger une fois avec Internet. Rouvrez ce sujet ensuite.)");
        sp.edit().putString("r_" + slug, refs.toString()).apply();
        return sb.toString();
    }

    // Référence -> {livre, chapitre, verset} (noms français ou anglais)
    int[] parse(String ref) {
        Matcher m = Pattern.compile("^(.+?)\\s+(\\d+)(?::(\\d+))?").matcher(ref.trim());
        if (!m.find()) return null;
        String nom = norm(m.group(1).trim());
        int liv = -1;
        if (nom.equals("psalm") || nom.equals("psaume")) liv = 18;
        else if (nom.equals("song of songs") || nom.equals("song of solomon")) liv = 21;
        for (int i = 0; i < LIVRES.length && liv < 0; i++) if (norm(LIVRES[i]).equals(nom) || norm(ANG[i]).equals(nom)) liv = i;
        if (liv < 0) return null;
        return new int[]{liv, Integer.parseInt(m.group(2)), m.group(3) == null ? 0 : Integer.parseInt(m.group(3))};
    }

    // ---------- Prédications développées : commentaires classiques traduits ----------
    void passage() {
        ecran("Prédications développées");
        t("Commentaires verset par verset (exemples, enseignements, applications), traduits en français.", 14, false);
        EditText e = new EditText(this);
        e.setHint("Ex. : Romains 8");
        champ(e);
        String[][] au = {{"matthew-henry", "Matthew Henry"}, {"john-gill", "John Gill"}, {"adam-clarke", "Adam Clarke"}};
        for (String[] a : au)
            b("Commentaire de " + a[1], () -> {
                int[] p = parse(e.getText().toString().trim());
                if (p == null) Toast.makeText(this, "Référence non reconnue (ex. : Romains 8)", Toast.LENGTH_SHORT).show();
                else commentaire(p[0], p[1], a[0], a[1]);
            });
    }

    String txt(Object o) {
        if (o == null) return "";
        if (o instanceof String) return (String) o;
        StringBuilder sb = new StringBuilder();
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) sb.append(txt(a.opt(i))).append(" ");
        } else if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            if (j.has("text")) sb.append(txt(j.opt("text")));
            else if (j.has("content")) sb.append(txt(j.opt("content")));
        }
        return sb.toString().replace("  ", " ");
    }

    void commentaire(int livre, int chap, String auteur, String nomAuteur) {
        String ref = LIVRES[livre] + " " + chap;
        ecran("Commentaire : " + ref);
        t(nomAuteur + " — traduit en français", 14, false);
        b("📖 Texte du chapitre (Louis Segond)", () -> lire(ref));
        TextView tv = t("Chargement…", 16, false);
        String ck = "c_" + auteur + "_" + livre + "_" + chap;
        new Thread(() -> {
            String done = sp.getString(ck, null);
            if (done != null) { runOnUiThread(() -> tv.setText(done)); return; }
            StringBuilder fr = new StringBuilder();
            try {
                JSONObject ch = new JSONObject(get(HELLO + auteur + "/" + CODES[livre] + "/" + chap + ".json")).getJSONObject("chapter");
                String intro = txt(ch.opt("introduction")).trim();
                JSONArray c = ch.getJSONArray("content");
                if (!intro.isEmpty()) {
                    String r = trLong(intro);
                    if (r == null) throw new Exception("trad");
                    fr.append("Introduction\n").append(r).append("\n\n");
                }
                for (int i = 0; i < c.length(); i++) {
                    JSONObject o = c.optJSONObject(i);
                    if (o == null) continue;
                    String en = txt(o.opt("content")).trim();
                    if (en.isEmpty()) continue;
                    String r = trLong(en);
                    if (r == null) throw new Exception("trad");
                    fr.append("verse".equals(o.optString("type")) ? "Verset " + o.optInt("number") + "\n" : "▌ ").append(r).append("\n\n");
                    String f = fr.toString();
                    runOnUiThread(() -> tv.setText(f));
                }
                sp.edit().putString(ck, fr.toString()).apply();
            } catch (Exception e) {
                String f = fr + "\n(Interrompu : vérifiez la connexion. La première fois, le modèle de traduction (environ 30 Mo) se télécharge, puis tout marche sans limite.)";
                runOnUiThread(() -> tv.setText(f));
            }
        }).start();
    }

    // ---------- Questions / Réponses (glossaire) ----------
    void reponse(JSONObject o) {
        String nom = o.optString("fr", o.optString("en"));
        ecran("Qu'est-ce que « " + nom + " » ?");
        TextView tv = t("Chargement…", 16, false);
        String d = o.optString("def", "");
        new Thread(() -> {
            String f;
            if (d.isEmpty()) f = "Pas de définition disponible.";
            else {
                String ck = "g_" + o.optString("id");
                String r = sp.getString(ck, null);
                if (r == null) { r = trLong(d); if (r != null) sp.edit().putString(ck, r).apply(); }
                f = r != null ? r : d + "\n\n(Traduction indisponible : connectez-vous à Internet.)";
            }
            String g = f;
            runOnUiThread(() -> tv.setText(g));
        }).start();
    }

    // ---------- Versets (Louis Segond 1910 via getBible, gardés sur le téléphone) ----------
    void verset() {
        ecran("Rechercher un verset");
        EditText e = new EditText(this);
        e.setHint("Ex. : Jean 3:16 ou Psaumes 23");
        champ(e);
        b("Lire", () -> lire(e.getText().toString().trim()));
    }

    void lire(String ref) {
        ecran(ref);
        TextView tv = t("Chargement…", 17, false);
        new Thread(() -> {
            String r;
            try { r = texte(ref); } catch (Exception e) { r = "Verset indisponible (hors-ligne et pas encore enregistré, ou référence non reconnue)."; }
            String f = r;
            runOnUiThread(() -> tv.setText(f));
        }).start();
    }

    String texte(String ref) throws Exception {
        int[] p = parse(ref);
        if (p == null) throw new Exception();
        String k = "ch_" + (p[0] + 1) + "_" + p[1];
        String s = sp.getString(k, null);
        if (s == null) {
            JSONArray a = new JSONObject(get("https://api.getbible.net/v2/ls1910/" + (p[0] + 1) + "/" + p[1] + ".json")).getJSONArray("verses");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.length(); i++)
                sb.append(a.getJSONObject(i).getInt("verse")).append(". ").append(a.getJSONObject(i).getString("text").trim()).append("\n");
            s = sb.toString();
            sp.edit().putString(k, s).apply();
        }
        if (p[2] == 0) return s;
        for (String l : s.split("\n")) if (l.startsWith(p[2] + ". ")) return ref + "\n" + l;
        throw new Exception();
    }
}
