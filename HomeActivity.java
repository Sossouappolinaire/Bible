package com.tvkouame.studio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * Accueil : icône, Inscription, Connexion.
 * PIN propriétaire : Enregistrer + Studio + Academy biblique. Tout autre PIN : Academy biblique seulement.
 */
public class HomeActivity extends Activity {
    private static final int ST_ACCUEIL = 0, ST_INSCRIPTION = 1, ST_CONNEXION = 2, ST_MENU = 3;
    private int etat = ST_ACCUEIL;
    private boolean demande = false, transfert = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Comptes.connecte(this)) menu(); else accueil();
        verifierDossier();
    }

    @Override
    protected void onResume() {
        super.onResume();
        verifierDossier();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        verifierDossier();
    }

    @Override
    public void onBackPressed() {
        if (etat == ST_INSCRIPTION || etat == ST_CONNEXION) accueil(); else super.onBackPressed();
    }

    // ---------- Dossier KouameAp ----------
    private void verifierDossier() {
        if (KouameAp.acces(this)) {
            if (!KouameAp.pret(this) && !transfert) lancerTransfert();
        } else if (!demande) {
            demande = true;
            KouameAp.demanderAcces(this);
        }
    }

    private void lancerTransfert() {
        transfert = true;
        final AlertDialog d = new AlertDialog.Builder(this)
            .setMessage("Préparation du dossier « " + KouameAp.NOM + " » : transfert et restauration de vos données…")
            .setCancelable(false).show();
        new Thread(() -> {
            try { KouameAp.initialiser(getApplicationContext()); } catch (Exception e) { }
            runOnUiThread(() -> {
                try { d.dismiss(); } catch (Exception e) { }
                transfert = false;
                Toast.makeText(this, "Dossier « " + KouameAp.NOM + " » prêt : vos données y sont enregistrées.", Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    // ---------- Éléments d'interface ----------
    private LinearLayout page() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.setFillViewport(true);
        int pad = Ui.dp(this, 20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad * 2);
        sv.addView(root, new ScrollView.LayoutParams(-1, -2));
        setContentView(sv);
        return root;
    }

    private void logo(LinearLayout r, int tailleDp) {
        LogoView lv = new LogoView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, tailleDp), Ui.dp(this, tailleDp));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.setMargins(0, 0, 0, Ui.dp(this, 14));
        r.addView(lv, lp);
    }

    private void titre(LinearLayout r, String t, String sous) {
        TextView title = Ui.text(this, t, 30, Ui.TEXT, true);
        title.setGravity(Gravity.CENTER);
        r.addView(title);
        TextView sub = Ui.text(this, sous, 15, Ui.MUTED, false);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 24));
        r.addView(sub);
    }

    private Button bouton(String label, int couleur, Runnable action) {
        Button b = Ui.button(this, label, couleur);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private EditText champ(LinearLayout r, String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextSize(16);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.MUTED);
        int p = Ui.dp(this, 14);
        e.setPadding(p, p, p, p);
        e.setBackground(Ui.rounded(Ui.CARD, Ui.dp(this, 14)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        r.addView(e, lp);
        return e;
    }

    private TextView erreur(LinearLayout r) {
        TextView t = Ui.text(this, "", 14, Ui.RED, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        r.addView(t);
        return t;
    }

    private static final int TYPE_PIN = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    private static final int TYPE_EMAIL = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;

    private static String mail(EditText e) { return e.getText().toString().trim().toLowerCase(Locale.ROOT); }

    // ---------- Écrans ----------
    private void accueil() {
        etat = ST_ACCUEIL;
        LinearLayout r = page();
        logo(r, 120);
        titre(r, "TvKouame Studio", "Bienvenue");
        r.addView(bouton("📝  Inscription", 0xFF2B6CB0, this::inscription));
        r.addView(bouton("🔑  Connexion", 0xFF6A3DB8, this::connexion));
        TextView note = Ui.text(this, "L'inscription fonctionne avec ou sans Internet.", 13, Ui.MUTED, false);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, Ui.dp(this, 14), 0, 0);
        r.addView(note);
    }

    private void inscription() {
        etat = ST_INSCRIPTION;
        LinearLayout r = page();
        logo(r, 80);
        titre(r, "Inscription", "Créez votre compte");
        final EditText nom = champ(r, "Nom", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        final EditText email = champ(r, "Email", TYPE_EMAIL);
        final EditText pin = champ(r, "PIN (4 chiffres)", TYPE_PIN);
        pin.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        final TextView err = erreur(r);
        r.addView(bouton("✅  Valider l'inscription", Ui.GREEN, () -> {
            String n = nom.getText().toString().trim(), e = mail(email), p = pin.getText().toString().trim();
            if (n.isEmpty()) { err.setText("Saisissez votre nom."); return; }
            if (!e.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) { err.setText("Email non valide."); return; }
            if (!p.matches("\\d{4}")) { err.setText("Le PIN doit avoir exactement 4 chiffres."); return; }
            String er = Comptes.inscrire(this, n, e, p);
            if (er != null) { err.setText(er); return; }
            Toast.makeText(this, "Inscription validée. Bienvenue " + n + " !", Toast.LENGTH_SHORT).show();
            menu();
        }));
        r.addView(bouton("🔑  J'ai déjà un compte : Connexion", 0xFF6A3DB8, this::connexion));
        r.addView(bouton("←  Retour à l'accueil", 0xFF3C4D63, this::accueil));
    }

    private void connexion() {
        etat = ST_CONNEXION;
        LinearLayout r = page();
        logo(r, 80);
        titre(r, "Connexion", "Entrez votre email et votre PIN");
        final EditText email = champ(r, "Email", TYPE_EMAIL);
        final EditText pin = champ(r, "PIN (4 chiffres)", TYPE_PIN);
        pin.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        final TextView err = erreur(r);
        r.addView(bouton("🔓  Se connecter", Ui.GREEN, () -> {
            String e = mail(email), p = pin.getText().toString().trim();
            if (e.isEmpty() || p.isEmpty()) { err.setText("Saisissez votre email et votre PIN."); return; }
            String er = Comptes.connecter(this, e, p);
            if (er != null) { err.setText(er); return; }
            menu();
        }));
        r.addView(bouton("←  Retour à l'inscription", 0xFF2B6CB0, this::inscription));
        r.addView(bouton("←  Retour à l'accueil", 0xFF3C4D63, this::accueil));
    }

    private void menu() {
        etat = ST_MENU;
        LinearLayout r = page();
        logo(r, 72);
        titre(r, "TvKouame Studio", "Bonjour " + Comptes.nom(this) + ", que voulez-vous faire ?");
        if (Comptes.patron(this)) {
            r.addView(bigButton("🎙", "Enregistrer",
                "Enregistrer depuis la table de mixage ou le micro, son nettoyé, export MP3 / AMR / WAV.",
                Ui.RED, RecordActivity.class));
            r.addView(bigButton("🎹", "Studio",
                "Créer un beat (batterie, basse, piano, solo), choisir le tempo et les accords, importer ou enregistrer un son dessus, mélanger et exporter.",
                0xFF2B6CB0, StudioActivity.class));
        }
        r.addView(bigButton("📖", "Academy biblique",
            "Lire la Bible, développer un thème ou un sujet, commentaires, versets, et créer des vidéos. Les thèmes développés restent disponibles hors ligne.",
            0xFF6A3DB8, BibleActivity.class));
        r.addView(bouton("Se déconnecter", 0xFF3C4D63, () -> { Comptes.deconnecter(this); accueil(); }));
    }

    private LinearLayout bigButton(String icon, String label, String desc, int color, final Class<?> target) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = Ui.dp(this, 22);
        card.setPadding(p, p, p, p);
        card.setBackground(Ui.rounded(color, Ui.dp(this, 22)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, Ui.dp(this, 16));
        card.setLayoutParams(lp);
        card.setClickable(true);
        card.setOnClickListener(v -> startActivity(new Intent(this, target)));

        TextView ic = Ui.text(this, icon, 44, 0xFFFFFFFF, false);
        ic.setGravity(Gravity.CENTER);
        TextView t = Ui.text(this, label, 24, 0xFFFFFFFF, true);
        t.setGravity(Gravity.CENTER);
        TextView d = Ui.text(this, desc, 13, 0xFFE6EEF7, false);
        d.setGravity(Gravity.CENTER);
        d.setPadding(0, Ui.dp(this, 6), 0, 0);
        card.addView(ic);
        card.addView(t);
        card.addView(d);
        return card;
    }
}
