package com.tvkouame.studio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Ce que l'IA fait pour le studio :
 *  1) proposer un beat complet (tempo, tonalité, accords, batterie, basse, piano, solo) à partir d'une idée ;
 *  2) régler le nettoyage d'un son (réduction de bruit, porte, filtre, clarté) à partir des MESURES du son.
 * L'IA ne reçoit jamais le fichier audio : seulement du texte et des chiffres. Le traitement du son, lui, est
 * fait sur le téléphone par AudioCleaner.
 */
public final class AiAssistant {

    private AiAssistant() {}

    // ------------------------------------------------------------------
    // 1) Composition
    // ------------------------------------------------------------------
    public static final class Proposal {
        public String name = "", advice = "";
        public int bpm = 100, beatsPerBar = 4, key = 0, prog = 0, pianoStyle = 0;
        public boolean[][] drum = new boolean[5][BeatModel.MAXSTEPS];
        public int[] bass = new int[BeatModel.MAXSTEPS];
        public boolean[] piano = new boolean[BeatModel.MAXSTEPS];
        public int[] solo = new int[BeatModel.MAXSTEPS];
        public String provider = "";
        /** Renseigné seulement pour une chanson faite à partir de paroles. */
        public java.util.List<Section> sections = null;
        public String lyricsText = "";

        public String summary() {
            if (sections != null) {
                StringBuilder sb = new StringBuilder();
                int total = 0;
                for (Section x : sections) {
                    total += x.bars;
                    sb.append("• ").append(x.name).append(" : ").append(x.bars).append(" mesures");
                    if (x.from > 0) sb.append(" (paroles ").append(x.from == x.to ? "ligne " + x.from : "lignes " + x.from + "-" + x.to).append(")");
                    else sb.append(" (instrumental)");
                    sb.append('\n');
                }
                int secs = Math.round(total * beatsPerBar * 60f / bpm);
                return String.format(Locale.US, "« %s »\n%d bpm · %d/4 · tonalité %s · %s · %d:%02d\n\n%s%s",
                        name, bpm, beatsPerBar, BeatModel.NOTES[key], BeatModel.PROG_NAMES[prog].trim(), secs / 60, secs % 60,
                        sb, advice.isEmpty() ? "" : "\nConseil : " + advice);
            }
            return String.format(Locale.US, "« %s »\n%d bpm · %d/4 · tonalité %s · %s\nPiano : %s%s",
                    name, bpm, beatsPerBar, BeatModel.NOTES[key], BeatModel.PROG_NAMES[prog].trim(),
                    BeatModel.PIANO_STYLES[pianoStyle], advice.isEmpty() ? "" : "\n\nConseil : " + advice);
        }
    }

    public static final class Section {
        public String name = "";
        public int bars = 4, from = 0, to = 0;
        public boolean[] on = new boolean[BeatModel.NT];   // batterie, basse, piano, solo
    }

    public interface ComposeCb {
        void onProposal(Proposal p);
        void onError(String msg);
    }

    private static String composeSystem() {
        StringBuilder pg = new StringBuilder();
        for (int i = 0; i < BeatModel.PROG_NAMES.length; i++) pg.append(i).append(" = ").append(BeatModel.PROG_NAMES[i].trim()).append("\n");
        return "Tu es un producteur de musique expert. Tu composes un beat sur une grille de pas (doubles-croches). "
                + "Réponds UNIQUEMENT avec un objet JSON valide, sans texte autour, sans ```.\n"
                + "Format :\n"
                + "{\"name\":\"titre court en français\",\"bpm\":40-200,\"beatsPerBar\":2-6,\"key\":0-11,\"prog\":0-8,\"pianoStyle\":0-3,"
                + "\"drum\":{\"kick\":[pas],\"snare\":[pas],\"hat\":[pas],\"open\":[pas],\"clap\":[pas]},"
                + "\"bass\":[{\"s\":pas,\"v\":1-3}],\"piano\":[pas],\"solo\":[{\"s\":pas,\"n\":1-6}],"
                + "\"advice\":\"un conseil de production en une phrase, en français\"}\n"
                + "Règles : les pas vont de 0 à (beatsPerBar*4 - 1) ; un temps = 4 pas. key : 0=Do, 1=Do#, 2=Ré, 3=Ré#, 4=Mi, 5=Fa, 6=Fa#, 7=Sol, 8=Sol#, 9=La, 10=La#, 11=Si. "
                + "prog (suite d'accords) :\n" + pg
                + "pianoStyle : 0=accords plaqués, 1=arpège montant, 2=arpège descendant, 3=arpège aller-retour. "
                + "bass v : 1=fondamentale, 2=quinte, 3=octave. solo n = degré de la gamme (1 à 6). "
                + "Fais un groove musical et cohérent avec le style demandé : pas de pas répétés, pas de doublons.";
    }

    public static void composeBeat(String idea, BeatModel cur, final ComposeCb cb) {
        String user = "Idée du musicien : " + (idea.trim().isEmpty() ? "un beat original et agréable" : idea.trim())
                + "\nBeat actuel (pour info) : " + cur.bpm + " bpm, " + cur.beatsPerBar + "/4, tonalité " + BeatModel.NOTES[cur.key] + ".";
        AiClient.chat(composeSystem(), user, 0.8f, new AiClient.Callback() {
            @Override public void onResult(String text, String provider) {
                try {
                    Proposal p = parseProposal(AiClient.extractJson(text));
                    p.provider = provider;
                    cb.onProposal(p);
                } catch (Exception e) {
                    cb.onError("La réponse de l'IA (" + provider + ") n'est pas exploitable : " + e.getMessage() + ". Réessaie.");
                }
            }
            @Override public void onError(String m) { cb.onError(m); }
        });
    }

    public static void songFromLyrics(String lyrics, final ComposeCb cb) {
        final String[] raw = lyrics.split("\\r?\\n");
        final java.util.List<String> lines = new java.util.ArrayList<>();
        for (String l : raw) if (l.trim().length() > 0) lines.add(l.trim());
        if (lines.size() < 2) { cb.onError("Écris au moins deux lignes de paroles."); return; }
        if (lines.size() > 80) { cb.onError("Paroles trop longues (80 lignes maximum)."); return; }
        StringBuilder num = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) num.append(i + 1).append(". ").append(lines.get(i)).append('\n');
        String sys = composeSystem()
                + "\nTu transformes ici des PAROLES en chanson complète. Déduis le style, l'ambiance et le tempo des paroles. "
                + "Ajoute au JSON le champ \"sections\" : [{\"name\":\"Intro\",\"bars\":2-16,\"drums\":true/false,\"bass\":true/false,\"piano\":true/false,\"solo\":true/false,\"from\":n,\"to\":n}]. "
                + "La structure suit les paroles : intro, couplets, refrain(s), pont éventuel, fin instrumentale. "
                + "from/to = numéros (inclus) des lignes de paroles chantées pendant la section, 0 et 0 si instrumentale. Chaque ligne de paroles occupe environ 2 mesures. "
                + "Chaque ligne doit apparaître dans une section, dans l'ordre. Total entre 16 et 96 mesures. "
                + "Le refrain est plus plein (tous les instruments, solo compris), l'intro et le couplet plus légers.";
        AiClient.chat(sys, "Paroles :\n" + num, 0.7f, new AiClient.Callback() {
            @Override public void onResult(String text, String provider) {
                try {
                    JSONObject j = AiClient.extractJson(text);
                    Proposal p = parseProposal(j);
                    p.provider = provider;
                    parseSections(j, p, lines);
                    cb.onProposal(p);
                } catch (Exception e) {
                    cb.onError("La réponse de l'IA (" + provider + ") n'est pas exploitable : " + e.getMessage() + ". Réessaie.");
                }
            }
            @Override public void onError(String m) { cb.onError(m); }
        });
    }

    static void parseSections(JSONObject j, Proposal p, java.util.List<String> lines) {
        JSONArray a = j.optJSONArray("sections");
        if (a == null || a.length() == 0) throw new IllegalArgumentException("aucune section");
        java.util.List<Section> out = new java.util.ArrayList<>();
        int total = 0;
        for (int i = 0; i < a.length() && total < BeatModel.MAXBARS; i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Section x = new Section();
            x.name = o.optString("name", "Partie " + (i + 1)).trim();
            if (x.name.isEmpty()) x.name = "Partie " + (i + 1);
            x.bars = clamp(o.optInt("bars", 4), 1, 32);
            if (total + x.bars > BeatModel.MAXBARS) x.bars = BeatModel.MAXBARS - total;
            x.on[BeatModel.DRUMS] = o.optBoolean("drums", true);
            x.on[BeatModel.BASS] = o.optBoolean("bass", true);
            x.on[BeatModel.PIANO] = o.optBoolean("piano", true);
            x.on[BeatModel.SOLO] = o.optBoolean("solo", false);
            int f = o.optInt("from", 0), t = o.optInt("to", 0);
            if (f >= 1 && t >= f && t <= lines.size()) { x.from = f; x.to = t; }
            total += x.bars;
            out.add(x);
        }
        if (total < 4) throw new IllegalArgumentException("structure trop courte");
        p.sections = out;
        StringBuilder sb = new StringBuilder();
        int bar = 1;
        for (Section x : out) {
            sb.append("[").append(x.name).append(" — mesure ").append(bar).append("]\n");
            if (x.from > 0) for (int k = x.from; k <= x.to; k++) sb.append(lines.get(k - 1)).append('\n');
            else sb.append("(instrumental)\n");
            sb.append('\n');
            bar += x.bars;
        }
        p.lyricsText = sb.toString().trim();
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    static Proposal parseProposal(JSONObject j) {
        Proposal p = new Proposal();
        p.name = j.optString("name", "Beat IA").trim();
        if (p.name.isEmpty()) p.name = "Beat IA";
        if (p.name.length() > 40) p.name = p.name.substring(0, 40);
        p.bpm = clamp(j.optInt("bpm", 100), 40, 200);
        p.beatsPerBar = clamp(j.optInt("beatsPerBar", 4), 2, 6);
        p.key = clamp(j.optInt("key", 0), 0, 11);
        p.prog = clamp(j.optInt("prog", 0), 0, BeatModel.PROG_NAMES.length - 1);
        p.pianoStyle = clamp(j.optInt("pianoStyle", 0), 0, BeatModel.PIANO_STYLES.length - 1);
        p.advice = j.optString("advice", "").trim();
        int spb = p.beatsPerBar * 4;
        JSONObject d = j.optJSONObject("drum");
        String[] names = {"kick", "snare", "hat", "open", "clap"};
        if (d != null) for (int r = 0; r < 5; r++) {
            JSONArray a = d.optJSONArray(names[r]);
            if (a != null) for (int i = 0; i < a.length(); i++) { int s = a.optInt(i, -1); if (s >= 0 && s < spb) p.drum[r][s] = true; }
        }
        JSONArray b = j.optJSONArray("bass");
        if (b != null) for (int i = 0; i < b.length(); i++) {
            JSONObject o = b.optJSONObject(i);
            if (o == null) continue;
            int s = o.optInt("s", -1);
            if (s >= 0 && s < spb) p.bass[s] = clamp(o.optInt("v", 1), 1, 3);
        }
        JSONArray pi = j.optJSONArray("piano");
        if (pi != null) for (int i = 0; i < pi.length(); i++) { int s = pi.optInt(i, -1); if (s >= 0 && s < spb) p.piano[s] = true; }
        JSONArray so = j.optJSONArray("solo");
        if (so != null) for (int i = 0; i < so.length(); i++) {
            JSONObject o = so.optJSONObject(i);
            if (o == null) continue;
            int s = o.optInt("s", -1);
            if (s >= 0 && s < spb) p.solo[s] = clamp(o.optInt("n", 1), 1, 6);
        }
        // un beat sans batterie ou sans basse n'est pas un beat : on refuse plutôt que d'appliquer du vide
        boolean anyDrum = false, anyBass = false, anyPiano = false;
        for (int r = 0; r < 5; r++) for (int s = 0; s < spb; s++) anyDrum |= p.drum[r][s];
        for (int s = 0; s < spb; s++) { anyBass |= p.bass[s] > 0; anyPiano |= p.piano[s]; }
        if (!anyDrum || !anyBass || !anyPiano) throw new IllegalArgumentException("beat incomplet (batterie, basse ou piano vide)");
        return p;
    }

    /** Remplace les motifs du beat par la proposition (les sons enregistrés ou importés sont conservés). */
    public static void apply(BeatModel m, Proposal p) {
        m.name = p.name;
        m.bpm = p.bpm;
        m.beatsPerBar = p.beatsPerBar;
        m.applyProgression(p.key, p.prog);
        m.pianoStyle = p.pianoStyle;
        m.style = -1;
        for (int r = 0; r < 5; r++) for (int s = 0; s < BeatModel.MAXSTEPS; s++) m.drum[r][s] = p.drum[r][s];
        for (int s = 0; s < BeatModel.MAXSTEPS; s++) { m.bassStep[s] = p.bass[s]; m.pianoHit[s] = p.piano[s]; m.soloStep[s] = p.solo[s]; }
        // les notes libres posées avant ne doivent pas masquer le nouveau motif
        m.notes.clear();
        for (int t = 0; t < BeatModel.NT; t++) for (int b = 0; b < BeatModel.MAXBARS; b++) m.custom[t][b] = false;
        m.composed = false;
        if (p.sections != null) {
            int bar = 0;
            for (int t = 0; t < BeatModel.NT; t++) for (int b = 0; b < BeatModel.MAXBARS; b++) m.play[t][b] = false;
            for (Section x : p.sections)
                for (int k = 0; k < x.bars && bar < BeatModel.MAXBARS; k++, bar++)
                    for (int t = 0; t < BeatModel.NT; t++) m.play[t][bar] = x.on[t];
            m.bars = Math.max(1, bar);
        }
    }

    // ------------------------------------------------------------------
    // 2) Réglages de nettoyage
    // ------------------------------------------------------------------
    public interface CleanCb {
        /** advice peut être vide ; fromAi = faux si l'IA était injoignable (réglages calculés sans IA). */
        void onParams(AudioCleaner.Params p, String advice, boolean fromAi, String info);
    }

    public static void tuneCleaning(AudioCleaner.Stats st, final CleanCb cb) {
        final AudioCleaner.Params fallback = AudioCleaner.Params.auto(st);
        String sys = "Tu es un ingénieur du son. À partir des mesures d'un enregistrement, tu choisis les réglages d'un nettoyage "
                + "(réduction de bruit, porte, filtre des graves, clarté). Réponds UNIQUEMENT avec un objet JSON valide, sans texte autour :\n"
                + "{\"hpHz\":30-200,\"denoise\":0-1,\"gateDb\":0-30,\"presenceDb\":-4..6,\"targetPeakDb\":-6..-0.5,\"advice\":\"conseil en une ou deux phrases, en français\"}\n"
                + "Repères : rapport signal/bruit < 20 dB = bruit fort (denoise 0,7-0,9, gateDb 15-24) ; > 40 dB = déjà propre (denoise <= 0,3, gateDb <= 8). "
                + "Beaucoup d'énergie sous 250 Hz = souffle ou ronflement : monter hpHz. Peu d'aigus = son sourd : presenceDb positif. "
                + "Saturation > 0,5 % = signal abîmé : le dire dans le conseil. Ne dépasse jamais les bornes.";
        String user = "Mesures : " + st.toText() + "\nRéglages de départ calculés : " + fallback.toText();
        AiClient.chat(sys, user, 0.2f, new AiClient.Callback() {
            @Override public void onResult(String text, String provider) {
                try {
                    JSONObject j = AiClient.extractJson(text);
                    AudioCleaner.Params p = new AudioCleaner.Params();
                    p.hpHz = (float) j.optDouble("hpHz", fallback.hpHz);
                    p.denoise = (float) j.optDouble("denoise", fallback.denoise);
                    p.gateDb = (float) j.optDouble("gateDb", fallback.gateDb);
                    p.presenceDb = (float) j.optDouble("presenceDb", fallback.presenceDb);
                    p.targetPeakDb = (float) j.optDouble("targetPeakDb", fallback.targetPeakDb);
                    p.clamp();
                    cb.onParams(p, j.optString("advice", "").trim(), true, provider);
                } catch (Exception e) {
                    cb.onParams(fallback, "", false, "réponse IA inexploitable");
                }
            }
            @Override public void onError(String m) { cb.onParams(fallback, "", false, m); }
        });
    }
}
